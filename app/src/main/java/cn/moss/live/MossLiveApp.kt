package cn.moss.live

import android.app.Application
import android.content.Context
import cn.moss.live.audio.MicCapture
import cn.moss.live.data.db.AppDatabase
import cn.moss.live.data.prefs.AppSettings
import cn.moss.live.data.prefs.SettingsDataStore
import cn.moss.live.protocol.MossLiveClient
import cn.moss.live.session.VoiceSessionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** 手工依赖容器：轻量应用不值得引入 DI 框架。 */
class AppContainer(context: Context) {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val db: AppDatabase = AppDatabase.get(context)
    val settings = SettingsDataStore(context)

    /** API Key 缓存：OkHttp 线程不能阻塞读 DataStore，这里后台同步一份。 */
    @Volatile private var cachedApiKey = ""

    val client = MossLiveClient(apiKeyProvider = { cachedApiKey })

    val mic = MicCapture { client.sendAudio(it) }

    val repo = VoiceSessionRepository(client, mic, db, settings, appScope)

    init {
        appScope.launch {
            settings.settings.collect { s: AppSettings -> cachedApiKey = s.apiKey }
        }
    }
}

class MossLiveApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
