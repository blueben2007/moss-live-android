package cn.moss.live.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import cn.moss.live.AppContainer
import cn.moss.live.MossLiveApp
import cn.moss.live.R
import cn.moss.live.data.db.SessionType
import cn.moss.live.session.Phase
import cn.moss.live.session.SessionUiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 语音会话前台服务（foregroundServiceType=microphone）：
 * 承载 WSS 连接、采集与播放的存活；锁屏/熄屏后继续监听。
 *
 * 通知形态（用户拍板）：默认状态型「摩斯语境 · 助理 · 已连接 mm:ss」；
 * 设置开启「锁屏显示实时字幕」后刷新为最新译文/最新一句（节流 ~700ms）。
 */
class VoiceForegroundService : Service() {

    companion object {
        const val ACTION_START_ASSISTANT = "cn.moss.live.action.START_ASSISTANT"
        const val ACTION_START_TRANSLATE = "cn.moss.live.action.START_TRANSLATE"
        const val ACTION_STOP = "cn.moss.live.action.STOP"
        private const val CHANNEL_ID = "voice_session"
        private const val NOTIFICATION_ID = 1
        private const val THROTTLE_MS = 700L
    }

    private val container: AppContainer by lazy { (application as MossLiveApp).container }
    private var wakeLock: PowerManager.WakeLock? = null
    private var job: Job? = null
    private var lastNotifyText = ""
    private var lastNotifyAt = 0L

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_ASSISTANT -> begin(SessionType.ASSISTANT)
            ACTION_START_TRANSLATE -> begin(SessionType.TRANSLATE)
            ACTION_STOP -> stopSession()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun begin(mode: SessionType) {
        createChannelAndForeground(mode)
        acquireWakeLock()
        job?.cancel()
        job = container.appScope.launch {
            if (mode == SessionType.ASSISTANT) container.repo.startAssistant()
            else container.repo.startTranslate()
            container.repo.ui.collect { updateNotification(it) }
        }
    }

    private fun stopSession() {
        job?.cancel()
        job = null
        container.appScope.launch {
            container.repo.hangUp()
            stopSelf()
        }
    }

    private fun createChannelAndForeground(mode: SessionType) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "语音会话", NotificationManager.IMPORTANCE_LOW),
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(if (mode == SessionType.ASSISTANT) "助理 · 连接中" else "翻译 · 连接中")
            .setOngoing(true)
        val notification: Notification = builder.build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private suspend fun updateNotification(state: SessionUiState) {
        val now = System.currentTimeMillis()
        if (now - lastNotifyAt < THROTTLE_MS) return
        val text = buildNotifyText(state)
        if (text.isEmpty() || text == lastNotifyText) return
        lastNotifyText = text
        lastNotifyAt = now
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(
            NOTIFICATION_ID,
            Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setOngoing(true)
                .build(),
        )
    }

    private suspend fun buildNotifyText(state: SessionUiState): String {
        val mode = when (state.mode) {
            SessionType.ASSISTANT -> "助理"
            SessionType.TRANSLATE -> "翻译"
            null -> return ""
        }
        // 锁屏字幕开关关闭：只显状态，不显内容
        if (!container.settings.snapshot().lockCaption) {
            val elapsed = state.startedAt?.let { (System.currentTimeMillis() - it) / 1000 } ?: 0L
            val mmss = "%02d:%02d".format(elapsed / 60, elapsed % 60)
            val status = when (state.phase) {
                Phase.Connecting -> "连接中"
                Phase.Listening -> "聆听中"
                Phase.Speaking -> "说话中"
                is Phase.Failed -> "出错"
                Phase.Idle -> "已结束"
            }
            return "$mode · $status · $mmss"
        }
        // 开关开启：最新译文 / 最新一句
        return when (state.mode) {
            SessionType.TRANSLATE ->
                (state.pairs.lastOrNull()?.let { it.translated.ifBlank { it.source } } ?: state.outputText).ifBlank {
                    "$mode · 聆听中"
                }
            SessionType.ASSISTANT ->
                (state.outputText.ifBlank { state.inputText }).ifBlank { "$mode · 聆听中" }
            null -> ""
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cn.moss.live:voice").apply {
            setReferenceCounted(false)
            acquire(8 * 60 * 60 * 1000L) // 8h 上限兜底，正常随服务销毁释放
        }
    }

    override fun onDestroy() {
        job?.cancel()
        job = null
        wakeLock?.release()
        wakeLock = null
        super.onDestroy()
    }
}
