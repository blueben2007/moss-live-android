package cn.moss.live

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import cn.moss.live.service.VoiceForegroundService
import cn.moss.live.ui.onboarding.ApiKeyGateScreen
import cn.moss.live.ui.home.HomeScreen
import cn.moss.live.ui.assistant.AssistantScreen
import cn.moss.live.ui.records.RecordsScreen
import cn.moss.live.ui.records.SessionDetailScreen
import cn.moss.live.ui.settings.SettingsScreen
import cn.moss.live.ui.theme.MossLiveTheme
import cn.moss.live.ui.translate.TranslateScreen

class MainActivity : ComponentActivity() {

    private val container by lazy { (application as MossLiveApp).container }

    /**
     * 锁屏保活的关键：Doze 会在熄屏一段时间后挂起后台网络，前台服务也不例外。
     * 申请「忽略电池优化」，每台设备只弹一次（用户拒绝后不再打扰，可在系统设置里手动改）。
     */
    private fun requestBatteryExemptionOnce() {
        val prefs = getSharedPreferences("boot", MODE_PRIVATE)
        if (prefs.getBoolean("battery_asked", false)) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        prefs.edit().putBoolean("battery_asked", true).apply()
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName")),
            )
        } catch (_: Exception) {
            // 部分 ROM 没有该页面：忽略，用户可在系统设置中手动设置
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { /* 结果由各界面兜底提示 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val permissions = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(permissions.toTypedArray())
        requestBatteryExemptionOnce()
        setContent {
            MossLiveTheme {
                AppRoot(container)
            }
        }
    }
}

@Composable
fun AppRoot(container: AppContainer) {
    val navController = rememberNavController()
    // settings 为 null 表示尚未加载，先只铺底色避免闪烁
    val settings by container.settings.settings.collectAsState(initial = null)
    val apiKeyReady = settings?.apiKey?.isNotBlank() == true

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        if (settings != null) {
            NavHost(
                navController = navController,
                startDestination = if (apiKeyReady) "home" else "onboarding",
            ) {
                composable("onboarding") {
                    ApiKeyGateScreen(
                        container = container,
                        onDone = {
                            navController.navigate("home") {
                                popUpTo("onboarding") { inclusive = true }
                            }
                        },
                    )
                }
                composable("home") {
                    HomeScreen(
                        container = container,
                        nav = { route -> navController.navigate(route) },
                    )
                }
                composable("assistant") {
                    AssistantScreen(
                        container = container,
                        onFinished = { navController.popBackStack() },
                    )
                }
                composable("translate") {
                    TranslateScreen(
                        container = container,
                        onFinished = { navController.popBackStack() },
                    )
                }
                composable("records") {
                    RecordsScreen(
                        container = container,
                        onBack = { navController.popBackStack() },
                        onOpenSession = { id -> navController.navigate("session/$id") },
                    )
                }
                composable("session/{id}") { entry ->
                    SessionDetailScreen(
                        container = container,
                        sessionId = entry.arguments?.getString("id").orEmpty(),
                        onBack = { navController.popBackStack() },
                    )
                }
                composable("settings") {
                    SettingsScreen(
                        container = container,
                        onBack = { navController.popBackStack() },
                    )
                }
            }
        }
    }
}

/** 从界面发起语音会话（必须在前台发起，Android 12+ 前台服务限制）。 */
fun startVoiceSession(context: android.content.Context, assistant: Boolean) {
    val action = if (assistant) VoiceForegroundService.ACTION_START_ASSISTANT
    else VoiceForegroundService.ACTION_START_TRANSLATE
    context.startForegroundService(Intent(context, VoiceForegroundService::class.java).setAction(action))
}

fun stopVoiceSession(context: android.content.Context) {
    context.startService(
        Intent(context, VoiceForegroundService::class.java)
            .setAction(VoiceForegroundService.ACTION_STOP),
    )
}
