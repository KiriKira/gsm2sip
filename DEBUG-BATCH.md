# 2026-10-17 debug 联调包

本批次三个仓库使用同名分支 `ci/webrtc-sip-debug-20261017`，各自包含
`debug-batch.json` 中记录的最新 main 和 WebRTC 实验分支。
CI 产物内的 `BUILD-INFO.json` 记录实际构建提交、运行链接和文件 SHA-256。

## 兼容与验证边界

正式 App 仍走现有 SIP/Asterisk 通路；保留 main 的 SIP、来电、多节点修复及原生 SIP 库。
不把 synthetic TestAudioBackend、虚拟 PhoneAccount 或模拟器活动加入正式 App。
WebRTC 命令 codec/ICE buffer 和服务端信令模块仍为实验阶段，生产三端媒体尚未接通。
本批次不能用于验证 SIP 对端与 WebRTC 对端互通，也没有启用自动传输切换。

Gateway checks 和 Android client 的 artifact 分别提供网关 APK/Magisk 包及主机 APK。
普通 debug APK 使用 Android debug 签名，与已有 release 签名不同；需要独立测试设备，
避免直接卸载日常安装造成数据丢失。它们保留原 applicationId 和原生 ABI，不使用模拟器专用裁剪。

网关的独立 `webrtc-synthetic-probe` artifact 另含探针 APK、SDK 来源与 JVM 测试报告。
无启动器图标；按 `probes/webrtc/README.md` 用 ADB instrumentation 运行。
探针仅检查双向合成 PCM，不打开蜂窝音频或验证真实 SIM/音质。

服务器 CI 在原有 Go race、API、数据库及 Asterisk 运行验证通过后提供 Linux amd64/arm64 debug 包。
包内包含带调试信息的 api/worker/admin、migrations 和来源信息；不是自动部署或正式 release。
沿用已有 SIP/Asterisk 环境配置，先在独立测试数据库验证迁移，不将实验二进制直接替换生产。
