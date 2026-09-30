# FCM Helper

FCM Helper 是一个面向特定 Android、microG 和 Google Play 版微信环境的本地辅助工具。它通过受限的 `READ_LOGS` 权限窄范围读取 microG 的微信 FCM 日志，识别普通消息、微信电话、电脑版登录请求和未知事件，并按锁屏状态显示提醒。

当前正式版本：`1.0.0`（`versionCode 3`）。发布文件为 `FCM-Helper-1.0.0.apk`；下载后请按发布记录核对 SHA-256 和签名证书指纹。

## 当前验证环境

- HUAWEI Mate 70 RS（PLU-AL10）
- microG Services `0.3.16.252432-hw`
- Google Play 版微信 `8.0.72`
- Android `minSdk 31`，`targetSdk 37`

其他设备、系统和微信版本可能具有不同的日志格式、后台限制或权限行为。本项目不承诺兼容全部 Android 或华为设备。

## 功能

- FCM 检测只监听 `GmsGcmMcsInput` 和 `GmsGcmMcsSvc` 两个 Logcat tag
- 有待处理提醒时，额外窄范围监听 Activity 前台事件；仅主微信 user 0 进入前台时提前清除提醒
- 分类 `MESSAGE`、`CALL`、`PC_LOGIN` 和 `UNKNOWN`
- 前台解锁状态使用 Toast，锁屏状态使用通知
- Logcat 子进程 EOF、异常退出或权限丢失后自动恢复
- 最近24小时脱敏事件历史，最多200条
- 可导出不含消息内容的诊断信息
- 可选的 Shizuku 增强唤醒实验功能，默认关闭

## 安装与首次设置

1. 安装 APK 并打开 FCM Helper。
2. 按系统提示授予通知权限。
3. 连接电脑并确认 ADB 已识别设备。
4. 执行：

   ```bash
   adb shell pm grant com.largebatata.fcmhelper android.permission.READ_LOGS
   ```

5. 返回 App，确认“读取系统日志”显示已授权。
6. 点击“开始监听”。

`READ_LOGS` 是 Android 受限权限，普通运行时权限弹窗无法授予。重新安装、签名变化或系统策略变化后可能需要再次检查授权状态。完整步骤见 [docs/setup.md](docs/setup.md)。

从 Debug/RC 测试版升级前，先核对已安装 App 与正式版的签名。签名不同不能原地覆盖；卸载测试版会删除其本地设置、事件历史和诊断文件，详见[安装与升级说明](docs/setup.md)。

## Shizuku 增强唤醒

增强唤醒是实验性可选功能，默认关闭。它只在去重后的 `MESSAGE`、`CALL` 或 `PC_LOGIN` 事件到达时尝试唤醒 user 0 主微信，不处理 `UNKNOWN` 或 user 128 微信分身，也不会打开微信 Activity。

微信组件导出状态及后台限制会随版本和系统变化。增强唤醒失败不会影响 FCM 读取、分类、Toast 或通知。

## 隐私

所有处理均在设备本地完成。事件历史只保存时间戳和类型；诊断日志只记录状态、错误类别和计数。项目不保存 FCM payload、完整 seq、rdata、uin、聊天内容、联系人、微信号或手机号，也不包含广告、analytics、账号系统、云上传或后台服务器。详见 [PRIVACY.md](PRIVACY.md)。

## 排障

设置页“高级信息”可查看权限、reader、微信、microG 和 Shizuku 状态，也可导出脱敏诊断文件。常见问题见 [docs/troubleshooting.md](docs/troubleshooting.md)。

## 构建

使用项目 Gradle Wrapper 和 Android Studio JBR：

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat testDebugUnitTest assembleRelease
.\scripts\Sign-Release.ps1 -InputApk .\app\build\outputs\apk\release\app-release-unsigned.apk -OutputApk .\app\build\outputs\release\FCM-Helper-1.0.0.apk -BuildToolsDir (Join-Path $env:ANDROID_HOME 'build-tools\37.0.0')
```

最后一条命令要求 `ANDROID_HOME` 指向本机已有 SDK；也可直接传入实际 Build Tools 路径，不改变项目 `local.properties` 的 SDK。Release 构建启用 AGP 9.3 应用优化和资源裁剪。仓库不包含发布私钥或密码；[签名脚本](scripts/Sign-Release.ps1)默认只接受已有正式密钥，防止换电脑时无意生成新证书。密钥和经 Windows 用户账户加密的密码保存在项目外，后续版本必须沿用同一证书。跨电脑恢复需要现有密钥的独立备份及另外保管的恢复口令，不能只依赖 Windows DPAPI 文件。

## License

本项目使用 [Apache License 2.0](LICENSE)。

## 商标声明

FCM Helper 是独立第三方开源项目，与 Tencent（腾讯）、WeChat（微信）、Google、microG、Huawei（华为）或 Shizuku 不存在官方隶属、授权或合作关系。相关名称仅用于客观描述兼容性和用途；微信及其他相关商标归各自权利人所有。本项目不提供这些第三方产品的官方支持。
