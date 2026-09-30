# GitHub Release 文案：FCM Helper 1.0.0

FCM Helper 1.0.0 是首个正式签名版本。它在已验证的 Android + microG + Google Play 版微信环境中，通过 `READ_LOGS` 读取 microG 的微信 FCM 日志，并在解锁时显示 Toast、锁屏时显示通知。事件类型包括 `MESSAGE`、`CALL`、`PC_LOGIN` 和 `UNKNOWN`。

本版保留单实例 Logcat reader、异常重连、脱敏的最近24小时事件历史与用户主动诊断导出。点击事件通知时优先打开 user 0 主微信的正常 Launcher 入口；常驻监听通知仍打开 FCM Helper。Shizuku 增强唤醒仍为默认关闭的实验功能。

## 下载与校验

- 附件：`FCM-Helper-1.0.0.apk`
- APK SHA-256：`19B92E7C902DF27AD6C4936EFB8C692273CE17FC1BD82BF9C4B0839846204DEB`
- 签名证书 SHA-256：`E92E8B767CA32DB4403ED8332DDAC5332EFE281238E12CE662560F8D91455AE9`
- APK 大小：2,032,882 字节
- 包名：`com.largebatata.fcmhelper`
- 版本：`1.0.0`，`versionCode 3`

## 安装与升级

首次安装后按 App 引导授予通知权限，通过 ADB 授予 `android.permission.READ_LOGS`，再手动点击“开始监听”。详细步骤见 [setup.md](setup.md)。

此前使用 Android Debug 证书的测试版无法由本正式版原地覆盖。卸载测试版会删除 FCM Helper 的本地设置、历史和诊断文件；请先核对已安装签名，不要为了升级清除微信或 microG 数据。

## 兼容性与隐私

主要验证环境是 HUAWEI Mate 70 RS、microG `0.3.16.252432-hw` 和 Google Play 版微信 `8.0.72`。其他设备及版本的行为可能不同。所有 FCM 解析均在本地完成；不保存完整 payload、seq、rdata、uin 或聊天内容。详见 [PRIVACY.md](../PRIVACY.md)。

FCM Helper 是独立第三方开源项目，与 Tencent（腾讯）、WeChat（微信）、Google、microG、Huawei（华为）、Shizuku 无官方隶属、授权或合作关系。相关商标归各自权利人所有。
