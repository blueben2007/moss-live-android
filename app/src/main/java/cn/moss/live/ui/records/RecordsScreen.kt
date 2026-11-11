package cn.moss.live.ui.records

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.moss.live.AppContainer
import cn.moss.live.data.db.SessionEntity
import cn.moss.live.data.db.SessionType
import cn.moss.live.data.db.buildFtsQuery
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 记录页：统一会话列表（助理/翻译混合，标注类型）+ 全文搜索。 */
@Composable
fun RecordsScreen(container: AppContainer, onBack: () -> Unit, onOpenSession: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    val all by container.db.sessionDao().observeAll().collectAsState(initial = emptyList())
    var searchedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var deleteTarget by remember { mutableStateOf<SessionEntity?>(null) }

    LaunchedEffect(query) {
        if (query.isBlank()) {
            searchedIds = emptySet()
            return@LaunchedEffect
        }
        delay(250) // 简易防抖
        searchedIds = container.db.messageDao().searchSessionIdsRaw(buildFtsQuery(query)).toSet()
    }
    val shown = if (query.isBlank()) all else all.filter { it.id in searchedIds }

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
            Text("交流记录", style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.size(12.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("搜索对话内容与译文") },
            singleLine = true,
            trailingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.size(12.dp))

        LazyColumn(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            if (shown.isEmpty()) {
                item {
                    Text(
                        if (query.isBlank()) "还没有记录，去说几句吧" else "没有匹配的记录",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 48.dp, bottom = 12.dp),
                    )
                }
            }
            items(shown, key = { it.id }) { session ->
                SessionRow(
                    session = session,
                    onClick = { onOpenSession(session.id) },
                    onDelete = { deleteTarget = session },
                )
            }
            item { Spacer(Modifier.size(24.dp)) }
        }
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除这条记录？") },
            text = { Text("「${target.title}」的全部对话与字幕将一并删除，不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        container.db.messageDao().deleteOfSession(target.id)
                        container.db.sessionDao().deleteById(target.id)
                        deleteTarget = null
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun SessionRow(session: SessionEntity, onClick: () -> Unit, onDelete: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(session.title, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.width(10.dp))
                    TypeChip(session.type)
                }
                Spacer(Modifier.size(4.dp))
                Text(
                    SimpleDateFormat("yyyy年M月d日 HH:mm", Locale.SIMPLIFIED_CHINESE)
                        .format(Date(session.startedAt)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TypeChip(type: SessionType) {
    val isAssistant = type == SessionType.ASSISTANT
    Box(
        Modifier
            .background(
                if (isAssistant) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(
            if (isAssistant) "助理" else "翻译",
            style = MaterialTheme.typography.labelMedium,
            color = if (isAssistant) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
