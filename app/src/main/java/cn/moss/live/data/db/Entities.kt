package cn.moss.live.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

enum class SessionType { ASSISTANT, TRANSLATE }

enum class Role { USER, ASSISTANT }

/** 一条会话：助理拨通→挂断、翻译开始→停止，各算一条，按时间自动命名。 */
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: String,
    val type: SessionType,
    val title: String, // 如「11月12日 14:03 助理」
    val startedAt: Long,
    val endedAt: Long?,
)

/** 一条消息。翻译会话的消息带 sourceText（对方原文），成对出现在详情页。 */
@Entity(
    tableName = "messages",
    indices = [Index("sessionId")],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "sessionId") val sessionId: String,
    val role: Role,
    /** 翻译会话：对方原文（原文小字）；助理会话：null。 */
    val sourceText: String?,
    /** 展示文本：助理=对话内容；翻译=中文译文（或中文直接转写）。 */
    val displayText: String,
    val timestamp: Long,
)

/** 全文搜索影子表：覆盖助理对话与翻译的原文+译文两种内容。 */
@Fts4(contentEntity = MessageEntity::class)
@Entity(tableName = "messages_fts")
data class MessageFtsEntity(
    val displayText: String,
    val sourceText: String?,
)
