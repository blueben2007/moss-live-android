package cn.moss.live.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import kotlinx.coroutines.launch

/** 首启无 Key 的引导页：API Key 只进设备 DataStore，永不进仓库。 */
@Composable
fun ApiKeyGateScreen(container: AppContainer, onDone: () -> Unit) {
    var key by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(88.dp))
        Text("摩斯语境", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(14.dp))
        Text(
            "接上你的 MOSS-Live API Key，它就成了随身的语音助理与同传翻译。" +
                "密钥只保存在这台手机上。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(48.dp))
        OutlinedTextField(
            value = key,
            onValueChange = { key = it; error = null },
            label = { Text("MOSS API Key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            isError = error != null,
            supportingText = error?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = {
                if (key.isBlank()) {
                    error = "请粘贴你的 API Key"
                } else {
                    scope.launch {
                        container.settings.setApiKey(key)
                        onDone()
                    }
                }
            },
            modifier = Modifier.width(220.dp),
        ) {
            Text("开始使用")
        }
        Spacer(Modifier.height(20.dp))
        Text(
            "免费 Key 在 platform.moss.cn 申请",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
