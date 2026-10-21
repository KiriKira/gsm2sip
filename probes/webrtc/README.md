# Android WebRTC 合成 PCM 探针

本目录是独立 Gradle 构建，不加入网关或主机的 app/settings/dependencies。
包名为 `com.callagent.gateway.webrtcprobe`，默认使用 instrumentation，无启动器入口；debug 构建额外提供显式 WebRtcProbeActivity，供模拟器来电共存实验，不申请录音、电话、短信或 root 权限。
目标实机基线为用户提供的 **Android 16 + Magisk root**；该探针本身不使用 root，也不拨打蜂窝电话。

## 已实现与尚未验证

- 固定第三方 `webrtc-sdk` 发布的 `io.github.webrtc-sdk:android:150.7871.01` AAR、SHA-256、
  发布者声明的源码提交和输入源码哈希。它不是 Google 官方预编译发行包。
- 原 SDK 支持 `setAudioRecordEnabled(false)` + `AudioBufferCallback` 外部采集注入；
  但播放回调在默认 AudioTrack 写入后触发，speaker mute 还会清零回调数据，不能直接作为独占的蜂窝上行输出。
- `sdk/external-playout.patch` 为探针增加显式的外部播放分支：10 ms 节拍从 native 拉取解码 PCM，
  只交给外部回调，不创建/写入默认 AudioTrack，也不修改系统通话音量。保留默认分支和原 JNI 方法/构造器签名。
  外部模式的缓冲帧数 getter 使用已配置值，避免 native 初始化时解引用不存在的 AudioTrack。
- 准备脚本从固定、经校验的源码重新生成 Java 类，移除 AAR 中对应的旧 Java 类；所有保留的 native `.so`
  与原始 AAR 字节一致。ABI 限定 arm64-v8a/x86_64，检查 ELF 的 16 KB 对齐，并把上游 LICENSE/PATENTS
  和固定发布者提交中的完整第三方许可证声明放入探针 APK。
- 仪器测试使用两个真实 native PeerConnection、ICE/DTLS-SRTP 和仅 Opus 的协商，各注入不同的合成音。
  必须两侧都收到对端音调且通过帧数门槛，并检查默认录音/播放路径未使用，才返回 PASS。
  本地回声、静音、只连接端口或只调用回调都不能满足断言。
- 2026-10-17 在 Android 16 / API 36 / x86_64 软件模拟器上通过原生双向合成 PCM 验证，
  包括真实 ICE/DTLS-SRTP、Opus 编解码、独占外部音频路径及资源清理。这不是 arm64 或 Magisk 真机验收。
  蜂窝 PCM、真实 SIM、8/16 kHz 重采样、DTMF、Android TURN 和持续运行均未验收，生产应用仍使用 SIP。

ICE 候选经容量受限的队列异步转交，避免两个 signaling 线程在同步 native 调用中互相等待。
先在独立的 20 秒窗口内等待 ICE/DTLS 连接，再启用音频，并在原有 20 秒 PCM 窗口内验证双向音调。
外部采集回调用 10 ms 节拍代替 AudioRecord.read 的阻塞，不积累补帧债务；
合成音采用递推振荡器和整周期帧缓存，批量写入直接缓冲区，检测器复用频率系数。
频率、连续相位、振幅、变长帧、静音与回声拒绝仍由独立测试约束。

## 构建与工具测试

需要仓库原有的 JDK 17、Android SDK 35、curl、patch、Python 3。命令在网关仓库根目录执行：

```sh
./gradlew -p probes/webrtc assembleDebug testDebugUnitTest --no-daemon --max-workers=1
python3 -m unittest discover -s probes/webrtc/tools -p 'test_*.py'
```

首次构建通过 TLS 校验下载固定 SDK/源码；输入哈希不符即失败，不静默替换坏缓存、不降低校验。
所有下载、生成源码、派生 AAR、许可证、provenance 和 APK 都位于被忽略的 `probes/webrtc/build/`。
清理缓存应只使用本独立构建的 clean，不更改预期校验和以绕过错误。

