# PLAN — gsm2sip 双 SIM 网关 v1

更新：2026-10-03。基于 [main 提交 477b7e3](https://github.com/KiriKira/gsm2sip/tree/477b7e349891928dda7b87a9b55b72c52e7eab87) 审查；以下功能均为待实现。此计划取代旧版，旧版保留在 Git 历史。\n\n协议以 [三端协议 v1](https://github.com/KiriKira/gsm2sip-server/blob/main/docs/protocol-v1.md) 为唯一权威，联合次序见 [roadmap](https://github.com/KiriKira/gsm2sip-server/blob/main/docs/roadmap.md)，源码差距见 [审查报告](docs/REVIEW-2026-10-03.md)。实现开始时在依赖记录锁定server协议提交；此仓库不复制wire protocol。

## 目标边界

把本仓库保留为旧手机端 rooted Android gateway：两张实体 SIM 留在该机，完成蜂窝短信收发、呼入/呼出与 RTP 媒体桥接。server 与未 root 主机 app 各有独立仓库。旧手机不提供任何公网 root/ADB/HTTP 控制入口。

| 功能 | v1 通道 |
|---|---|
| 旧手机 ↔ VPS 通话信令 | SIP/TLS，验证证书链与主机名 |
| 旧手机 ↔ VPS 通话媒体 | 仅 SDES-SRTP AES_CM_128_HMAC_SHA1_80，VPS 锚定 RTP |
| 网关短信、SIM 绑定、状态、事件和命令 | HTTPS JSON；WSS 仅作通知，断线后从 HTTPS 补齐 |
| 旧 SIP MESSAGE 代码 | 保留为迁移/基线测试；v1 生产命令处理器禁用，不得与 HTTPS 同时执行 |

明确限制：每个 gateway 最多一条桥接通话；两张 SIM 都可收短信；DSDS 第二卡在另一张卡通话期间是否可接电话由基带、ROM、运营商决定，先实测后描述。

## SIM 绑定与 fail-closed 规则

服务端分配稳定 UUID sim_id；gateway 保存本地映射：

sim_id -> 当前 subscriptionId、slotIndex、PhoneAccountHandle、身份核验状态

mapping_revision 是 gateway 级递增配置版本。subscriptionId 在同一设备、同一张卡通常可跨重启稳定，但不能作为跨设备或永远有效的 SIM 身份；slot 也不能作为身份。重启时重新读取并校验当前映射，校验有效则保留；换卡、移槽更新或恢复出厂后按规则增加 revision/重新确认。特权 ICCID 如可用只在本机保存加盐指纹，绝不上传明文 ICCID/IMSI；无法可靠核验时提示旧机本地确认。

执行任何 SIM 定向任务前，校验 sim_id、mapping_revision、当前 active subscription 和身份核验状态。未知卡、SIM 缺失、旧 revision、PhoneAccountHandle 不匹配、SMS Manager 创建失败均返回 SIM_MAPPING_CHANGED 或 SIM_UNAVAILABLE，并生成状态事件；绝不能使用系统默认 SIM。

入站电话用 Telecom Call.Details.accountHandle 反查 sim_id；出站电话把映射到的 PhoneAccountHandle 显式传入 TelecomManager.placeCall。不得依赖默认语音线路，也不得在线路解析失败后走不带 account 的 ACTION_CALL。

入站 SMS 使用 SMS_RECEIVED 提供的当前 subscription 元数据映射到 sim_id。若 Android/OEM 广播无法核验线路，仍保存原文和事件，但记录 sim_id=null、sim_resolution=unknown；主机不提供原卡回复/回拨快捷操作。

## 呼叫通路任务

呼出：主机先向服务端创建短时 call-intent；主机 SIP SDK 发起消费该 intent 的 SIP INVITE；服务端验证 client 身份与 intent 后，剥除客户端自带的 X-* 路由字段，由服务端生成可信 X-GSM-Protocol-Version、X-GSM-Call-Id、X-GSM-Sim-Id、X-GSM-Mapping-Revision。gateway 只接受已认证 VPS SIP endpoint 上的可信字段，校验号码、SIM 映射、并发与业务 call_id 幂等账本后（intent/TTL由server消费验证，不要求gateway读取server私有token）用指定 PhoneAccountHandle 拨号。

呼入：gateway 按 PhoneAccountHandle 判定 sim_id，在本地持久化业务 call_id 后发送 SIP INVITE，附带 sim_id、mapping_revision 和服务器规定的关联标识。gateway 先让蜂窝侧继续振铃；仅服务端将路由交给在线主机并且 SIP 主机侧真正接听后，才应答蜂窝腿。CANCEL、主叫挂机、过期 push、第二通话忙线与本地拒接要收敛到同一通话状态。

DTMF 是 v1 呼叫验收项。当前 SDP 声明 telephone-event/8000，但 RTP 接收与解码路径未处理 payload 101。实现 RFC4733 事件解析/转发，并将远端蜂窝方向的数字通过当前 Call.playDtmfTone()/stopDtmfTone() 发送；同时处理事件结束位、持续时间、重复包、结束后停音。分别使用两张 SIM 实测 IVR/语音信箱；SIP 日志里出现事件不能代替远端电话确实收到按键。

## 短信、事件与命令实现

入站：SMS_RECEIVED -> 事务性持久化 inbox 项和单调 sequence event -> HTTPS 批量上传 -> 等待服务端将 inbox 与 event outbox 同一数据库事务提交 -> 收到逐 event_id durable ACK 后才将 event 标成可清理。WSS 断线、手机重启或 HTTP 超时均复用原 event_id；不按正文/发送者时间窗口猜测重复。容量达上限时停止清理旧事件、告警并保留可恢复数据。

出站：主机创建短信任务；gateway 通过 HTTPS claim 得到 message_id、sim_id、mapping_revision、号码、原文、TTL 和幂等字段；先在 SQLite/Room 持久化 command ledger，再解析并确认指定 SIM，然后持久化 dispatching，最后调用绑定 validatedSubId 的 SmsManager。Android 31+ 使用 createForSubscriptionId；较旧 API 用对应的 subscription-bound 方法；异常不得 catch 后回退默认实例。

逐短信分片按 message_id+part_index 幂等 upsert SENT 与 DELIVERED 回执，重复 PendingIntent callback 不累加分片成功数。若 app 在持久化 dispatching 前崩溃，可在核验后再试；若重启时 ledger 已处于 dispatching，则 modem 是否接到 binder 调用未知，标 unknown 并上报，禁止自动重领/重发整条短信。终态与事件由 HTTPS 上报；服务端提交后回 durable ACK。超过 TTL 的未发送命令标 expired。

现有 SmsStore / SmsOutbox 是 SharedPreferences JSON 队列，入站队列超 200 会静默删旧消息；SmsOutbox.add() 只留最后 200 条，remove()/prune() 又会删除 message_id。GatewayService.onSmsSendRequest() 仅用当前 outbox 做重复检查，所以行被清理后重放同一个 ID 有再次发出的风险。迁移时把正文/回执清理和幂等 tombstone 分开；未完成项绝不因容量限制淘汰，已接受 ID 至少保留到服务器不再重放。以上旧存储不满足 journal/ledger 语义。避免在进程同时使用旧 SIP MESSAGE 与新 API command 触发双发送。

## 音频和 Android 权限

当前媒体实现依赖硬件 Audio HAL：VOICE_CALL/VOICE_DOWNLINK/VOICE_RECOGNITION AudioRecord、AudioTrack playback usage 与 tinymix profile。CAPTURE_AUDIO_OUTPUT、Telecom 控制和 appops 需要特权安装/root 配置。Root 本身不保证 Audio HAL 有 Telephony Tx/Rx 路由。

目标机型先跑 tools/check-device.sh，并登记型号、SoC、ROM、Android、内核、两张卡运营商/制式、自动识别的 profile、RTP 音频 capture/playback。先单卡验证再分别在 SIM1/SIM2 上测试入站与出站、双向音质、静音/回声、网络抖动与长通话；任一方向不通即阻止该机型标记双 SIM 可用。

仓库 README 当前记录的音频证据是 Poco X3 NFC（SM6150/SM7150）及 Galaxy S4 Mini（MSM8960）实机通话可用，Exynos S10e 不可用；这些记录不是本项目目标旧手机的验证结果。对尚未记录的设备，只能报告未知，不能按“已 root”或“Qualcomm”推定通过。

保留旧计划里移除 PermissionController 全局隐藏、不要覆盖用户手动 Magisk deny、不要全局取消 SMS 发送限额和不要改默认短信应用已读状态等要求。所有发送速率限制由 server 和 gateway 独立执行，默认按 sim_id 限制，并提供明确审计记录。

## 实施阶段与验收

### G0 — 对齐三端协议与设备门槛

- 移除新装界面的 callagent.pro 与非必要第三方 STUN 默认值。
- 生产默认 SIP/TLS + 强制 SRTP；缺证书验证或 SDES 协商就拒绝呼叫，不得静默回明文 RTP。
- 固定协议版本引用；把本仓库之外的 server/client 工作留在各自仓库。
- 锁定目标旧手机及 ROM；完成 check-device.sh 和音频路由预检。

验收：新安装不会连接未配置的第三方服务；候选手机身份与设备检查结果可复现。未知硬件继续标记未验证。

### G1 — 稳定 SIM 映射与定线

- 建立本地 sim_id/mapping_revision 存储与变更监听、确认流程。
- 入站 Call 使用 PhoneAccountHandle 归属卡；入站 SMS 解析订阅来源或明确标 unknown。
- 出站 Call 与 SMS 都只按经校验 sim_id 指定线路；移除对 slot、subId、系统默认线路的静默 fallback。
- 全设备最多一个 call ledger 活动项，并与 Telecom Call、SIP dialog 的挂断/失败/超时状态一致。

验收：两张 SIM 分别进行入站/出站拨号、收发短信；故意移卡、换卡、禁用订阅、发送旧 mapping_revision 时必须拒绝错线而非回退。目标机重启后映射有效时仍可继续，不要求无理由轮换 UUID。

### G2 — 安全可靠的数据面

- 新建 Room/SQLite gateway store：event journal、sequence、message inbox、command ledger、每分片 SENT/DELIVERED 状态，以及与正文保留分离的幂等 tombstone。
- 实现 HTTPS 批量 event ingest、逐事件 durable ACK、HTTPS command claim/幂等和 WSS wakeup；不把 Asterisk SIP response 当 DB ACK。
- 定义容量、回压、恢复和隐私保留；SMS 正文/凭据不写普通日志，Android backup 关闭并保护本机凭据。
- 旧 SIP MESSAGE 仅由迁移选项显式开启，生产模式不能消费它。

验收：服务端提交前后分别杀进程/断网/重启，重试保持 event_id 和 message_id；同一个已完成或已过期 ID 重放也不能二次发短信；重复分片 callback 不会虚增成功计数；modem 状态未知时不自动重发；队列满会告警而不淘汰验证码或未完成命令。

### G3 — SIP/TLS/SRTP 与协议正确性

- 保留证书链、主机名、SNI 校验；生产强制 TLS 和 SRTP，禁止 RTP fallback。
- 修复 SipTransport 按字节处理 Content-Length；覆盖分段 TCP header/body、中文多字节跨 read、多个消息合并及 SDP。
- 解析服务器授予的 registration Expires/Contact expires。
- 校验 PJSIP 实际互通、可靠 NAT anchoring、SIP call intent 与 gateway call ledger 的重传/取消/挂断一致性。
- 添加 RFC4733 telephone-event 处理及 Telecom DTMF API 转接。

验收：目标 Asterisk/PJSIP 与 SIP SDK 完成正确 TLS、SDES、SDP、挂断、DTMF；错证书、缺 SRTP、任意未授权 SIM 或过期 intent 均拒绝。中文短信由 HTTPS 原样收发，不再依赖 SIP TLS SMS framing。

### G4 — 双 SIM 实机端到端

- 卡 A、卡 B 分别测试真实蜂窝呼入/呼出、中文/emoji/多段 SMS、原卡回复、按键菜单与语音双向。
- 一卡通话时测另一卡 SMS 与来电，记录基带/运营商 DSDS 结果。
- 测槽位互换、换卡、恢复出厂后重配、subId 保持与变化、SMS 广播订阅 extra 缺失。
- 测长通话、热机、锁屏、Doze、Wi-Fi/AP 重启、服务端证书更新、设备重启，以及双端事件重放竞态。

验收：所有任务能显示明确 SIM 与业务状态；旧 revision、未知身份、SIM unavailable 都可解释地失败；audio profile 和双卡限制写入 docs/devices.md。只有两卡均通过才声明该设备完整支持双 SIM 语音/短信。

## G5 — 保留旧计划的协议、系统权限与发布工程任务

- `sip/SipTransport.kt`：byte buffer + Content-Length有界解析；拒绝重复冲突/负数/过大长度和无界积累，测试ASCII、中文/emoji跨read、SDP、流水多帧、CRLF keepalive、异常帧。
- `sip/SipAuth.kt`、`SipClient.kt`：补qop=auth、cnonce/nc、401/407、nonce生命周期及支持时SHA-256；REGISTER按服务端 granted Expires/Contact expires刷新（覆盖300/600/1800/3600秒）。与server配置共同验收，不能单独开启不支持的digest。
- `sip/SipCall.kt`、`rtp/Srtp.kt/RtpSession.kt`：生产strict SDES拒绝缺crypto/错误suite，鉴别成功后才更新symmetric RTP来源，增加SRTP密钥派生/重放/rollover测试；RTCP若启用必须补齐SRTCP并与Asterisk核对。G.722/PCMA与telephone-event payload按SDP协商而非硬编码。
- `gsm/GsmCallManager.kt`、`bridge/CallOrchestrator.kt`：显式PhoneAccountHandle、call ledger在placeCall前持久dispatching；重启无法确认未拨出的call_id→unknown，不自动重拨。控制SIP重传和第二卡来电不得覆盖另一Call对象。
- `sms/SmsSender.kt/SmsSendReceiver.kt`：API31+用createForSubscriptionId，API26–30用相应subscription-specific旧API；不catch异常后回默认。PendingIntent以message_id/part_index/type建立稳定唯一身份（如显式data URI），不用可能碰撞的hash requestCode作为唯一标识；callback逐分片upsert幂等。
- `sms/SmsStore.kt/SmsOutbox.kt`与新store：SharedPreferences迁入Room/SQLite事务，迁移失败保留旧数据；正文清理与幂等tombstone分离，不能takeLast(200)或24h prune让已执行ID重新可执行。盘满需告警/回压、记录无法落库的状态，不能宣称无条件不丢短信。
- `magisk/install.sh,service.sh,system/priv-app/PermissionController/.replace`：移除全局隐藏PermissionController、自动改Magisk deny为allow、全局短信无限额和默认修改其它SMS App通知/已读的行为。采用gateway UID定向权限/AppOps/必要SELinux与audio profile；先做profile前后对照，不用全面权限削弱代替适配。
- `app/build.gradle.kts,build.sh,magisk/`与新CI：专用且持久的release key、固定applicationId，连续release升级签名校验；APK与Magisk中的APK签名一致，迁移原debug签名有一次安装/备份恢复说明。私钥/密码只放受控secret，不进git。
- `magisk/tinycap,tinymix,tinymix32,tools/tinymix/`：保留可审计tinymix源码，tinycap由固定AOSP/tinyalsa源构建；记录所有native源码pin/hash/ABI，不发布来源不明预编译文件。arm64先行，32bit仅目标旧机需要且验证后支持。
- `service/GatewayService.kt/BootReceiver.kt/RootShell.kt`：心跳、独立root/SIP/SIM/audio/温度/空间健康，网络退避重连和分组件watchdog；不因单次失败反复重启。用户撤销root明确显示不可用，不自动改权限。
- `docs/devices.md`、README：记录实际旧机型号/ROM/两卡/数字音频与DTMF测试、DSDS和SRTCP能力；将本fork推荐部署入口改为server仓库PJSIP计划，不再推荐旧chan_sip/callagent默认。
- 发布前确认原网关与引入依赖的许可证/分发权限；server和client保持独立原创工程。现有无repo级LICENSE不等于可以自行给上游代码重新授权。

验收：CI可编译/JVM协议与ledger测试/native重建/校验APK签名与Magisk包；目标机重启、断网、TLS证书轮换及24h熄屏+72h持续运行通过。测试前后权限变化可审阅、连续release可升级、两卡调用都不默认回落。

## 建议新增代码位置与依赖

新增`sim/SimRegistry.kt`、`data/GatewayDatabase.kt`（journal/ledger/parts/tombstones）、`net/ControlApiClient.kt`、`net/ControlEventsClient.kt`、`service/HealthReporter.kt`，保留现有Telecom/media实现并小步改造。优先执行联合M0设备探针与G0/G1/G3基础修复，之后按联合M2–M5对接；G5中的安全发布项是生产门槛，不能等正式上线后再处理。

SIM account映射优先使用目标Android版本支持的Telephony/Telecom关联API；API26–30与API31+分支分别验证，不把PhoneAccountHandle.id字符串解析成subId的猜测作为可靠映射。通话中能否发送SMS按两卡各自实测能力声明，未验证时留原任务/原卡/原TTL等待，不自动切卡。
