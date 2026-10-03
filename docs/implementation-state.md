# 首批三端实施状态

权威服务端协议固定为 `56f55f77a4840f0e6797a1765165db601f7c08b4`：
[wire addendum](https://github.com/KiriKira/gsm2sip-server/blob/56f55f77a4840f0e6797a1765165db601f7c08b4/docs/server-wire-addendum.md)、
[OpenAPI](https://github.com/KiriKira/gsm2sip-server/blob/56f55f77a4840f0e6797a1765165db601f7c08b4/openapi/openapi.yaml) 与
[fixture](https://github.com/KiriKira/gsm2sip-server/tree/56f55f77a4840f0e6797a1765165db601f7c08b4/fixtures/api)。
原计划仍是三个仓库的完整验收目标；本批只完成 M1/M2 短信基础。

## 已实现

HTTPS 配对、Keystore 加密且绑定控制端 URL 的令牌、本机逐卡确认、
服务器 `sim_id`/revision 与本地身份 barrier、30 秒轮询和失败退避、
SQLite 事件/命令/分片账本与 durable ACK 清理、订阅明确的 SmsManager、
每 SIM 持久化滚动 5/min 与 30/hour 预算、迟到回执及旧身份事件隔离。

modem 调用前先写入 dispatching。应用重启后不确定任务转 unknown，
不自动重发；发送前重复校验 SIM 身份与 revision，无默认卡 fallback。
准备分片后、尚未 dispatching 的任务可按同分片数安全恢复。
入站未知 SIM 显式上报 unknown，不猜卡；上游旧 SIP MESSAGE 执行器停用，
旧 outbox 只迁入隔离账本。SQLite WAL 主写连接明确采用 synchronous=FULL。

空闲服务在 Android 14+ 使用 specialUse FGS；开机只启动已配对控制服务，
不弹出 Activity 或强制录音授权。Magisk 不再改全局短信限额、su 自动授权、
其他短信应用通知或 PermissionController。发布包必须使用自有签名；
签名迁移与备份时须保留所有未决账本。

## 尚未完成

目标手机没有连接：实际双 SIM 发/收短信、回执、热插拔、断电、Android
开机/后台限制及 24h/72h 运行仍需验收。没有执行真实发送或计费拨号。
重复身份校验不等于 Android 的换卡与 modem 调用具备原子性。

M0 音频和 DSDS 尚未通过。通用 voice 选卡仅 API 31+，且还需要 OEM
capture/uplink 验证；API 26–30 的本批功能保留短信，语音报告不可用。
主机 SIP SDK、Asterisk ARI、完整 Telecom 和三端通话未实现。网关已有
SIP/媒体加固与呼叫账本，只是供后续 M3 的受限实现，不能作为已验收通话。

SIP 子集不支持 auth-int、Digest sess、DTLS-SRTP、SRTCP 或 rekey；
RFC 4733 桥接目前是接收 RTP 事件后向 Telecom 发送 DTMF。完整通话
必须按计划做取消/超时/重连、两卡音频及主机打断等联调。

WSS/FCM、通知、完整背压与长期留存尚待后续实施。旧机的隔离事件/
未知任务不会删除或重发，需后续管理界面和保留策略；主机本批仅前台
HTTPS 同步。当前 debug APK 和本地 Compose 都不是公网生产部署。
