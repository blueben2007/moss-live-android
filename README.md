# 摩斯语境

接在 [MOSS-Live](https://github.com/OpenMOSS/MOSS-Live) 全双工实时语音 API 上的轻量安卓客户端。

- **语音助理**：全双工对话，可随时打断，实时字幕，苔绿通话界面
- **同传翻译**：持续听对方说话，任意语种→中文大字字幕显示；听到中文则直接转写。**只显示、不朗读**
- **交流记录**：对话与翻译字幕自动落库，统一列表、类型标注、全文搜索（Room + FTS4）
- **锁屏可用**：`microphone` 前台服务 + Wake Lock，熄屏后继续聆听（翻译/对话不中断）

语音理解、生成全部在 MOSS-Live 云端完成，App 不含任何本地语音模型；API Key 仅保存在手机本地（DataStore），仓库零密钥。

## 安装

到 GitHub Actions 最新一次成功的构建里下载 artifact `MossLive-apk`（或 Releases 页下载 APK），传到手机安装。minSdk 26（Android 8.0+）。

首次打开：填入你在 [platform.moss.cn](https://platform.moss.cn) 申请的 API Key 即可使用。

## CI 打包与签名（一次性配置）

工作流 `.github/workflows/android.yml` 在 push 到 `main` 时自动构建 release APK：
- 仓库 Secrets 里配置了签名材料 → 用 release keystore 签名
- 未配置 → 自动回退 debug 签名（照样出包，装不上覆盖更新而已）
- 打 `v*` tag 时 APK 自动挂到 GitHub Release

### 配置签名 secrets（只需一次）

在本机（非仓库目录）生成 keystore 并 base64：

```bash
keytool -genkeypair -v -keystore moss-live-release.jks -alias moss-live \
  -keyalg RSA -keysize 2048 -validity 10950 \
  -dname "CN=Moss Live, OU=Personal, O=yourname, C=CN"

base64 -w0 moss-live-release.jks > keystore.b64
```

然后在仓库 **Settings → Secrets and variables → Actions** 录入 4 个 secrets：

| Secret | 内容 |
|---|---|
| `KEYSTORE_BASE64` | `keystore.b64` 文件全文 |
| `KEYSTORE_PASSWORD` | keystore 密码 |
| `KEY_ALIAS` | `moss-live` |
| `KEY_PASSWORD` | key 密码（与 keystore 密码相同则填同一个） |

**keystore 与密码务必在仓库外备份**：签名不一致将无法覆盖安装旧版本。

发版：`git tag v1.0.0 && git push origin v1.0.0`。

## 本地构建（可选）

任何装了 JDK 17 的环境直接 `./gradlew assembleDebug`。本仓库开发时使用便携工具链（`.tools/`，已 gitignore）：

```
JAVA_HOME=<jdk17> .tools/gradle/bin/gradle.bat assembleDebug
```

`local.properties` 指向 `.tools/android-sdk`（同样不入库）。

## 真机验收清单

1. **首启配 Key**：首次打开出现引导页，粘贴 Key 后进入首页；重启后直接进首页
2. **全双工对话**：拨通「助理」→说话出字幕→回复带语音；回复中开口说话能打断它
3. **锁屏保活**：通话中锁屏 30 秒后解锁，会话仍在、字幕连续（通知栏显示状态）
4. **翻译模式**：对手机说英语/其他语种 → 中文大字出现；说中文 → 直接转写；全程无声
5. **记录与搜索**：挂断后「交流记录」出现条目（助理/翻译类型标注）；搜索框输入之前说过的词能命中对应会话
6. **卸载重装**：卸载后重装，无 Key 残留，重新填写后一切正常

## 已知限制

- **限免版单会话约 5 分钟**：到期自动换新会话续聊（开了长期记忆则上下文衔接）；设置里可关记忆
- Android 14+ 上**亮屏但 App 在后台**时麦克风可能被系统限制；熄屏（锁屏）不受影响
- 锁屏保活依赖三件套：麦克风前台服务 + 电池优化豁免（首启弹一次窗）+ Wi-Fi 锁；各厂商 ROM 对后台/通知有差异（如小米需开自启动）
- 音色当前内置暖暖（女）/木木（男）两档
- 翻译模式设计上不朗读（字幕机定位）；设置「联调」区有试听译文音频开关，排查用
- 语音页底部有可复制的诊断面板（事件计数/已发命令/时间线/断连原因），反馈问题时复制粘贴即可
