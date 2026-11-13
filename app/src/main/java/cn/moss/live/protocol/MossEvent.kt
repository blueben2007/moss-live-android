package cn.moss.live.protocol

import org.json.JSONObject

/**
 * 服务端下行事件模型。字段以 MOSS-Live 官方 API Reference 为准；
 * 真机联调发现偏差只改 [parse] 与字段名，不动消费方。
 */
sealed interface MossEvent {

    /** session_attached：会话已建立，保存 sessionId 供重连与记忆续用；reconnected=true 表示这是断线重连。 */
    data class SessionAttached(val sessionId: String, val reconnected: Boolean = false) : MossEvent

    /** session.updated：session.update 的回执，error 为空串表示成功。 */
    data class SessionUpdated(val error: String, val tools: List<String>) : MossEvent

    data class VoiceChanged(val name: String) : MossEvent

    data object InputSpeechStarted : MossEvent
    data object InputSpeechStopped : MossEvent

    /**
     * 用户语音转写（实时更新）。协议要点：text 是当前完整文本，
     * 消费方必须整体替换而非追加。
     */
    data class InputTranscriptUpdated(val text: String, val turn: Int?) : MossEvent

    /** 本轮用户语音转写定稿（该轮落库的时机）。 */
    data class InputTranscriptDone(val text: String, val turn: Int?) : MossEvent

    data class InputTranscriptRevised(val text: String, val previous: String, val turn: Int?) : MossEvent

    /** 模型回复转写（实时），runId/turnId 关联音频批次。 */
    data class OutputTranscriptUpdated(val text: String, val runId: String, val turnId: String) : MossEvent

    /** 本条回复转写定稿。 */
    data class OutputTranscriptDone(val text: String, val runId: String, val turnId: String) : MossEvent

    /** 一条回复的音频开始到达（runId 批次）。 */
    data class ResponseAudioStarted(val runId: String, val turnId: String) : MossEvent

    /** 下一个二进制音频帧的归属信息（48kHz PCM 时无 durationMs）。 */
    data class AudioChunkMeta(val runId: String, val turnId: String, val chunkId: String, val durationMs: Long?) : MossEvent

    /** 某一块音频全部下发完毕。 */
    data class ResponseAudioDone(val runId: String, val turnId: String) : MossEvent

    /** 回复被打断：立即停播该 run 并清空播放队列。 */
    data class ResponseAudioStop(val runId: String, val turnId: String, val reason: String?) : MossEvent

    /** 一条回复结束，status: completed / aborted / failed。 */
    data class ResponseEnded(val runId: String, val turnId: String, val status: String) : MossEvent

    data class ResponseInterrupted(val runId: String, val turnId: String, val text: String?) : MossEvent

    /** 服务端准备关闭会话；reconnect=true 时可用原 sessionId 重连恢复。 */
    data class SessionClosing(val code: Int?, val reason: String?, val reconnect: Boolean) : MossEvent

    data class Pong(val clientTimestamp: Long?, val serverRecvTimestamp: Long?) : MossEvent

    data class Error(val code: String?, val message: String) : MossEvent

    /** 未识别的事件：留日志不处理，协议演进时兜底。 */
    data class Unknown(val action: String) : MossEvent

    companion object {
        fun parse(action: String, root: JSONObject): MossEvent {
            val data = root.optJSONObject("data") ?: JSONObject()
            return when (action) {
                "session_attached" -> SessionAttached(data.optString("session_id"))
                "session.updated" -> SessionUpdated(
                    error = data.optString("error"),
                    tools = data.optJSONArray("tools")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
                )
                "voice_changed" -> VoiceChanged(data.optString("name"))
                "input.speech.started" -> InputSpeechStarted
                "input.speech.stopped" -> InputSpeechStopped
                "input.transcript.updated" -> InputTranscriptUpdated(data.optString("text"), data.optInt("turn").takeIf { data.has("turn") })
                "input.transcript.done" -> InputTranscriptDone(data.optString("text"), data.optInt("turn").takeIf { data.has("turn") })
                "input.transcript.revised" -> InputTranscriptRevised(data.optString("text"), data.optString("previous"), data.optInt("turn").takeIf { data.has("turn") })
                "output.transcript.updated" -> OutputTranscriptUpdated(
                    data.optString("text"),
                    data.optString("run_id"),
                    if (data.has("turn_id")) data.optString("turn_id") else data.optString("source_turn"),
                )
                "output.transcript.done" -> OutputTranscriptDone(
                    data.optString("text"),
                    data.optString("run_id"),
                    if (data.has("turn_id")) data.optString("turn_id") else data.optString("source_turn"),
                )
                "response.audio.started" -> ResponseAudioStarted(data.optString("run_id"), data.optString("turn_id"))
                "response.audio.context" -> AudioChunkMeta(
                    runId = data.optString("run_id"),
                    turnId = data.optString("turn_id"),
                    chunkId = data.optString("chunk_id"),
                    durationMs = data.optLong("duration_ms").takeIf { data.has("duration_ms") },
                )
                "response.audio.done" -> ResponseAudioDone(data.optString("run_id"), data.optString("turn_id"))
                "response.audio.stop" -> ResponseAudioStop(data.optString("run_id"), data.optString("turn_id"), data.optString("reason").takeIf { data.has("reason") })
                "response.ended" -> ResponseEnded(data.optString("run_id"), data.optString("turn_id"), data.optString("status"))
                "response.interrupted" -> ResponseInterrupted(data.optString("run_id"), data.optString("turn_id"), data.optString("text").takeIf { data.has("text") })
                "session.closing" -> SessionClosing(
                    code = data.optInt("code").takeIf { data.has("code") },
                    reason = data.optString("reason").takeIf { data.has("reason") },
                    reconnect = data.optBoolean("reconnect"),
                )
                "pong" -> Pong(
                    clientTimestamp = data.optLong("client_timestamp").takeIf { data.has("client_timestamp") },
                    serverRecvTimestamp = data.optLong("server_recv_timestamp").takeIf { data.has("server_recv_timestamp") },
                )
                "error" -> {
                    val err = data.optJSONObject("error") ?: data
                    Error(err.optString("code").takeIf { err.has("code") }, err.optString("message"))
                }
                else -> Unknown(action)
            }
        }
    }
}
