# FCM Helper 1.0.0 构建记录

- 包名：`com.largebatata.fcmhelper`
- 版本：`1.0.0`，`versionCode 3`
- 构建环境：Android Studio JBR 25.0.2、AGP 9.3.0、Gradle 9.5.0、Kotlin 2.2.10、targetSdk 37
- 构建检查：`testDebugUnitTest` 52/52 通过；`assembleRelease` 成功；`lintVitalRelease` 成功
- Release 优化：R8 与资源裁剪均实际运行
- 对齐：`zipalign -c -v 4` 验证成功
- APK 签名：`apksigner verify --verbose --print-certs` 验证成功，APK Signature Scheme v3
- APK：`app/build/outputs/release/FCM-Helper-1.0.0.apk`
- 字节数：2,032,882
- APK SHA-256：`19B92E7C902DF27AD6C4936EFB8C692273CE17FC1BD82BF9C4B0839846204DEB`
- 签名证书 SHA-256：`E92E8B767CA32DB4403ED8332DDAC5332EFE281238E12CE662560F8D91455AE9`
- 签名证书 SHA-1：`F09C3EA566A0F102BFA1B224816A0E685C2CD315`

Release 私钥和凭据保存在仓库外，不随源码或 APK 分发。签名脚本为 `scripts/Sign-Release.ps1`，今后更新必须沿用同一证书。维护者须独立保管密钥备份和恢复口令，并以本记录的证书指纹核对恢复结果；机器绑定的凭据文件不能替代跨电脑恢复备份。

构建时项目尚未初始化 Git，也未建立 GitHub 仓库。本记录证明本地正式构建与校验结果，不代表已经上传源码或发布线上 Release。

签名 APK尚未安装到手机。本地验证确认最终 APK 的 `MonitorService` 常驻通知直接指向 `MainActivity`；事件通知通过 `getLaunchIntentForPackage("com.tencent.mm")` 获取正常 Launcher 入口，创建失败或入口缺失时回退 Helper。R8 映射和针对最终 APK 的单类反编译均支持此结论。
