# 三端实施状态

权威服务端协议固定为 `8b83ae8f613fd7f27ea8e4f3aa1b2148f22c1606`：
[wire addendum](https://github.com/KiriKira/gsm2sip-server/blob/8b83ae8f613fd7f27ea8e4f3aa1b2148f22c1606/docs/server-wire-addendum.md)、
[OpenAPI](https://github.com/KiriKira/gsm2sip-server/blob/8b83ae8f613fd7f27ea8e4f3aa1b2148f22c1606/openapi/openapi.yaml) 与
[fixture](https://github.com/KiriKira/gsm2sip-server/tree/8b83ae8f613fd7f27ea8e4f3aa1b2148f22c1606/fixtures/api)。
原计划仍是三个仓库的完整验收目标；首批完成 M1/M2 短信基础，后续按
用户要求增加 [Magisk 通用能力适配](magisk-runtime.md)，继续推进通话基础。

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

短信模式普通 APK 无需 root 或 Magisk：只申请 SIM/SMS 权限，控制服务启动、
本机 SIM 确认和心跳不查询语音账户，不请求默认电话角色。root 初始化及旧版
特权通知迁移只在显式语音启动时执行，网络详情也使用普通公开接口。

空闲服务在 Android 14+ 使用 specialUse FGS；开机只启动已配对控制服务，
不弹出 Activity 或强制录音授权。Magisk 不再改全局短信限额、su 自动授权、
其他短信应用通知或 PermissionController。发布包必须使用自有签名；
签名迁移与备份时须保留所有未决账本。

## 尚未完成

目标手机没有连接：实际双 SIM 发/收短信、回执、热插拔、断电、Android
开机/后台限制及 24h/72h 运行仍需验收。没有执行真实发送或计费拨号。
重复身份校验不等于 Android 的换卡与 modem 调用具备原子性。

M0 音频和 DSDS 尚未通过。语音选卡按实际精确账户关联支持，公开接口
不足时通过 Magisk 查询，不按机型或 API 31 整体拒绝。数字 capture/uplink
按系统端点与实际路由验证；Magisk 无法创建系统未提供的蜂窝音频接口。
主机内置 PJSUA2、Telecom、服务器 Asterisk ARI 编排与通话授权已落代码。
网关 SIP/媒体加固与呼叫账本仍需同实际蜂窝音频联合验收。

SIP 子集不支持 auth-int、Digest sess、DTLS-SRTP、SRTCP 或 rekey；
RFC 4733 桥接目前是接收 RTP 事件后向 Telecom 发送 DTMF。完整通话
必须按计划做取消/超时/重连、两卡音频及主机打断等联调。

两端现采用 [Material 3 Expressive 与后台运行设置](android-ui-and-background.md)。
WSS 唤醒、HTTPS 补齐、用户停止/开机恢复与主机后台短信通知已落代码；
FCM、完整切网媒体重协商、背压和留存管理尚待实施。旧机隔离事件/未知任务不会删除或
重发，需后续管理界面和保留策略。当前 debug APK 和本地 Docker Compose
均待真机验收，也没有部署公网生产服务。

完整缺口和网络场景见 [弱网恢复审查](https://github.com/KiriKira/gsm2sip-server/blob/codex/control-plane-foundation/docs/network-and-feature-status.md)。

## 本次短信恢复与凭据隔离

可选 READ_SMS 系统库恢复需要显式启用，默认从启用时间开始；全历史导入先预览再确认。分页 checkpoint、provider fingerprint 与事件同事务写入，未知 SIM 不猜卡。配对切换按 gateway/控制 URL 租约隔离，旧扫描不能写入新身份。网络刷新响应丢失保留原 token 与请求键；服务端仅恢复当前代际至自然到期。

SIP 密码和 CA 单独通过 Keystore 保存，服务器 bootstrap 可用同请求键恢复。普通短信控制不读取这些语音凭据，也不触发 Magisk；显式配置语音后仍需用户启动和设备能力检查。
