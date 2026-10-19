# 熄屏与同版本备用切换验证

状态：PASS。Host `918ac34702778830b4a6441695238cf060e17e3c`；Gateway `740ca46bdf60fdae71d1059ccd6fa75513467a93`；Server `751f1a1540c62102811ae56fe3106c556e15f226`。

本批修复 Go/RTC 轮询缺少 CPU 唤醒锁的问题。按三端一起升级、备用节点运行相同服务端版本验收；所有源码包含复核的 main。

- Android 系统回读未充电、真实熄屏待机 79.24 秒后，虚拟来电通知和实际拒接按钮通过。
- 再次熄屏来电，实际通知接听、熄屏通话 81.03 秒、双向测试 PCM 连续增长、70 ms 延迟/23 ms 抖动/3% 丢包、实际通知挂断通过。
- 空闲时无法访问 A，约 55.66 秒自动选择 B；B 上实际 Go/RTC 通话、双向测试 PCM和挂断通过。恢复 A 后继续按健康策略处理，较早同版本联测已覆盖空闲回切。
- 三个节点使用本批公开 CI Docker 的精确 amd64 摘要；amd64/arm64 镜像构建与部署冒烟均通过。两台 KVM 模拟器持续运行；熄屏用例中应用和虚拟机 PID 保持不变。
- Host 本地 205 项单元测试与 lint 通过，包含唤醒锁到期、释放和节点归属的回归测试。CI 状态和原签名核验见附件。

测试 PCM 仅替换模拟器缺少的蜂窝音频硬件；原生 ICE/DTLS-SRTP/Opus、Android Telecom、虚拟 modem 和三端服务真实运行。真机 HAL、厂商电池策略和强制深度 Doze 尚未验收。跨版本混用和整体迁移不纳入本批发布。

本批按用户范围只在空闲时模拟主节点不可达；通话中只注入链路延迟、抖动和丢包。早先界面焦点和日志解码失败记录保留；本批最终结果以这两个同版本候选验收报告为准。

## 下载

- [Gateway APK / Magisk](https://github.com/KiriKira/gsm2sip/releases/tag/debug-webrtc-screenoff-20261019)
- [Host APK](https://github.com/KiriKira/gsm2sip-client-android/releases/tag/debug-webrtc-screenoff-20261019)
- [Server / Docker](https://github.com/KiriKira/gsm2sip-server/releases/tag/debug-webrtc-screenoff-20261019)

两个 APK 沿用原签名，可直接覆盖安装。端口、镜像摘要、CI 和三端日志关联见 Release 附件；原有 SIP 兼容保留，跨版本混用与整体迁移不纳入本批。