APK：`probes/webrtc/build/outputs/apk/debug/Gsm2SipWebRtcProbe-debug.apk`。
来源报告：`probes/webrtc/build/webrtc-sdk/provenance.json`。
报告中的 native_runtime_validated/cellular_audio_validated 固定为 false，表示构建不能证明设备验收；
运行器会另行保存与 APK 哈希绑定的运行结果。

## 在设备上运行

先使测试设备出现在 `adb devices` 的 device 状态；多设备时必须指定序列号。

```sh
python3 probes/webrtc/tools/run_probe.py --serial DEVICE_SERIAL
```

运行器先检查设备，再从当前输入构建、安装独立探针 APK 并执行 instrumentation。
它不会安装/卸载网关或主机应用，不清除其数据，不运行 su。
结果写入 `probes/webrtc/build/probe-result.json`：PASS 的退出码为 0，FAIL 为 1，缺少设备等 BLOCKED 为 2。
必须同时满足 instrumentation result code、结构化 PASS 和双向 PCM 证据；adb 自身退出 0 不表示探针通过。
结果仅代表 `scope=synthetic_webrtc_pcm`，cellular_audio/turn 必须保持 NOT_RUN。
`--reuse-identical-apk` 仍先从当前输入构建，再校验设备上唯一 base.apk 的 SHA-256；只有与当前构建字节相同时才复用，且强制停止旧进程后启动新测试。
默认仍重新安装。`--instrumentation-timeout` 默认 90 秒，只控制外层 adb 等待，不改变内部 ICE/PCM 窗口或帧数门槛；运行结果记录阶段及安装方式。
失败结果保留受控的阶段、连接状态、队列与帧计数，不保存原始异常文本、SDP 或 ICE 凭据。
连接与 SDP 在测试进程内传递，不写入普通结果文件；运行器不保存完整 native/信令输出。

后续蜂窝音频探针应单独接入已验证的 AudioRecord/AudioTrack 路由、采样率转换和前台授权，
不能把本合成音频结果改名为真机蜂窝验收。现有 UI/短信/配对/多节点流程保持总迁移计划的约束。

## 来源与生产门槛

发布者仓库固定提交：`3396525b2f3d7f7271f259b1f79351e3de45a1f4`。
其 CHANGES.md 把 150.7871.01 对应到 WebRTC fork 提交 `73cb8180f7258ee292878d6edd05177f41883962`。
sources.jar 只有占位 README，所以准备脚本从该固定源码提交获取输入，而不把占位 jar 当作源码证据。
发布脚本还会调整 Java class version；本次使用并校验 Maven 发布物，没有改动其原生代码或关闭 TLS/哈希检查。

来源链接：

- [发布者版本记录](https://github.com/webrtc-sdk/android/blob/3396525b2f3d7f7271f259b1f79351e3de45a1f4/CHANGES.md)
- [发布者构建/打包脚本](https://github.com/webrtc-sdk/android/blob/3396525b2f3d7f7271f259b1f79351e3de45a1f4/downloadAar.sh)
- [声明的 WebRTC 源码](https://github.com/webrtc-sdk/webrtc/tree/73cb8180f7258ee292878d6edd05177f41883962)

这些是发布者声明、固定输入和本地派生构建的证据，不是本次从源码重建 native 库的证明。
生产采用前仍须验证 Android 16 的实际加载/关闭、独占音频路由、性能/线程回收、SDK 完整可复现构建、
所有可见编码/语音设置的等价语义及三端升级回退。该 Java 适配目前只存在于独立探针，不进入生产依赖。

开发环境可选 `--driver activity`，使用相同 SyntheticPeerPair 和结果校验器；每次启动传入新的 run_id，旧结果及失败结果码不能通过。模拟来电共存时先准备 APK，再由前台实验应用显式启动该 Activity，核对 SHA-256 后读取新编号结果。该入口不会接入生产通话或蜂窝音频。
