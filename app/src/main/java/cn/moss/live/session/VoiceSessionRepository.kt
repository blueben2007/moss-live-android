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
 * 两种模式：
 * - 助理：全双工对话。麦克风常开、扬声器播放、可打断。
 * - 翻译：同传字幕。只听不说——SpeakerPlayback 不实例化（无任何朗读路径）。
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

        /** 翻译模式同传指令：任意语种→中文；中文输入直接转写，绝不朗读。 */
        const val TRANSLATE_INSTRUCTIONS =
            "你是一个实时同声传译机。把用户说的任何语言实时翻译成简体中文。规则：" +
                "1) 只输出译文本身，不要任何解释、前言或后语；" +
                "2) 如果用户说的已经是中文，就原样转写用户的话，不要翻译；" +
                "3) 语气与标点保持口语自然；" +
                "4) 每次只译一句完整的话，不要合并多句。"
    }

    private val _ui = MutableStateFlow(SessionUiState())
    val ui: StateFlow<SessionUiState> = _ui

    /** 麦克风音量电平，驱动声纹动画。 */
    val micLevel get() = mic.level

    private var eventsJob: Job? = null
    @Volatile private var speaker: SpeakerPlayback? = null
    @Volatile private var dbSessionId: String? = null
    @Volatile private var messageCount = 0
    @Volatile private var pendingSource: String? = null

    // ---- 开始 / 结束 ----

    suspend fun startAssistant() {
        val settings = settingsStore.snapshot()
        if (settings.apiKey.isBlank()) {
            _ui.value = SessionUiState(phase = Phase.Failed("请先在设置里配置 API Key"))
            return
        }
        beginSession(SessionType.ASSISTANT)
        collectEvents(translate = false, settings = settings)
        connectWithMemory(settings)
    }

    suspend fun startTranslate() {
        val settings = settingsStore.snapshot()
        if (settings.apiKey.isBlank()) {
            _ui.value = SessionUiState(phase = Phase.Failed("请先在设置里配置 API Key"))
            return
        }
        beginSession(SessionType.TRANSLATE)
        collectEvents(translate = true, settings = settings)
        connectWithMemory(settings)
    }

    /** memory_scope=user 必须带 user 标识（服务端校验），由本机稳定 UUID 供给。 */
    private suspend fun connectWithMemory(settings: AppSettings) {
        if (settings.memoryEnabled) {
            client.connect(null, "user", settingsStore.ensureUserId())
        } else {
            client.connect(null, "session", null)
        }
    }

    private suspend fun beginSession(mode: SessionType) {
        val id = UUID.randomUUID().toString()
        dbSessionId = id
        messageCount = 0
        pendingSource = null
        db.sessionDao().insert(
            SessionEntity(
                id = id,
                type = mode,
                title = title(mode, System.currentTimeMillis()),
                startedAt = System.currentTimeMillis(),
                endedAt = null,
            ),
        )
        _ui.value = SessionUiState(
            mode = mode,
            phase = Phase.Connecting,
            dbSessionId = id,
            startedAt = System.currentTimeMillis(),
        )
    }

    private fun collectEvents(translate: Boolean, settings: AppSettings) {
        eventsJob?.cancel()
        eventsJob = scope.launch {
            client.events.collect { event -> handle(event, translate, settings) }
        }
    }

    // ---- 事件处理（协议落点） ----

    private suspend fun handle(event: MossEvent, translate: Boolean, settings: AppSettings) {
        when (event) {
            is MossEvent.SessionAttached -> {
                // attach 后先下发指令，等 session.updated 确认成功再开麦（协议时序要求）
                val instructions =
                    if (translate) TRANSLATE_INSTRUCTIONS
                    else settings.persona.ifBlank { DEFAULT_PERSONA }
                client.updateInstructions(instructions)
            }

            is MossEvent.SessionUpdated -> {
                if (event.error.isNotBlank()) {
                    _ui.update { it.copy(phase = Phase.Failed("会话配置被拒绝：${event.error}")) }
                    return
                }
                client.micState(true)
                mic.start()
                if (!translate && speaker == null) {
                    speaker = SpeakerPlayback(client).also { it.start(scope) }
                }
                _ui.update { it.copy(phase = Phase.Listening) }
            }

            is MossEvent.InputTranscriptUpdated ->
                _ui.update { it.copy(inputText = event.text) }

            is MossEvent.InputTranscriptDone -> {
                if (translate) {
                    pendingSource = event.text
                    _ui.update { st ->
                        st.copy(
                            inputText = "",
                            currentPair = TranslatePair(event.text, st.currentPair?.translated ?: ""),
                        )
                    }
                } else {
                    insertMessage(Role.USER, source = null, display = event.text)
                    _ui.update { it.copy(inputText = "") }
                }
            }

            is MossEvent.InputTranscriptRevised -> {
                // 修订视作当轮转写的最新版本
                if (translate) {
                    pendingSource = event.text
                    _ui.update { st ->
                        st.copy(currentPair = TranslatePair(event.text, st.currentPair?.translated ?: ""))
                    }
                }
            }

            is MossEvent.OutputTranscriptUpdated -> {
                if (translate) {
                    _ui.update { st ->
                        st.copy(
                            currentPair = TranslatePair(
                                st.currentPair?.source ?: pendingSource ?: "",
                                event.text,
                            ),
                        )
                    }
                } else {
                    _ui.update { it.copy(outputText = event.text) }
                }
            }

            is MossEvent.OutputTranscriptDone -> {
                if (translate) {
                    val pair = TranslatePair(pendingSource ?: "", event.text)
                    if (pair.translated.isNotBlank()) {
                        insertMessage(Role.USER, source = pair.source.ifBlank { null }, display = pair.translated)
                    }
                    pendingSource = null
                    _ui.update { st ->
                        st.copy(pairs = st.pairs + pair, currentPair = null, outputText = "")
                    }
                } else {
                    insertMessage(Role.ASSISTANT, source = null, display = event.text)
                    _ui.update { it.copy(outputText = "") }
                }
            }

            is MossEvent.ResponseAudioStarted ->
                if (!translate) _ui.update { it.copy(phase = Phase.Speaking) }

            is MossEvent.ResponseAudioStop, is MossEvent.ResponseInterrupted ->
                if (!translate) _ui.update { it.copy(phase = Phase.Listening) }

            is MossEvent.ResponseEnded ->
                if (!translate && _ui.value.phase == Phase.Speaking) {
                    _ui.update { it.copy(phase = Phase.Listening) }
                }

            is MossEvent.Error -> {
                // 1013 限流等场景：客户端会自动退避重连，这里只提示
                _ui.update { it.copy(errorMessage = "${event.code ?: ""} ${event.message}".trim()) }
            }

            is AudioChunk, is MossEvent.AudioChunkMeta,
            is MossEvent.ResponseAudioDone, is MossEvent.Pong,
            is MossEvent.Unknown, is MossEvent.SessionClosing,
            is MossEvent.VoiceChanged, is MossEvent.InputSpeechStarted,
            is MossEvent.InputSpeechStopped -> Unit
        }
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

    // ---- 挂断 / 停止 ----

    suspend fun hangUp() {
        client.micState(false)
        mic.stop()
        speaker?.stopAll()
        speaker = null
        client.close()
        eventsJob?.cancel()
        eventsJob = null
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
        pendingSource = null
        _ui.value = SessionUiState()
    }

    private fun title(mode: SessionType, startedAt: Long): String =
        SimpleDateFormat("M月d日 HH:mm", Locale.SIMPLIFIED_CHINESE).format(startedAt) +
            if (mode == SessionType.ASSISTANT) " 助理" else " 翻译"
}
