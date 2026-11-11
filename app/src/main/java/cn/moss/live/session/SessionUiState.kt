package cn.moss.live.session

import cn.moss.live.data.db.SessionType

sealed interface Phase {
    data object Idle : Phase
    data object Connecting : Phase
    data object Listening : Phase
    data object Speaking : Phase
    data class Failed(val message: String) : Phase
}

/** 翻译会话的一对字幕：对方原文 → 中文译文。 */
data class TranslatePair(val source: String, val translated: String)

data class SessionUiState(
    val mode: SessionType? = null,
    val phase: Phase = Phase.Idle,
    /** 本会话落库 id（非 MOSS session_id）。 */
    val dbSessionId: String? = null,
    val startedAt: Long? = null,
    /** 用户实时转写（协议要求整体替换）。 */
    val inputText: String = "",
    /** 模型实时转写（整体替换）。 */
    val outputText: String = "",
    /** 翻译模式：进行中的一对（原文已定稿、译文流式）。 */
    val currentPair: TranslatePair? = null,
    /** 翻译模式：本会话已定稿的字幕对。 */
    val pairs: List<TranslatePair> = emptyList(),
    val errorMessage: String? = null,
) {
    val isActive: Boolean get() = mode != null && phase != Phase.Idle && phase !is Phase.Failed
}
