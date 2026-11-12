package cn.moss.live.session

import cn.moss.live.audio.MicCapture
import cn.moss.live.audio.SpeakerPlayback
import cn.moss.live.data.db.AppDatabase
import cn.moss.live.data.db.MessageEntity
import cn.moss.live.data.db.Role
import cn.moss.live.data.db.SessionEntity
import cn.moss.live.data.db.SessionType
import cn.moss.live.data.prefs.AppSettings
import cn.moss.live.data.prefs.SettingsDataStore
import cn.moss.live.protocol.AudioChunk
import cn.moss.live.protocol.MossEvent
import cn.moss.live.protocol.MossLiveClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID

/**
 * 会话状态机（单例）：UI 与前台服务都经它通信。
 *
 * 两种模式走两个端点：
 * - 助理：/v1/realtime 全双工对话。麦克风常开、扬声器播放、可打断。
 * - 翻译：/v1/realtime/interpret 专用同传端点。只听不说——不实例化 SpeakerPlayback，
 *   但译文音频到达后必须**立即补发播放回执**（服务端靠回执推进下一句），否则字幕卡死。
 *
 * 翻译状态按 turn 编号驱动：译句可能滞后于语音，来源/译文用 map 配对。
 * 中文输入（源=目标语言）服务端可能不产生译文：3 秒未到译文则把原文提升为大字显示。
 *
 * 会话切分：助理拨通→挂断、翻译开始→停止，各为一条记录；无消息的会话不落库。
 */
