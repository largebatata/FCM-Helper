# Changelog

## 1.0.0

- 发布正式版，`versionCode` 从 2 提升到 3；保留已验证的 FCM 监听、分类与提醒行为。
- 事件通知点击后优先打开 user 0 主微信的正常 Launcher 入口；无法取得或创建入口时回退 FCM Helper。常驻监听通知仍打开 FCM Helper。
- Release 继续启用 R8 与资源裁剪，使用独立的正式发布证书签名。
- Shizuku 增强唤醒仍为默认关闭的实验性功能，不影响核心 FCM 提醒。

## 1.0.0-rc1

- 收敛 READ_LOGS + microG Logcat 前台监听链路为首个发布候选版。
- 保留 `MESSAGE`、`CALL`、`PC_LOGIN`、`UNKNOWN` 分类和现有提醒策略。
- 新增可跳过的首次设置引导和 READ_LOGS ADB 命令复制入口。
- 新增最近24小时脱敏事件历史与独立清空入口，最多保留200条。
- 新增基于 FileProvider 的用户主动诊断导出。
- 将 Shizuku 增强唤醒标记为默认关闭的实验性功能，并细分失败原因。
- 统一首页四张统计卡布局，压缩首页和设置页。
- 最终图标移除推送波纹并调整气泡视觉中心。
- Release 启用 R8 应用优化和资源裁剪。
