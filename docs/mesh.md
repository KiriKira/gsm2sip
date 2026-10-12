# 网关多节点（mesh）

总体设计见 server 仓库 [docs/mesh.md](https://github.com/KiriKira/gsm2sip-server/blob/main/docs/mesh.md)。本文只说明旧手机一侧的实现。

## 主节点与其他节点

- **主节点**：原来的配对（设置 → 中转服务器）。SIM 的本地确认只在主节点完成一次，主节点
  分配的 `sim_id` 就是所有节点共用的全局 SIM ID。
- **其他节点**：设置 → 其他节点 → 添加节点，填写该节点地址和它生成的 gateway 配对码。要求该
  服务器在 `/v1/capabilities` 中声明 `mesh`。每个节点的凭据单独加密保存（`SessionVault`），
  与主节点互不影响。添加后自动用 `proposed_sim_id` 把主节点已确认的 SIM 绑定过去，无需再确认。

## 保证如何实现

| 保证 | 实现 |
|---|---|
| 每条入站短信送到所有节点 | `recordIncomingSms` 写主节点事件的同一事务里，为每个节点写一份副本事件（相同 `message_id` 与 `sim_id`，各自的事件 ID 与序号）。副本在首次上传前填入该节点的 SIM revision 并冻结；节点尚未绑定该 SIM 时按序等待。各节点分别确认，主节点确认不影响副本正文。 |
| 同一条出站短信最多执行一次 | 命令账本 `message_id` 唯一。其他节点为同一 `message_id` 下发的命令若内容哈希（`sim_id`、号码、正文）一致，记为 `command_aliases`，只把原执行的 dispatching/分片/终态镜像为该节点自己的命令事件，永不再次调用 modem；内容不同则拒绝。 |
| 发送限额 | 每 SIM 的分钟/小时限额按全局 `sim_id` 统计，所有节点合计。 |
| 节点 SIM 失效 | 节点心跳撤销 SIM 或返回 `SIM_MAPPING_CHANGED` 时清空该节点绑定，下一轮从主节点已确认 SIM 重新绑定；节点命令的 revision 必须等于该节点当前 revision，并在本地按主节点 revision 解析同一张物理 SIM，否则失败关闭。 |
| 节点互不阻塞 | 每轮同步先处理主节点，再逐个处理其他节点；任一节点失败只记录在该节点状态里。主节点不可达时其他节点照常同步。 |

## 通话（第二阶段）

- 主节点启用语音后，每个其他节点在同步时自动开通自己的 SIP 端点（`/devices/self/sip-config` +
  `sip-credentials/rotate`，重试键先落盘），凭据按节点加密保存（`MeshVoiceStore`）。语音运行时
  为每个节点建立独立的 SIP/TLS 注册（`MeshVoiceClients`），节点心跳上报该节点自己的
  `sip_registered`，所以主手机在哪个节点上都能看到“可通话”。
- **来电**：GSM 来电同时经主节点和每个已注册、已绑定该 SIM 的节点发出 INVITE（相同 `call_id`，
  各带该节点的 SIM revision）。最先接通的一路胜出，其余用 CANCEL 取消；若取消与对方的 200 交叉，
  先 ACK 再 BYE，不留下悬空对话。某一路失败而其他路仍在振铃时不结束通话。
- **去电**：任一节点授权的 INVITE 都会被接受；节点授权的 revision 必须等于该节点当前绑定，
  再按主节点 revision 解析同一张物理 SIM。通话账本以 `call_id` 唯一，同一通电话经其他节点
  再到达时不会第二次拨号（主手机在多节点配置下自行铸造 `call_id`）。
- 无论经过几个节点，旧手机同一时间只桥接一通电话；忙时返回 486。

## 当前限制

- 其他节点目前只走 HTTPS 轮询（与控制循环同周期，约 30 秒）；主节点仍有 WSS 唤醒。
- 来电在每个节点各发一路 SIP，会占用各节点的 SIP/TLS 连接；经 cloudflared 的节点无法承载 SIP。
- 移除节点后，尚未上传到该节点的副本保留在本地，不会转给其他节点（其他节点已各有一份）。
