# WebRTC 三项目联合验证（2026-10-19）

最终源代码：Gateway `c2a278381f59581d1776b02bab30b3536cec8784`，Host `9673b767955ca9408bcb0295ead274d98ac4f9ea`，Server `e7c789cb36eb1cce3330de9afea36d18da2ada2a`。均包含本次发布前获取的各自 main。

在 EC2 18.119.132.34 上使用 KVM，Gateway API 36 与 Host API 29 两台 Android 模拟器持续运行，ADB 操作验证；未在每个用例冷启动。全部最终用例由普通运行进程执行，无调试器暂停或放宽产品超时。仅“租约停止续期”用例在音频验证后有意暂停两端应用，验证服务端看门狗及时回收。

| 验证项 | 结果 |
| --- | --- |
| Host 实际 UI 开关选择 WebRTC，呼出、来电接听各持续超过 95 秒，双向测试 PCM | PASS |
| 静音/恢复、DTMF、挂断、拒接、主叫取消、无接听前音频 | PASS |
| SIP 端点不可用时拒绝拨号、零蜂窝拨号、保留 Host 来电监听 | PASS |
| TURN/UDP 中继、屏蔽 UDP 后 TURN/TCP 中继，双向音频 | PASS |
| 停止租约续期后 SIP RTP 看门狗恢复；媒体链路中断结束且不回落 SIP、不重复蜂窝拨号 | PASS |
| 三个独立服务端、独立网桥：A→B→C，B/C 实际 WebRTC 呼叫 | PASS |
| A 恢复后 C 通话保持当前节点，挂断空闲后自动回切 A | PASS |
| 关闭实际 UI WebRTC 开关，传统 SIP 来电拒接/取消事件回归 | PASS |
| 历史签名匹配，Gateway Magisk 内 APK 与独立 APK 一致 | PASS |
| 物理蜂窝通话音频采集/注入、真实手机兼容性 | 待真机测试 |

测试音频后端使用 16 kHz PCM（Host 500 Hz、Gateway 900 Hz），仅通过测试进程依赖注入替代物理音频硬件。呼叫服务、SIP/Asterisk、原生 ICE/DTLS-SRTP/Opus、Android Telecom 和虚拟 modem 均真实运行。该结果不代表已经验证物理 modem HAL 音频。传统 SIP 的本次兼容回归范围为呼叫事件；真实 SIP 蜂窝音频仍需要手机测试。

切换复测发现并修复 Host 旧节点异步清理误停止新节点通话服务的竞态，含两项阻塞工作队列回归测试；另外修复拨号授权失败错误停止既有来电监听的问题，并完成真实 SIP 端点不可用的拒绝回归。最终序列中两台模拟器保持原进程，Gateway 应用保持原进程。

构建包内较早日期的组件验收记录保留其构建时状态；本次联合验收以本报告及对应 JSON 为准。

详细 JSON 结果和仅含媒体状态的三端日志见 [JOINT-TEST-EVIDENCE.tar.gz](https://github.com/KiriKira/gsm2sip/releases/download/debug-webrtc-joint-20261019/JOINT-TEST-EVIDENCE.tar.gz)；签名/源码/CI 对照见 [final-signatures.json](https://github.com/KiriKira/gsm2sip/releases/download/debug-webrtc-joint-20261019/final-signatures.json)。端口、Docker 和手机测试步骤见 [INSTALL-AND-PORTS.md](https://github.com/KiriKira/gsm2sip/releases/download/debug-webrtc-joint-20261019/INSTALL-AND-PORTS.md)。

[本仓库可安装 Release](https://github.com/KiriKira/gsm2sip/releases/tag/debug-webrtc-joint-20261019)。
