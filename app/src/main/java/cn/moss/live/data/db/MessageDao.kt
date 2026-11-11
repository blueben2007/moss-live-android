package cn.moss.live.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Insert
    suspend fun insert(message: MessageEntity)

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun observeOfSession(sessionId: String): Flow<List<MessageEntity>>

    @Query("DELETE FROM messages WHERE sessionId = :sessionId")
    suspend fun deleteOfSession(sessionId: String)

    @Query("DELETE FROM messages")
    suspend fun deleteAll()

    /**
     * 全文搜索：命中会话 id 列表（去重）。查询词做前缀匹配的宽松处理，
     * 多词按 OR 合并，避免用户输入破坏 FTS 语法。
     */
    @Query(
        """
        SELECT DISTINCT m.sessionId FROM messages m
        WHERE m.rowid IN (SELECT docid FROM messages_fts WHERE messages_fts MATCH :ftsQuery)
        """,
    )
    suspend fun searchSessionIdsRaw(ftsQuery: String): List<String>

    @Query(
        """
        SELECT * FROM messages
        WHERE rowid IN (SELECT docid FROM messages_fts WHERE messages_fts MATCH :ftsQuery)
        ORDER BY timestamp DESC
        LIMIT 20
        """,
    )
    suspend fun searchMessagesRaw(ftsQuery: String): List<MessageEntity>

    @Query("SELECT COUNT(*) FROM messages")
    suspend fun count(): Int
}