class VoiceSessionRepository(
    private val client: MossLiveClient,
    private val mic: MicCapture,
    private val db: AppDatabase,
    private val settingsStore: SettingsDataStore,
    private val scope: CoroutineScope,
) {

    companion object {
        /** 助理默认人设（极简无名），自定义人设留空时使用。 */
        const val DEFAULT_PERSONA = "用简洁口语化的中文回答，短句优先，不啰嗦。"

        /** 同传专用端点：target 固定中文，source 省略＝自动检测。 */
        const val INTERPRET_URL = "wss://api.mosi.cn/v1/realtime/interpret?model=moss-live-1.0&target=zh"

        /** 原文提升为译文显示前的等待毫秒数（等源语言≠中文时的翻译产出）。 */
        private const val PROMOTE_AFTER_MS = 3_000L
    }

    private val _ui = MutableStateFlow(SessionUiState())
    val ui: StateFlow<SessionUiState> = _ui

    /** 麦克风音量电平，驱动声纹动画。 */
    val micLevel get() = mic.level

    /** 联调开关：翻译会话内试听模型产出的译文语音。 */
    private val _translateMonitor = MutableStateFlow(false)

    /** 供设置页读取/写入试听开关状态。 */
    val translateMonitor: StateFlow<Boolean> = _translateMonitor

    fun setTranslateMonitor(on: Boolean) {
        _translateMonitor.value = on
        if (!on) {
            if (_ui.value.mode == SessionType.TRANSLATE) {
                speaker?.stopAll()
                speaker = null
            }
        } else if (_ui.value.mode == SessionType.TRANSLATE && _ui.value.isActive && speaker == null) {
            speaker = SpeakerPlayback(client).also { it.start(scope) }
        }
    }

    private var eventsJob: Job? = null
    @Volatile private var speaker: SpeakerPlayback? = null
    @Volatile private var dbSessionId: String? = null
    @Volatile private var messageCount = 0

    /** 非空表示本次助理会话要先下发自定义人设（attach → session.update → 等 updated → 开麦）。 */
    @Volatile private var pendingPersonaUpdate: String? = null

    /** 助理空人设（裸 attach）时置位：收到 session_attached 后再开麦。 */
    @Volatile private var bareStartPending = false

    // ---- 翻译会话状态（turn → 内容） ----
    private val turnSourceFinal = mutableMapOf<Int, String>()
    private val turnSourceLive = mutableMapOf<Int, String>()
    private val turnTransFinal = mutableMapOf<Int, String>()
    private val turnTransLive = mutableMapOf<Int, String>()
    private val turnPromoted = mutableSetOf<Int>()
    private val promoteJobs = mutableMapOf<Int, Job>()

    // ---- 翻译模式译文音频回执（不播放但必须确认，服务端才推下一句） ----
    private val audioDoneRuns = mutableSetOf<String>()
    private val playbackAckedRuns = mutableSetOf<String>()

    // ---- 助理进行中的回复转写（被打断/异常结束时也要落库，防记录缺失） ----
    @Volatile private var liveOutputRunId: String? = null
    @Volatile private var liveOutputText: String = ""

    // ---- 开始 / 结束 ----

    suspend fun startAssistant() {
        val settings = settingsStore.snapshot()
        if (settings.apiKey.isBlank()) {
            _ui.value = SessionUiState(phase = Phase.Failed("请先在设置里配置 API Key"))
            return
        }
        beginSession(SessionType.ASSISTANT)
        collectEvents(translate = false)
        val persona = settings.persona
        // 先登记待办再连接：避免 session_attached 早于标志位赋值（竞态）。
        if (persona.isBlank()) {
            // Playground 同款流程：裸 attach → 等 session_attached → 开麦，不发 session.update
            bareStartPending = true
        } else {
            // 自定义人设：attach → session.update → 等 session.updated → 开麦
            pendingPersonaUpdate = persona
        }
        client.connect(null, if (settings.memoryEnabled) "user" else "session", settingsStore.ensureUserId())
    }

    /**
     * 助理开麦 + 起扬声器。必须在 session_attached 之后调用：协议要求 attach_session
     * 是第一条消息，此前就 mic_state / 上行音频会违反握手顺序。
     */
    private fun startAssistantAudio() {
        client.micState(true)
        mic.start(android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION)
        if (speaker == null) {
            speaker = SpeakerPlayback(client).also { it.start(scope) }
        }
        _ui.update { it.copy(phase = Phase.Listening) }
    }

    suspend fun startTranslate() {
        val settings = settingsStore.snapshot()
        if (settings.apiKey.isBlank()) {
            _ui.value = SessionUiState(phase = Phase.Failed("请先在设置里配置 API Key"))
            return
        }
        beginSession(SessionType.TRANSLATE)
        collectEvents(translate = true)
        // 同传端点：不带 user/memory 参数（无会话恢复，每条连接新会话）
        client.connect(null, null, null, urlOverride = interpretUrl(settings), interpret = true)
    }

    /** 同传端点 URL：target 固定中文；源语言可显式指定（服务端自动检测异常时兜底）。 */
    private fun interpretUrl(settings: AppSettings): String =
        if (settings.translateSource.isBlank()) INTERPRET_URL
        else "wss://api.mosi.cn/v1/realtime/interpret?model=moss-live-1.0&source=${settings.translateSource}&target=zh"

    private suspend fun beginSession(mode: SessionType) {
        val id = UUID.randomUUID().toString()
        dbSessionId = id
        messageCount = 0
        pendingPersonaUpdate = null
        bareStartPending = false
        db.sessionDao().insert(
            SessionEntity(
                id = id,
                type = mode,
                title = title(mode, System.currentTimeMillis()),
                startedAt = System.currentTimeMillis(),
                endedAt = null,
            ),
        )
        if (mode == SessionType.TRANSLATE) clearTranslateState()
        _ui.value = SessionUiState(
            mode = mode,
            phase = Phase.Connecting,
            dbSessionId = id,
            startedAt = System.currentTimeMillis(),
        )
    }

    private fun collectEvents(translate: Boolean) {
        eventsJob?.cancel()
        eventsJob = scope.launch {
            client.events.collect { event -> handle(event, translate) }
        }
    }

    // ---- 事件处理（协议落点） ----

    private suspend fun handle(event: MossEvent, translate: Boolean) {
        when (event) {
            is MossEvent.SessionAttached -> {
                if (!translate) {
                    val persona = pendingPersonaUpdate
                    if (persona != null) {
                        // 自定义人设：先下发，等 session.updated 再开麦
                        client.updateInstructions(persona)
                    } else if (bareStartPending) {
                        // 裸 attach：session 已建立，现在才开麦
                        bareStartPending = false
                        startAssistantAudio()
                    }
                }
                // 同传端点不发 session.update（不在其接受列表内）
            }

            is MossEvent.SessionUpdated -> {
                if (event.error.isNotBlank()) {
                    _ui.update { it.copy(phase = Phase.Failed("会话配置被拒绝：${event.error}")) }
                    return
                }
                if (!translate && pendingPersonaUpdate == null) {
                    // 裸 attach 路径已在 startAssistant 开麦，这里无需重复
                    return
                }
                pendingPersonaUpdate = null
                client.micState(true)
                // 翻译无播放，用普通 MIC 音源（部分 ROM 上 VOICE_COMMUNICATION 会静音）
                mic.start(
                    if (translate) android.media.MediaRecorder.AudioSource.MIC
                    else android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                )
                if (!translate && speaker == null) {
                    speaker = SpeakerPlayback(client).also { it.start(scope) }
                }
                // 翻译联调开关开着：本会话内开始试听译文语音
                if (translate && _translateMonitor.value && speaker == null) {
                    speaker = SpeakerPlayback(client).also { it.start(scope) }
                }
                _ui.update { it.copy(phase = Phase.Listening) }
            }

            is MossEvent.InputTranscriptUpdated ->
                if (translate) {
                    turnSourceLive[event.turn ?: 0] = event.text
                    rebuildTranslateState()
                } else {
                    _ui.update { it.copy(inputText = event.text) }
                }

            is MossEvent.InputTranscriptDone ->
                if (translate) {
                    val turn = event.turn ?: 0
                    turnSourceFinal[turn] = event.text
                    turnSourceLive.remove(turn)
                    schedulePromotion(turn)
                    rebuildTranslateState()
                } else {
                    insertMessage(Role.USER, source = null, display = event.text)
                    _ui.update { it.copy(inputText = "") }
                }

            is MossEvent.InputTranscriptRevised ->
                if (translate) {
                    val turn = event.turn ?: 0
                    turnSourceFinal[turn] = event.text
                    turnSourceLive.remove(turn)
                    rebuildTranslateState()
                } else Unit

            is MossEvent.OutputTranscriptUpdated ->
                if (translate) {
                    val turn = event.turnId.toIntOrNull() ?: 0
                    promoteJobs[turn]?.cancel()
                    turnTransLive[turn] = event.text
                    rebuildTranslateState()
                } else {
                    liveOutputRunId = event.runId
                    liveOutputText = event.text
                    _ui.update { it.copy(outputText = event.text) }
                }

            is MossEvent.OutputTranscriptDone ->
                if (translate) {
                    val turn = event.turnId.toIntOrNull() ?: 0
                    promoteJobs[turn]?.cancel()
                    turnTransFinal[turn] = event.text
                    turnTransLive.remove(turn)
                    finalizeTranslateMessage(turn)
                    rebuildTranslateState()
                } else {
                    insertMessage(Role.ASSISTANT, source = null, display = event.text)
                    liveOutputRunId = null
                    liveOutputText = ""
                    _ui.update { it.copy(outputText = "") }
                }

            // 翻译模式默认丢弃译文音频但须瞬时补回执（服务端靠回执推进）；
            // 「试听译文」开启时交给 SpeakerPlayback 播放并回执
            is AudioChunk ->
                if (translate) {
                    if (speaker == null) {
                        client.chunkCompleted(event.runId, event.turnId.takeIf { it.isNotBlank() }, event.chunkId)
                    }
                } else Unit // 助理模式由 SpeakerPlayback 消费并回执

            is MossEvent.ResponseAudioDone ->
                if (translate) {
                    if (speaker == null) {
                        audioDoneRuns.add(event.runId)
                        ackPlayback(event.runId)
                    }
                } else Unit // SpeakerPlayback 处理

            is MossEvent.ResponseEnded ->
                if (translate) {
                    if (speaker == null) ackPlayback(event.runId)
                } else {
                    flushPartialAssistantReply(event.runId)
                    if (_ui.value.phase == Phase.Speaking) {
                        _ui.update { it.copy(phase = Phase.Listening) }
                    }
                }

            is MossEvent.ResponseAudioStarted ->
                if (!translate) _ui.update { it.copy(phase = Phase.Speaking) }

            is MossEvent.ResponseAudioStop ->
                if (!translate) {
                    flushPartialAssistantReply(event.runId)
                    _ui.update { it.copy(phase = Phase.Listening) }
                }

            is MossEvent.ResponseInterrupted ->
                if (!translate) {
                    flushPartialAssistantReply(event.runId)
                    _ui.update { it.copy(phase = Phase.Listening) }
                }

            is MossEvent.Error -> {
                // 1013 限流等场景：客户端会自动退避重连，这里只提示
                _ui.update { it.copy(errorMessage = "${event.code ?: ""} ${event.message}".trim()) }
            }

            is MossEvent.AudioChunkMeta, is MossEvent.Pong,
            is MossEvent.Unknown, is MossEvent.SessionClosing,
            is MossEvent.VoiceChanged, is MossEvent.InputSpeechStarted,
            is MossEvent.InputSpeechStopped -> Unit
        }
    }

    // ---- 翻译状态 ----

    /**
     * 3 秒没等到译文时的兜底：仅当原文是中文（含 CJK）才提升为大字显示——
     * 中文输入的转写就是预期的"译文"；外文原文绝不提升（用户不想看到英文大字）。
     */
    private fun schedulePromotion(turn: Int) {
        promoteJobs[turn]?.cancel()
        promoteJobs[turn] = scope.launch {
            delay(PROMOTE_AFTER_MS)
            if (turnTransFinal[turn] == null && turnTransLive[turn] == null) {
                val src = turnSourceFinal[turn] ?: turnSourceLive[turn] ?: ""
                if (src.any { it.code in 0x4E00..0x9FFF }) {
                    turnPromoted.add(turn)
                    rebuildTranslateState()
                }
            }
        }
    }

    private fun ackPlayback(runId: String) {
        if (runId !in audioDoneRuns || runId in playbackAckedRuns) return
        playbackAckedRuns.add(runId)
        client.playbackCompleted(runId, null)
    }

    private fun rebuildTranslateState() {
        val turns = (turnSourceFinal.keys + turnSourceLive.keys + turnTransFinal.keys + turnTransLive.keys)
            .toSortedSet()
        val list = turns.map { t ->
            val src = turnSourceFinal[t] ?: turnSourceLive[t] ?: ""
            val trans = turnTransFinal[t] ?: turnTransLive[t]
            TranslatePair(
                source = src,
                translated = trans ?: if (t in turnPromoted) src else "",
            )
        }
        _ui.update { it.copy(pairs = list) }
    }

    private fun clearTranslateState() {
        promoteJobs.values.forEach { it.cancel() }
        promoteJobs.clear()
        turnSourceFinal.clear()
        turnSourceLive.clear()
        turnTransFinal.clear()
        turnTransLive.clear()
        turnPromoted.clear()
        audioDoneRuns.clear()
        playbackAckedRuns.clear()
    }

    /** 一句译文定稿 → 落库（原文小字 + 译文大字成对）。 */
    private suspend fun finalizeTranslateMessage(turn: Int) {
        val src = turnSourceFinal[turn] ?: turnSourceLive[turn] ?: ""
        val trans = turnTransFinal[turn] ?: return
        if (trans.isBlank() && src.isBlank()) return
        insertMessage(Role.USER, source = src.ifBlank { null }, display = trans)
    }

    private suspend fun insertMessage(role: Role, source: String?, display: String) {
        val id = dbSessionId ?: return
        if (display.isBlank() && source.isNullOrBlank()) return
        db.messageDao().insert(
            MessageEntity(
                id = UUID.randomUUID().toString(),
                sessionId = id,
                role = role,
                sourceText = source,
                displayText = display,
                timestamp = System.currentTimeMillis(),
            ),
        )
        messageCount++
    }

    /** 助理回复被打断/异常结束时，把已生成的部分转写也落库，避免回复记录缺失。 */
    private suspend fun flushPartialAssistantReply(runId: String) {
        if (runId.isNotBlank() && liveOutputRunId == runId && liveOutputText.isNotBlank()) {
            insertMessage(Role.ASSISTANT, source = null, display = liveOutputText)
            liveOutputRunId = null
            liveOutputText = ""
            _ui.update { it.copy(outputText = "") }
        }
    }

    // ---- 挂断 / 停止 ----

    suspend fun hangUp() {
        client.micState(false)
        mic.stop()
        speaker?.stopAll()
        speaker = null
        client.close()
        eventsJob?.cancel()
        eventsJob = null
        // 会话结束前把未定稿的助理回复落库
        if (liveOutputText.isNotBlank()) {
            insertMessage(Role.ASSISTANT, source = null, display = liveOutputText)
        }
        liveOutputRunId = null
        liveOutputText = ""
        val id = dbSessionId
        if (id != null) {
            if (messageCount > 0) {
                val mode = _ui.value.mode ?: SessionType.ASSISTANT
                db.sessionDao().finish(
                    id,
                    System.currentTimeMillis(),
                    title(mode, _ui.value.startedAt ?: System.currentTimeMillis()),
                )
            } else {
                db.sessionDao().deleteById(id) // 没说过一句话的会话不留垃圾记录
            }
        }
        dbSessionId = null
        clearTranslateState()
        _ui.value = SessionUiState()
    }

    private fun title(mode: SessionType, startedAt: Long): String =
        SimpleDateFormat("M月d日 HH:mm", Locale.SIMPLIFIED_CHINESE).format(startedAt) +
            if (mode == SessionType.ASSISTANT) " 助理" else " 翻译"
}
