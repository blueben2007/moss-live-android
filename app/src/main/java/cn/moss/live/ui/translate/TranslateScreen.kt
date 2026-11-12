package cn.moss.live.ui.translate

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cn.moss.live.AppContainer
import cn.moss.live.stopVoiceSession
import cn.moss.live.session.Phase
import cn.moss.live.session.TranslatePair
import cn.moss.live.ui.common.DiagnosticsRow
import cn.moss.live.ui.common.ElapsedText
import cn.moss.live.ui.common.StopGlyph

/** 翻译页：字幕隐喻——原文小字灰在上，中文译文大字在下，自动滚动，绝不朗读。 */
@Composable
fun TranslateScreen(container: AppContainer, onFinished: () -> Unit) {
    val context = LocalContext.current
    val ui by container.repo.ui.collectAsState()
    val conn by container.client.connState.collectAsState()
    val uplink by container.client.bytesSent.collectAsState()
    val events by container.client.recentEvents.collectAsState()
    val capErr by container.mic.captureError.collectAsState()
    val level by container.repo.micLevel.collectAsState()
    var monitor by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(ui.phase) {
        if (ui.phase == Phase.Idle) onFinished()
    }
    // 字幕更新时滚到底部
    LaunchedEffect(ui.pairs) {
        if (ui.pairs.isNotEmpty()) listState.animateScrollToItem(ui.pairs.size - 1)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .padding(horizontal = 24.dp),
    ) {
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (ui.phase is Phase.Failed) "出错" else "同传字幕",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.width(10.dp))
            ElapsedText(ui.startedAt)
            Spacer(Modifier.weight(1f))
            SmallStopButton { stopVoiceSession(context) }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "听到中文将直接转写 · 不会朗读",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "试听译文音频（联调）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = monitor, onCheckedChange = {
                monitor = it
                container.repo.setTranslateMonitor(it)
            })
        }
        Spacer(Modifier.height(10.dp))

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(30.dp),
        ) {
            itemsIndexed(ui.pairs) { _, pair ->
                PairView(pair, current = false)
            }
        }

        ui.errorMessage?.let { msg ->
            Text(
                msg,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }

        DiagnosticsRow(
            conn = conn.name,
            phase = when (ui.phase) {
                Phase.Connecting -> "连接中"
                Phase.Listening -> "聆听"
                Phase.Speaking -> "说话"
                is Phase.Failed -> "失败"
                Phase.Idle -> "空闲"
            },
            micPct = (level * 100).toInt(),
            uplinkKb = uplink / 1024,
            eventCounts = container.client.eventCounts.collectAsState().value,
            seq = events,
            captureError = capErr,
            lastDisconnect = container.client.lastDisconnect.collectAsState().value,
        )
        Spacer(Modifier.height(8.dp))
        Spacer(Modifier.navigationBarsPadding())
    }
}

@Composable
private fun PairView(pair: TranslatePair, current: Boolean) {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (pair.source.isNotBlank()) {
            Text(
                pair.source,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
        }
        Text(
            pair.translated.ifBlank { "…" },
            style = MaterialTheme.typography.displaySmall,
            color = if (current) MaterialTheme.colorScheme.onBackground
            else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.88f),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun SmallStopButton(onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        StopGlyph(14.dp, color = MaterialTheme.colorScheme.onSurface)
    }
}
