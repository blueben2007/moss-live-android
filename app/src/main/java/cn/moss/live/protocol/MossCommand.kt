package cn.moss.live.protocol

import org.json.JSONArray
import org.json.JSONObject

/**
 * 客户端上行命令构造。字段位置（顶层 or data 内）以官方文档示例为准；
 * 真机联调发现偏差只改这里，不动调用方。
 */
object MossCommand {

    /** 新会话传 sessionId=null；memoryScope 传 "user" 开启跨会话长期记忆。 */
    fun attachSession(sessionId: String?, memoryScope: String?): String {
        val o = JSONObject()
        o.put("action", "attach_session")
        o.put("session_id", sessionId ?: JSONObject.NULL)
        if (memoryScope != null) o.put("memory_scope", memoryScope)
        return o.toString()
    }

    fun micState(enabled: Boolean): String {
        val o = JSONObject()
        o.put("action", "mic_state")
        o.put("enabled", enabled)
        return o.toString()
    }

    /** 同传端点要求：开启 tagged-v1 二进制协议后，音频帧带 0x00 前缀。 */
    fun mediaModeTagged(): String {
        val o = JSONObject()
        o.put("action", "media.mode")
        o.put("binary_protocol", "tagged-v1")
        return o.toString()
    }

    /**
     * 整体替换会话指令（助理人设或同传指令），≤16000 字符。
     * 官方示例：session 对象在顶层，不在 data 内（与全部命令字段一致）。
     */
    fun updateInstructions(instructions: String): String {
        val o = JSONObject()
        o.put("action", "session.update")
        o.put("session", JSONObject().put("instructions", instructions))
        return o.toString()
    }

    fun changeVoice(voiceId: String): String {
        val o = JSONObject()
        o.put("action", "change_voice")
        o.put("voice_name", voiceId)
        return o.toString()
    }

    fun ping(): String = JSONObject().put("action", "ping").toString()

    /** 每块音频播完必发，服务端靠它推进节奏；turn_id 缺省时省略（同传端点的回执无 turn）。 */
    fun playbackChunkCompleted(runId: String, turnId: String?, chunkId: String): String {
        val o = JSONObject()
        o.put("action", "playback.chunk.completed")
        o.put("run_id", runId)
        if (!turnId.isNullOrBlank()) o.put("turn_id", turnId)
        o.put("chunk_id", chunkId)
        return o.toString()
    }

    /** 整条回复播完必发。 */
    fun playbackCompleted(runId: String, turnId: String?): String {
        val o = JSONObject()
        o.put("action", "playback.completed")
        o.put("run_id", runId)
        if (!turnId.isNullOrBlank()) o.put("turn_id", turnId)
        return o.toString()
    }

    /** 被打断时回传：已播块确认已在播完时逐块发过，这里带当前块已播毫秒数。 */
    fun playbackStopped(runId: String, turnId: String?, playedAudioMs: Long): String {
        val o = JSONObject()
        o.put("action", "playback.stopped")
        o.put("run_id", runId)
        if (!turnId.isNullOrBlank()) o.put("turn_id", turnId)
        o.put("played_audio_ms", playedAudioMs)
        return o.toString()
    }

    /** 仅供扩展：本版本未使用工具调用。 */
    fun unusedToolsPlaceholder(): String = JSONArray().toString()
}
