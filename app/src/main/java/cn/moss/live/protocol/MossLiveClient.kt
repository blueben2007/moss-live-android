package cn.moss.live.protocol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okio.ByteString
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** 一块下行音频（48kHz 单声道 PCM S16LE），归属信息来自前置的 response.audio.context。 */
data class AudioChunk(
    val runId: String,
    val turnId: String,
    val chunkId: String,
    val pcm: ByteArray,
) : MossEvent

/**
 * MOSS-Live 实时会话客户端：WSS 连接、事件解析、二进制音频配对、
 * 播放回执、应用层心跳与断线退避重连。
 *
 * 协议落点（对应预览验收门第④项）：
 * ① connect 后首条消息必为 attach_session（可带原 session_id 重连）
 * ② 上行音频为 16kHz PCM 二进制帧（由 MicCapture 按实时速率供给）
 * ③ 下行音频 48kHz PCM，二进制帧与其归属由 response.audio.context 一一配对
 * ④ 每块播完回 playback.chunk.completed，整条播完回 playback.completed
 * ⑤ 被打断（response.audio.stop）由消费方停播清队后回 playback.stopped
 * ⑥ ping/pong 心跳维持连接
 * ⑦ session.closing(reconnect=true) 或连接异常 → 递增退避、原 session_id 重连
 * ⑧ transcript 类事件 text 整体替换
 */
