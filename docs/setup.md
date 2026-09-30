# 安装与设置

## 1. 安装

从可信的项目发布页下载 `FCM-Helper-1.0.0.apk`，并核对发布记录中的 SHA-256 与签名证书指纹。使用 ADB 安装时应明确选择目标设备：

```bash
adb -s DEVICE_SERIAL install -r FCM-Helper-1.0.0.apk
```

`-r` 仅在设备上现有 App 与正式版使用相同签名时才能保留数据原地升级。此前 Debug/RC 测试版通常使用 Android Debug 证书，不能通过正式证书覆盖安装。请勿在确认数据影响前卸载或清除现有 App。

若必须从不同签名的测试版切换，Android 正常签名校验不允许保留该 App 私有数据直接覆盖。卸载测试版会删除 FCM Helper 的本地设置、最近24小时事件历史和诊断文件；之后须重新安装正式版、检查通知权限、重新授予 `READ_LOGS` 并手动开始监听。微信和 microG 的数据不应被清除。

## 2. 通知权限

首次打开 App 后按系统提示授予通知权限。该权限用于前台监听状态通知和锁屏微信提醒。

## 3. READ_LOGS

Android 不提供普通的 READ_LOGS 授权弹窗，需要通过 ADB 执行：

```bash
adb -s DEVICE_SERIAL shell pm grant com.largebatata.fcmhelper android.permission.READ_LOGS
```

只读检查：

```bash
adb -s DEVICE_SERIAL shell dumpsys package com.largebatata.fcmhelper
```

确认 `android.permission.READ_LOGS: granted=true` 后返回 App。

## 4. 开始监听

点击“开始监听”。状态页应显示前台服务运行、reader 正常或等待日志。正常长时间没有日志不代表 reader 断线。

## 5. 系统后台策略

部分系统会限制用户安装 App 的后台运行。若锁屏后监听被系统终止，请在系统提供的应用启动或电池管理界面中允许 FCM Helper 按预期后台运行。具体名称取决于设备系统；项目不会自动修改这些设置。
