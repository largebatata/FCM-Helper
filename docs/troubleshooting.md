# Troubleshooting

## READ_LOGS 未授权

重新执行 Setup 页面显示的 ADB 命令，然后回到 App。不要执行 `logcat -c`；FCM Helper 不需要清空系统日志。

## Reader 显示异常或恢复中

打开“设置 → 高级信息”检查 reader 状态、子进程、重连次数、最近读取和故障类型。Reader 使用有限指数退避自动恢复，不需要按固定周期重启。

## 解锁时有 Toast，通知栏没有消息

这是预期策略：未锁屏时使用 Toast；锁屏时使用 Helper 通知。

## Shizuku 已连接但唤醒失败

增强唤醒是实验功能。当前微信版本可能不导出后台服务，系统也可能阻止后台启动。关闭增强唤醒不会影响核心 FCM 监听和提醒。

## 导出诊断

打开“设置 → 高级信息 → 导出诊断信息”，在 Android 分享界面选择保存或发送目标。导出内容不包含 FCM payload、完整 seq、rdata、uin 或聊天内容。
