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
)

/** 仅存设备本地的配置；API Key 永不出现在仓库或日志。 */
class SettingsDataStore(private val context: Context) {

    private object Keys {
        val API_KEY = stringPreferencesKey("api_key")
        val VOICE_ID = stringPreferencesKey("voice_id")
        val MEMORY_ENABLED = booleanPreferencesKey("memory_enabled")
        val LOCK_CAPTION = booleanPreferencesKey("lock_caption")
        val PERSONA = stringPreferencesKey("persona")
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
        )
    }

    suspend fun snapshot(): AppSettings = settings.first()

    suspend fun setApiKey(value: String) = context.dataStore.edit { it[Keys.API_KEY] = value.trim() }

    suspend fun setVoiceId(value: String) = context.dataStore.edit { it[Keys.VOICE_ID] = value }

    suspend fun setMemoryEnabled(value: Boolean) = context.dataStore.edit { it[Keys.MEMORY_ENABLED] = value }

    suspend fun setLockCaption(value: Boolean) = context.dataStore.edit { it[Keys.LOCK_CAPTION] = value }

    suspend fun setPersona(value: String) = context.dataStore.edit { it[Keys.PERSONA] = value }
}
