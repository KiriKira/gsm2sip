# 独立 Go/RTC 联合验收

> **0.4.0 起**：网关已改名 Homura，SIP/RTP/SRTP/STUN 代码全部移除，通话只走服务器 HTTPS 呼叫控制与 WebRTC。本文中有关 SIP、Asterisk、ARI 的内容是历史记录。

状态：PASS。Gateway `2ef24b8546314d28ce707756b250f23d3c4f5ad0`，
Host `0ededb1868253d863c66a0dcf00b225e668a1f82`，Server `f2dc1d162a5af8a8a0052194bdc4ea57b89a61da`。

三个节点使用本批 CI 正式镜像的 amd64 精确摘要，Asterisk 全部关闭。
真实 UI 出站长通话 105.78 秒、虚拟来电长通话
104.10 秒，双向测试 PCM、静音/恢复、DTMF、挂断、
拒接、主叫取消、接听前无 PCM、监听保留均通过。纯 RTC 抓包 SIP/ARI 为 0。
TURN UDP/TCP、Go 控制租约失效保留 unknown 占用、媒体失效清理、无静默 SIP
回落和无重复蜂窝拨号通过；纯模式两端未加载 PJSUA2。

A→B→C 自动备用选择、备用节点真实通话、主节点恢复时保持现有通话、挂机后
空闲回切通过。额外两个节点有独立 bridge/veth 网络。兼容测试覆盖实际开关
OFF 的 SIP 来电，以及新 Host 开关 ON、存在旧客户端时整通 SIP 来电；同时
证明保留 Asterisk 时，新出站 RTC 仍走 Go 控制。

实现过程中联测发现并修复了备用节点 SIP 注册残留、Host 节点可用性缓存和
RTC 引擎跨节点误复用；失败记录保留在实验环境，发布证据对应最终通过版本。
备份/恢复、Go race、Android 单元/lint、折叠屏 UI/原生兼容和多架构 Docker CI
状态见 `ci-results.json`，所有源提交包含发布前复核的最新 main。

逐项样本、真实呼叫 ID、三端日志关联、签名与摘要在附带证据中。
物理蜂窝 HAL 音频未在模拟器上宣称通过，最后由真实手机验收。

[本仓库原签名 Debug Release](https://github.com/KiriKira/gsm2sip/releases/tag/debug-webrtc-independent-20261019)。
