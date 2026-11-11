package cn.moss.live.ui.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cn.moss.live.AppContainer
import cn.moss.live.stopVoiceSession
import cn.moss.live.session.Phase
import cn.moss.live.ui.common.ElapsedText
import cn.moss.live.ui.common.StopGlyph
import cn.moss.live.ui.common.VoiceWaveform
import cn.moss.live.ui.theme.HangupColor

/** 助理通话页：中央声纹 + 实时字幕 + 挂断圆钮（通话隐喻）。 */
@Composable
fun AssistantScreen(container: AppContainer, onFinished: () -> Unit) {
    val context = LocalContext.current
    val ui by container.repo.ui.collectAsState()
    val level by container.repo.micLevel.collectAsState()
    val conn by container.client.connState.collectAsState()

    // 挂断完成后自动回到首页
    LaunchedEffect(ui.phase) {
        if (ui.phase == Phase.Idle) onFinished()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(28.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusLabel(ui, conn)
            Spacer(Modifier.width(12.dp))
            ElapsedText(ui.startedAt)
        }

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            VoiceWaveform(
                level = level,
                speaking = ui.phase == Phase.Speaking,
                modifier = Modifier.size(260.dp, 180.dp),
            )
        }

        // 实时字幕区：你（灰）在右，摩斯（正）在左
        Column(
            Modifier
                .fillMaxWidth()
                .height(190.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            if (ui.outputText.isNotBlank()) {
                Text(
                    ui.outputText,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(14.dp))
            }
            if (ui.inputText.isNotBlank()) {
                Text(
                    ui.inputText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        ui.errorMessage?.let { msg ->
            Text(
                msg,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        Spacer(Modifier.height(32.dp))
        HangupButton {
            stopVoiceSession(context)
        }
        Spacer(Modifier.height(28.dp))
        Spacer(Modifier.navigationBarsPadding())
    }
}

@Composable
private fun StatusLabel(
    ui: cn.moss.live.session.SessionUiState,
    conn: cn.moss.live.protocol.MossLiveClient.ConnState,
) {
    val text = when {
        conn == cn.moss.live.protocol.MossLiveClient.ConnState.RECONNECTING -> "重连中…"
        ui.phase is Phase.Failed -> "出错"
        ui.phase == Phase.Connecting -> "连接中…"
        ui.phase == Phase.Speaking -> "说话中"
        ui.phase == Phase.Listening -> "聆听中…"
        else -> ""
    }
    Text(text, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun HangupButton(onClick: () -> Unit) {
    Box(
        Modifier
            .size(76.dp)
            .background(HangupColor, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        StopGlyph(24.dp)
    }
}
