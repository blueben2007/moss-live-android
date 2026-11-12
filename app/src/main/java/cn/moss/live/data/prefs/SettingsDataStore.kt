package cn.moss.live.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/** 已知音色（官方 List Voices 示例），音色 id 后续可加。 */
object Voices {
    const val NUANNUAN = "6c3152b1-1d4f-4e49-ade3-09f447712fec" // 暖暖（女）
    const val MUMU = "6cc93520-3207-4f7a-bca9-2d560b9c306b" // 木木（男）
}

data class AppSettings(
    val apiKey: String,
    val voiceId: String,
    val voiceName: String,
    val memoryEnabled: Boolean,
    val lockCaption: Boolean,
    val persona: String,
    /** 本机稳定用户标识（服务端长期记忆 memory_scope=user 必需）。 */
    val userId: String,
    /** 同传源语言（"" = 自动检测；服务端自动检测异常时显式指定）。 */
    val translateSource: String,
    /** 翻译页是否显示对方原文（默认只显示中文译文）。 */
    val translateShowSource: Boolean,
)

/** 仅存设备本地的配置；API Key 永不出现在仓库或日志。 */
class SettingsDataStore(private val context: Context) {

    private object Keys {
        val API_KEY = stringPreferencesKey("api_key")
        val VOICE_ID = stringPreferencesKey("voice_id")
        val MEMORY_ENABLED = booleanPreferencesKey("memory_enabled")
        val LOCK_CAPTION = booleanPreferencesKey("lock_caption")
        val PERSONA = stringPreferencesKey("persona")
        val USER_ID = stringPreferencesKey("user_id")
        val TRANSLATE_SOURCE = stringPreferencesKey("translate_source")
        val TRANSLATE_SHOW_SOURCE = booleanPreferencesKey("translate_show_source")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        val voiceId = p[Keys.VOICE_ID] ?: Voices.NUANNUAN
        AppSettings(
            apiKey = p[Keys.API_KEY] ?: "",
            voiceId = voiceId,
            voiceName = if (voiceId == Voices.MUMU) "木木" else "暖暖",
            memoryEnabled = p[Keys.MEMORY_ENABLED] ?: true,
            lockCaption = p[Keys.LOCK_CAPTION] ?: false,
            persona = p[Keys.PERSONA] ?: "",
            userId = p[Keys.USER_ID] ?: "",
            translateSource = p[Keys.TRANSLATE_SOURCE] ?: "",
            translateShowSource = p[Keys.TRANSLATE_SHOW_SOURCE] ?: false,
        )
    }

    /**
     * 稳定的本机用户标识：开启跨会话长期记忆（memory_scope=user）时，
     * 服务端要求连接 URL 带 user 参数。首次调用生成并持久化，之后不变。
     */
    suspend fun ensureUserId(): String {
        val current = context.dataStore.data.first()[Keys.USER_ID]
        if (!current.isNullOrBlank()) return current
        val fresh = java.util.UUID.randomUUID().toString()
        context.dataStore.edit { it[Keys.USER_ID] = fresh }
        return fresh
    }

    suspend fun snapshot(): AppSettings = settings.first()

    suspend fun setApiKey(value: String) = context.dataStore.edit { it[Keys.API_KEY] = value.trim() }

    suspend fun setVoiceId(value: String) = context.dataStore.edit { it[Keys.VOICE_ID] = value }

    suspend fun setMemoryEnabled(value: Boolean) = context.dataStore.edit { it[Keys.MEMORY_ENABLED] = value }

    suspend fun setLockCaption(value: Boolean) = context.dataStore.edit { it[Keys.LOCK_CAPTION] = value }

    suspend fun setPersona(value: String) = context.dataStore.edit { it[Keys.PERSONA] = value }

    suspend fun setTranslateSource(value: String) = context.dataStore.edit { it[Keys.TRANSLATE_SOURCE] = value }

    suspend fun setTranslateShowSource(value: Boolean) = context.dataStore.edit { it[Keys.TRANSLATE_SHOW_SOURCE] = value }
}
