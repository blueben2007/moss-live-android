package cn.moss.live.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cn.moss.live.AppContainer
import cn.moss.live.data.prefs.Voices
import cn.moss.live.session.VoiceSessionRepository
import kotlinx.coroutines.launch

/** 设置页：API Key / 音色 / 长期记忆 / 锁屏字幕 / 自定义人设 / 清空记录。 */
@Composable
fun SettingsScreen(container: AppContainer, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val settings by container.settings.settings.collectAsState(initial = null)
    var keyInput by remember { mutableStateOf("") }
    var personaInput by remember { mutableStateOf("") }
    var loadedOnce by remember { mutableStateOf(false) }
    var confirmWipe by remember { mutableStateOf(false) }

    LaunchedEffect(settings) {
        val s = settings ?: return@LaunchedEffect
        if (!loadedOnce) {
            keyInput = s.apiKey
            personaInput = s.persona
            loadedOnce = true
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.size(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
            }
            Spacer(Modifier.width(6.dp))
            Text("设置", style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.size(20.dp))

        Section("连接") {
            OutlinedTextField(
                value = keyInput,
                onValueChange = { keyInput = it },
                label = { Text("MOSS API Key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.size(10.dp))
            Button(onClick = { scope.launch { container.settings.setApiKey(keyInput) } }) {
                Text("保存 Key")
            }
        }

        Section("音色（下次会话生效）") {
            Row {
                FilterChip(
                    selected = settings?.voiceId == Voices.NUANNUAN,
                    onClick = { scope.launch { container.settings.setVoiceId(Voices.NUANNUAN) } },
                    label = { Text("暖暖 · 女声") },
                )
                Spacer(Modifier.width(12.dp))
                FilterChip(
                    selected = settings?.voiceId == Voices.MUMU,
                    onClick = { scope.launch { container.settings.setVoiceId(Voices.MUMU) } },
                    label = { Text("木木 · 男声") },
                )
            }
        }

        Section("行为") {
            SettingSwitch(
                title = "跨会话长期记忆",
                desc = "助理会记得之前聊过的内容（由服务端按你的账号保存）",
                checked = settings?.memoryEnabled ?: true,
                onChange = { scope.launch { container.settings.setMemoryEnabled(it) } },
            )
            SettingSwitch(
                title = "锁屏显示实时字幕",
                desc = "常驻通知里滚动显示最新译文或对话",
                checked = settings?.lockCaption ?: false,
                onChange = { scope.launch { container.settings.setLockCaption(it) } },
            )
        }

        Section("助理人设") {
            OutlinedTextField(
                value = personaInput,
                onValueChange = { personaInput = it },
                label = { Text("自定义人设（可留空）") },
                minLines = 3,
                supportingText = {
                    Text("留空使用默认：${VoiceSessionRepository.DEFAULT_PERSONA}")
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.size(10.dp))
            Button(onClick = { scope.launch { container.settings.setPersona(personaInput) } }) {
                Text("保存人设")
            }
        }

        Section("数据") {
            TextButton(
                onClick = { confirmWipe = true },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text("清空全部记录")
            }
        }
        Spacer(Modifier.size(32.dp))
    }

    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            title = { Text("清空全部记录？") },
            text = { Text("所有助理对话与翻译字幕将被永久删除，不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        container.db.messageDao().deleteAll()
                        container.db.sessionDao().deleteAll()
                        confirmWipe = false
                    }
                }) { Text("清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmWipe = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(10.dp))
        content()
        Spacer(Modifier.size(30.dp))
    }
}

@Composable
private fun SettingSwitch(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