class MossLiveClient(
    private val apiKeyProvider: () -> String,
    private val wsUrl: String = "wss://api.mosi.cn/v1/realtime?model=moss-live-1.0",
) {

    enum class ConnState { IDLE, CONNECTING, CONNECTED, RECONNECTING, CLOSED }

    private val okhttp = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS) // 传输层保活；协议层心跳另发 {"action":"ping"}
        .build()

    private val _events = MutableSharedFlow<MossEvent>(
        extraBufferCapacity = 512,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<MossEvent> = _events

    private val _connState = MutableStateFlow(ConnState.IDLE)
    val connState: StateFlow<ConnState> = _connState

    private val _bytesSent = MutableStateFlow(0L)

    /** 累计成功入队上行的音频字节数，供诊断显示。 */
    val bytesSent: StateFlow<Long> = _bytesSent

    private val _recentEvents = MutableStateFlow<List<String>>(emptyList())

    /** 最近收到的服务端事件名（最多 6 个），供诊断显示。 */
    val recentEvents: StateFlow<List<String>> = _recentEvents

    private var ws: WebSocket? = null
    private val pendingMeta = AtomicReference<MossEvent.AudioChunkMeta?>(null)
    @Volatile private var sessionId: String? = null
    @Volatile private var memoryScope: String? = null
    @Volatile private var userId: String? = null
    @Volatile private var interpret = false
    @Volatile private var connectUrl: String = wsUrl
    @Volatile private var userClosed = false
    @Volatile private var reconnectAttempt = 0
    private var scope: CoroutineScope = newScope()
    private var pingJob: Job? = null

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * sessionId=null 建新会话；传原 session_id 恢复会话。memoryScope="user" 开长期记忆。
     * userId：本机稳定标识，memory_scope=user 时连接 URL 必须带 user 参数。
     * urlOverride + interpret=true：连同传端点（/v1/realtime/interpret）——attach 后
     * 须发 media.mode(tagged-v1)，上行音频带 0x00 前缀，不传 user 等额外查询参数。
     */
    fun connect(
        sessionId: String?,
        memoryScope: String?,
        userId: String? = null,
        urlOverride: String? = null,
        interpret: Boolean = false,
    ) {
        userClosed = false
        reconnectAttempt = 0
        this.sessionId = sessionId
        this.memoryScope = memoryScope
        this.userId = if (interpret) null else userId
        this.interpret = interpret
        this.connectUrl = urlOverride ?: wsUrl
        scope = newScope()
        openSocket()
    }

    private fun openSocket() {
        _connState.value = if (reconnectAttempt == 0) ConnState.CONNECTING else ConnState.RECONNECTING
        var url = connectUrl
        if (!interpret && !userId.isNullOrBlank()) {
            url = "$url&user=${java.net.URLEncoder.encode(userId, "UTF-8")}"
        }
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${apiKeyProvider()}")
            .build()
        ws = okhttp.newWebSocket(request, listener)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            reconnectAttempt = 0
            webSocket.send(MossCommand.attachSession(sessionId, memoryScope))
            if (interpret) {
                // 同传端点时序：attach → media.mode(tagged-v1) → 开麦
                webSocket.send(MossCommand.mediaModeTagged())
                webSocket.send(MossCommand.micState(true))
                // 通知仓库层就绪（服务端不回 session.updated，用合成事件接续流程）
                _events.tryEmit(MossEvent.SessionUpdated("", emptyList()))
            }
            startPingLoop()
        }

        override fun onMessage(webSocket: WebSocket, text: String) = handleText(text)

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handleBinary(bytes)

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            stopPing()
            maybeReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            stopPing()
            _events.tryEmit(
                MossEvent.Error(response?.code?.toString(), t.message ?: "connection failure"),
            )
            maybeReconnect()
        }
    }

    private fun handleText(text: String) {
        var action = "malformed"
        val event = try {
            val root = JSONObject(text)
            action = root.optString("action")
            MossEvent.parse(action, root)
        } catch (e: Exception) {
            MossEvent.Unknown("malformed-json")
        }
        _recentEvents.value = (_recentEvents.value + action).takeLast(6)
        when (event) {
            is MossEvent.SessionAttached -> {
                sessionId = event.sessionId // 保存，供重连恢复
                _connState.value = ConnState.CONNECTED
            }
            is MossEvent.SessionClosing -> {
                if (!event.reconnect) userClosed = true // 服务端明确不恢复
            }
            else -> Unit
        }
        _events.tryEmit(event)
    }

    private fun handleBinary(bytes: ByteString) {
        val meta = pendingMeta.getAndSet(null) ?: return // 无归属信息的二进制帧丢弃
        _events.tryEmit(
            AudioChunk(meta.runId, meta.turnId, meta.chunkId, bytes.toByteArray()),
        )
    }

    /** 递增退避重连：1s,2s,4s,8s,16s 后封顶 30s，用原 session_id 恢复。 */
    private fun maybeReconnect() {
        if (userClosed) {
            _connState.value = ConnState.CLOSED
            return
        }
        val delayMs = minOf(30_000L, 1_000L shl reconnectAttempt.coerceAtMost(5))
        reconnectAttempt++
        _connState.value = ConnState.RECONNECTING
        scope.launch {
            delay(delayMs)
            if (!userClosed) openSocket()
        }
    }

    private fun startPingLoop() {
        stopPing()
        pingJob = scope.launch {
            while (isActive && !userClosed) {
                delay(25_000)
                ws?.send(MossCommand.ping())
            }
        }
    }

    private fun stopPing() {
        pingJob?.cancel()
        pingJob = null
    }

    // ---- 上行 ----

    /** 16kHz PCM S16LE 音频块（MicCapture 按实时速率持续供给，静音也照发）。 */
    fun sendAudio(pcm16kLe: ByteArray) {
        val buffer = okio.Buffer()
        if (interpret) buffer.writeByte(0x00) // tagged-v1：音频帧标记
        buffer.write(pcm16kLe, 0, pcm16kLe.size)
        if (ws?.send(buffer.readByteString()) == true) {
            _bytesSent.value += pcm16kLe.size + if (interpret) 1 else 0
        }
    }

    fun micState(enabled: Boolean) {
        ws?.send(MossCommand.micState(enabled))
    }

    fun updateInstructions(instructions: String) {
        ws?.send(MossCommand.updateInstructions(instructions))
    }

    fun changeVoice(voiceId: String) {
        ws?.send(MossCommand.changeVoice(voiceId))
    }

    fun chunkCompleted(runId: String, turnId: String?, chunkId: String) {
        ws?.send(MossCommand.playbackChunkCompleted(runId, turnId, chunkId))
    }

    fun playbackCompleted(runId: String, turnId: String?) {
        ws?.send(MossCommand.playbackCompleted(runId, turnId))
    }

    fun playbackStopped(runId: String, turnId: String?, playedAudioMs: Long) {
        ws?.send(MossCommand.playbackStopped(runId, turnId, playedAudioMs))
    }

    /** 挂断：不再重连，释放连接。 */
    fun close() {
        userClosed = true
        stopPing()
        scope.cancel()
        ws?.close(1000, "client hangup")
        ws = null
        pendingMeta.set(null)
        _connState.value = ConnState.CLOSED
    }

    fun currentSessionId(): String? = sessionId
}
