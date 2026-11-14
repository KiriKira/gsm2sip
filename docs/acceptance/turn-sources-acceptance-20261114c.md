# 更正与补充：R1 端口统计、来源残留、以及审计发现的问题（2026-11-14 第三轮补充 C）

本文件更正 `turn-sources-acceptance-20261114b.md` 中**证据无效**的部分。结论方向未变，但原证据必须作废重述。

---

## 0. 对更正文件 v1 的一处撤回

本文件 v1 第 3 节曾写“**代码里没有 `node_servers_configured` 这个字段**，该推断撤回”。**那条撤回本身是错的，现再撤回。**

错误原因：核对时三个仓库都停在 `plan/acceptance-report`（放报告的）分支，而不是被测提交 `c67f380`/`ba8b087`，在报告分支上自然搜不到产品代码里的字段。

在正确提交上该字段确实存在（`RelayGatheringDiagnostics.kt`）：

```kotlin
private val nodeSources = if (nodeServers.isEmpty()) 0 else 1
private val nodeServerCount = nodeServers.size
...
" external_sources_selected=$externalSelected node_sources_configured=$nodeSources"
+ " node_servers_configured=$nodeServerCount node_enabled=$nodeEnabled selected_turn_urls=$selectedTurnUrls"
```

即 `node_sources_configured` 是“节点是否提供了 server”（0/1），`node_servers_configured` 是 `nodeServers.size`（server 条目数，不是 URL 数）。

实测一条正常通话的诊断行（当前安装 `0deacf46`/`bf649c57`）：

```
event=relay_no_local_candidates reason=turn_candidates_unavailable force_relay=true local_candidates=0
external_sources_configured=0 external_sources_enabled=0 external_sources_selected=0
node_sources_configured=1 node_servers_configured=2 node_enabled=true selected_turn_urls=2
```

**字段工作正常，节点 TURN 也正常进入了配置。** 之前 P3probe 里出现 `node_servers_configured=0 ... selected_turn_urls=0`，是那一刻确实没有节点 TURN（绑到无内置 TURN 的 nodeB 且无外部来源），属真实状态，不是字段故障。

---

## 1. 作废：R1 的 `port_residues` / `all_released` 是假的

### 现象

`r1_quota2.py` 的 `node()` 用 f-string 拼接 shell：

```python
subprocess.run(f"sudo docker exec {NODE} sh -c {json.dumps(cmd)}", shell=True, ...)
```

`json.dumps` 只加双引号，**双引号内的 `$2` 仍由外层 shell 展开**。传入的 `awk 'NR>1{print $2}'` 实际执行为 `awk 'NR>1{print }'`。

### 复现

同一份 `/proc/net/udp` 样本：

```
buggy  output: ' 00000000\n 0100007F\n'
buggy  count : 0
correct ports: [25693, 49187]
```

即原实现**恒返回 0**。因此上一轮报告里的：

- `port_residues = [0,0,0,0,0,0,0,0,0,0]`
- `all_released = True`

**不是测出来的结果，是"永远等于 0"的产物，一律作废。**

第二个缺陷：它按端口范围扫全部 socket，不区分持有者。本机 49180 属于模拟器 `netsimd`（连 `142.251.185.188:5228`），会被当成中继端口。

### 重做：`lab/relay_ports.py`

- 命令以 argv 传入，不经外层 shell；
- 端口范围从容器环境变量 `TURN_RELAY_MIN_PORT/MAX_PORT` 读，不硬编码；
- **只统计 coturn(pid 29) 自身 fd 指向的 socket**，外部进程不计。

### 重测结果（`b01f6568` 节点，单通强制中继外呼）

| 时点 | coturn 拥有的中继端口 | 说明 |
|---|---|---|
| 通话前空闲 | `[]` | 无残留 |
| 通话中 | `[49247, 49255, 49161, 49188, 49189, 49220]` | 6 个，`relay/relay` 接通（4640 ms） |
| 通话后 | `[49161]` | 其余 5 个已释放 |

**残留的 49161 是否泄漏：不是。** 核查其 socket：

```
local  <lab-host>:49161   remote 0.0.0.0:0   st=07   uid=0   inode=1948746
owner  turnserver -c /run/gsm2sip/turn.conf  (pid 29)
```

远端 `0.0.0.0:0` + `st=07`（CLOSE）是 coturn 在最小端口上常驻的**空闲监听 socket**（first-relay-port 预绑定），60 秒空闲采样稳定存在、与通话无关。

**改正后的结论**：通话期间分配的 6 个中继端口在通话结束后**全部回收**，空闲态只有 1 个 coturn 预绑定的监听端口。原报告"端口残留 0"的方向正确，但原证据作废。

---

## 2. 需要补充说明：C5 的 `c5-many` 未持久化

上一轮报告 4.6 写"表单接受并接通"，但同时承认"该来源未出现在列表里"。这两句并列容易被读成通过。

准确说法：**40 个地址的表单提交被 App 接受、未崩溃、通话正常；但该来源没有落盘到来源列表，因此"超 32 截断"这条路径实际没有被真正验证过。** 记为未验证，不计通过。

---

## 3. 审计提出的其余问题：核实结论

