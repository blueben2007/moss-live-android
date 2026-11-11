package cn.moss.live.ui.records

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.moss.live.AppContainer
import cn.moss.live.data.db.Role
import cn.moss.live.data.db.SessionEntity
import cn.moss.live.data.db.SessionType
import kotlinx.coroutines.launch

/** 会话详情：助理=对话时间线；翻译=原文小字→译文大字 成对时间线。 */
@Composable
fun SessionDetailScreen(container: AppContainer, sessionId: String, onBack: () -> Unit) {
    val session by produceState<SessionEntity?>(initialValue = null, sessionId) {
        value = container.db.sessionDao().byId(sessionId)
    }
    val messages by container.db.messageDao().observeOfSession(sessionId)
        .collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.size(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
            }
            Spacer(Modifier.width(6.dp))
            Text(
                session?.title ?: "记录",
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { confirmDelete = true }) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.size(8.dp))

        LazyColumn(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            when (session?.type) {
                SessionType.TRANSLATE -> {
                    items(messages, key = { it.id }) { m ->
                        Column(Modifier.padding(vertical = 12.dp)) {
                            m.sourceText?.let { src ->
                                Text(
                                    src,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(5.dp))
                            }
                            Text(
                                m.displayText,
                                style = MaterialTheme.typography.displaySmall,
                                color = MaterialTheme.colorScheme.onBackground,
                            )
                        }
                    }
                }
                else -> {
                    items(messages, key = { it.id }) { m ->
                        Column(Modifier.padding(vertical = 10.dp)) {
                            val isUser = m.role == Role.USER
                            Text(
                                if (isUser) "你" else "摩斯",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (isUser) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                m.displayText,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (isUser) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.onBackground,
                            )
                        }
                    }
                }
            }
            item { Spacer(Modifier.size(24.dp)) }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除这条记录？") },
            text = { Text("全部内容将一并删除，不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        container.db.messageDao().deleteOfSession(sessionId)
                        container.db.sessionDao().deleteById(sessionId)
                        confirmDelete = false
                        onBack()
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            },
        )
    }
}
