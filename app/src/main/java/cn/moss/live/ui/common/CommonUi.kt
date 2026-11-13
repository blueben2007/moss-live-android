package cn.moss.live.ui.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin

/** 会话计时 mm:ss，每秒走一格。 */
@Composable
fun ElapsedText(startedAt: Long?, modifier: Modifier = Modifier) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAt) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }
    val sec = startedAt?.let { ((now - it) / 1000).coerceAtLeast(0) } ?: 0
    androidx.compose.material3.Text(
        "%02d:%02d".format(sec / 60, sec % 60),
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 声纹五条：随麦克风音量起伏；无人说话时缓慢呼吸。
 * 通话隐喻的核心动效——克制、无发光。
 */
@Composable
fun VoiceWaveform(
    level: Float,
    speaking: Boolean,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val transition = rememberInfiniteTransition(label = "wave")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing)),
        label = "phase",
    )
    Canvas(modifier) {
        val gap = 20.dp.toPx()
        val width = 7.dp.toPx()
        val base = 8.dp.toPx()
        val amp = if (speaking) 56.dp.toPx() else base + 52.dp.toPx() * level
        val cx = center.x
        for (i in 0..4) {
            val wave = (0.55f + 0.45f * sin(phase + i * 0.9f)).coerceIn(0.15f, 1f)
            val centerWeight = if (i == 2) 1f else 0.72f
            val h = (base + amp * wave * centerWeight).coerceAtLeast(base)
            val x = cx + (i - 2) * gap
            drawLine(
                color = color,
                start = Offset(x, center.y - h / 2),
                end = Offset(x, center.y + h / 2),
                strokeWidth = width,
                cap = StrokeCap.Round,
            )
        }
    }
}

/** 停止/挂断按钮中心的白色圆角方块。 */
@Composable
fun StopGlyph(size: Dp, modifier: Modifier = Modifier, color: Color = Color.White) {
    Canvas(modifier.size(size)) {
        drawRoundRect(
            color = color,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.toPx() / 4),
        )
    }
}

/** 联调诊断行：连接状态 / 阶段 / 麦克风电平 / 上行流量 / 会话事件累计 / 已发命令 / 事件时间线 / 断连原因 / 采集异常，可一键复制。 */
@Composable
fun DiagnosticsRow(
    conn: String,
    phase: String,
    micPct: Int,
    uplinkKb: Long,
    eventCounts: Map<String, Int>,
    seq: List<String>,
    captureError: String?,
    lastDisconnect: String,
    sentCounts: Map<String, Int> = emptyMap(),
    timeline: List<String> = emptyList(),
    modifier: Modifier = Modifier,
) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val countsText = eventCounts.entries.joinToString(" ") { "${it.key}×${it.value}" }
    val sentText = sentCounts.entries.joinToString(" ") { "${it.key}×${it.value}" }
    val text = buildString {
        append("诊断 连接:$conn · 阶段:$phase · 麦克:$micPct% · 上行:${uplinkKb}KB")
        if (countsText.isNotBlank()) append("\n事件累计: $countsText")
        if (sentText.isNotBlank()) append("\n已发送: $sentText")
        if (seq.isNotEmpty()) append("\n最近: " + seq.takeLast(6).joinToString(" "))
        if (timeline.isNotEmpty()) append("\n时间线: " + timeline.takeLast(30).joinToString(" "))
        if (lastDisconnect.isNotBlank()) append("\n断连: $lastDisconnect")
        captureError?.let { append("\n采集异常: $it") }
    }
    Column(modifier) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 8,
            overflow = TextOverflow.Ellipsis,
        )
        androidx.compose.material3.TextButton(onClick = { clipboard.setText(AnnotatedString(text)) }) {
            Text("复制诊断", style = MaterialTheme.typography.labelMedium)
        }
    }
}