| 项 | 核实结果 |
|---|---|
| `p_prep2.py` 未按时间戳排序 | **代码属实**。实测 logcat 乱序 4 对/7810 行，全部在 200 ms 内、不跨会话；归档证据 72 侧 `relay_sources_resolved` **全部仅 1 条**且在 `selected` 之前。因此本轮 P 组数值不受影响，但排序是该修的潜在缺陷。 |
| `relay_sources_resolved` 可能一通多条 | 本轮 72 侧全部 1 条，无重复。是否因预取+正式而重复：当前代码预取走 `resolve()` 不打点，正式走 `resolveForCall()` 才打点，故单 session 应为 1 条。 |
| `p_prep2.py` 呼叫结束后才 dump 全缓冲 | **成立但未发生**：本轮通话短（≈5 s），缓冲未滚动，72 侧均取到数据。长通话或高频通话才有丢行风险。 |
| `install_pair.sh` SHA 不匹配只警告不退出 | **成立**。本轮安装未使用该脚本，改用 adb 返回码 + `pm list instrumentation` + 设备侧 `sha256sum` 三重确认，且已发现并纠正一次错装（见第 4 节）。 |
| `acceptance-build.yml` manifest 断言靠正则子串 | **成立**（CI 日志里 `Raw:` 前缀可见），属测试工具质量问题，不影响产物。 |
| `r1_quota2.py` 跨 host/gateway 时钟相减 | **不成立**：`rows` 在 `for role` 内重建，P 的三处 `dms()` 均用 host 锚点与 host 事件。 |
| `FORCE_RTC_RELAY` 用 `buildconfig.py` 读取 | **该脚本在本环境失效**（androguard 路径返回 `None`）。已改用 jadx 复核。 |
| 读产品代码时的分支 | **已犯过一次错**：在 `plan/acceptance-report` 分支上搜字段，得出"字段不存在"的错误结论（见第 0 节）。读被测实现必须 `git show <被测SHA>:<path>` 或先切到被测提交，不能在放文档的分支上搜。 |

---

## 4. 本轮自查发现的一次错装（已纠正）

为 E1 还原 PR 版时，我先按文件名装了 `apks/hr-relay-app.apk` / `apks/mr-relay-app.apk`，设备侧 sha256 为 `2e359ad4…` / `f6e27f9d…`，**与本轮 CI 产物不符**。

根因：实验机 `apks/` 目录里 `hr-*` / `mr-*` 是**上一轮**（11-14 06:08 / 08:00）的文件；本轮 CI 产物在本地 `apks-v3/named/`。

已重新部署并逐字节核对：

| 设备 | 包 | 设备侧 sha256（前 16） | 对应本地文件 |
|---|---|---|---|
| emulator-5554 | com.callagent.gateway | `0deacf4631fcb298` | `apks-v3/named/homura-relay-app.apk` |
| emulator-5556 | com.callagent.host | `bf649c57e3453e14` | `apks-v3/named/madoka-relay-app.apk` |

runner 亦重新确认：`GatewayJointInstrumentation` / `HostJointInstrumentation`。

---

## 5. `FORCE_RTC_RELAY` 复核（方法更正，结论不变）

上一轮报告第 2 节写"用 androguard 读 dex 中 BuildConfig 的类静态初始值"。该脚本在当前环境读不出值（返回 `None`）。改用 jadx 反编译后直接读源码：

```
com/callagent/gateway/BuildConfig.java:  public static final boolean FORCE_RTC_RELAY = true;
com/callagent/host/BuildConfig.java:     public static final boolean FORCE_RTC_RELAY = true;
```

同时 `APPLICATION_ID` 分别为 `com.callagent.gateway` / `com.callagent.host`，与设备上的包名一致。**结论（强制中继版本）不变，方法与证据类型更正为 jadx 源码级确认。**

---

## 6. 待办：需要在下一轮修掉的测试工具问题

按优先级：

1. `r1_quota2.py`：`node()` 改为 argv 传参；端口统计改用 `relay_ports.py`；采集失败必须报错而非返回 0。
2. `r1_quota2.py`：`both_relay` 应断言 `== "relay/relay"`（现在 `startswith("relay/")` 会放过 `relay/host`）；`all_released` 缺数据应返回 unknown 而非 true。
3. `r1_quota2.py`：`count_486()` 用 30 分钟滚动总数差分，旧记录退出窗口会抵消新错误；应改为按本轮日志窗口统计明确错误字段。
4. `p_prep2.py`：事件提取改为"先收齐 → 按完整时间戳排序 → 取首次"；`relay_sources_resolved` 多于 1 条时保留全部并标 `ambiguous_resolution`。
5. 两份脚本：改为通话前启动持续 `logcat` 落盘、结束清理后停止，所有解析读固定文件，避免缓冲滚动与两次快照覆盖范围不一致。
6. `install_pair.sh`：SHA 不匹配/缺 runner/DIALER 失败必须中止；设备侧算 SHA，不 `exec-out cat` 大文件。
7. `p_prep2.py` 依赖的子脚本 `call_case_v2.py` 相位表需补 `INCOMING_RINGING`（外层已补，内层未补）。

以上均未修改产品代码，仅测试工具。
