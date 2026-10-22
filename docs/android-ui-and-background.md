# Android 界面与后台运行

## Homura 0.4 界面

0.4.0 起网关界面与主机 Madoka 统一：页面在代码中构建，共用同一套圆角分组列表样式（`ui/GatewayUi.kt`，与 Madoka 的 `HostUi` 保持一致）和底部导航。

- **状态**：网关名称与状态胶囊（未配对 / 短信在线 / 通话连接中 / 通话就绪），进行中的通话卡片（静音、监听、挂断），“完成设置”分步引导，后台同步与远程通话状态，移动网络与 Wi-Fi 详情，SIM 摘要和短信备份入口。
- **记录**：按日期分组的通话与短信记录，可按全部、呼入、呼出筛选；点短信查看详情；可清除记录。
- **设置**：服务器与配对、其他节点、SIM 卡绑定、远程通话（设备诊断、对方音量、通话时静音本机）、短信（补收、备份与归档）、后台运行与权限、关于与运行日志。

页面只构建一次，状态广播和定时刷新只重建变化的行，正在编辑的输入框不会失去焦点。旧版中的 SIP 服务器、端口、账号、编解码器、STUN、TLS/SRTP 设置以及无实际作用的“本机号码”与“发送前转写为 ASCII”选项已删除。

本次两端采用原生 Kotlin Views + **Material 3 Expressive**，依赖 Material Components **1.14.0**（实施时核对 Google Maven 的最新稳定版），使用 `Theme.Material3Expressive.DayNight.NoActionBar`。支持明暗主题、Android 12+ 动态取色，较早系统使用语义色板。Material 卡片、按钮、输入框、对话框及线路选择保留现有明确选卡、草稿与安全重试语义。按窗口宽度控制内容和系统栏/键盘 insets，不以机型判断布局。

构建使用 compileSdk 35、AGP 8.7.2、Kotlin 2.1.20、Gradle 8.9；minSdk 26、targetSdk 34。升级编译 SDK 用于满足新组件依赖，不把旧手机按型号排除。Compose 并非采用 Material 3 的必要条件；当前在现有 Views 上实现该设计体系。

## 后台同步与操作入口

用户可以查看后台运行、通知、电池优化、连接状态和最后同步时间，并进入系统通知或电池优化设置。前台服务显示常驻通知及停止按钮；应用内停止会持久保存，开机和系统重建不会自行覆盖。Android 13+ 任务管理器主动停止通过最近 `ApplicationExitInfo` 的用户停止记录阻止自动恢复，用户明确再次开启后才恢复。

WSS `/v1/ws` 只接收 `sync_required` 提示，不承载短信正文、执行命令或通话信令；随后用已认证 HTTPS 拉取权威数据。Bearer 仅放请求头，使用系统 CA 与 hostname 校验。断开后退避重连，并保留 HTTPS 定时补齐；回调必须符合当前配对身份和连接代次。网络切换时重新同步，工作唤醒锁有时间上限并在结束后释放。

网关空闲控制连接在 Android 14+ 使用已声明用途的 `specialUse` 前台服务；新装默认关闭后台运行，既有明确保存的 autoconnect 设置保留迁移；恢复需要已配对且用户允许运行。开机只恢复控制同步，不开启语音录音。收到唤醒后进入原有串行 HTTPS 控制循环，避免 WSS 和定时轮询并发执行命令；短信持久事件和幂等执行账本保持原有规则。

## 需要允许的权限与真实限制

1. 两端允许通知；主机开启后台同步后才能提供后台新短信提示。
2. 对需要持续接收的应用允许电池优化豁免，并在系统提供该功能时允许后台运行/自启动。只从用户操作打开系统授权入口，不自动授权。
3. 无障碍不是本项目后台同步的前提。无障碍服务不提供 Doze 网络豁免，也不能保证进程不被结束，暂不为保活添加空服务。

普通前台服务不能消除 Doze 网络限制；电池豁免有助于无 GMS 的 WSS 路线，但网络故障、系统/厂商策略仍可能延迟。系统设置中的强行停止会阻止正常后台启动，需要用户重新打开并明确开启。已停止的服务不会靠短信广播或开机广播绕过用户选择。

0.4 起通话只使用服务器呼叫控制与 WebRTC；主机的 self-managed Telecom、CallStyle 通知与通话前台服务保留，实现状态见[三端实施状态](implementation-state.md)。实际通话使用对应 Telecom/phoneCall 与麦克风权限；不使用空闲 microphone/phoneCall FGS 保活，也不开机录音。WSS 连通或进程常驻不能作为来电验收依据。

旧手机不使用 FCM：语音运行时常驻前台服务，命令与来电走 HTTPS 轮询与 WSS 唤醒。0.4.1 起 FCM 只用于唤醒主手机（Madoka），见主机仓库同名文档。两台真机仍需测试熄屏、Doze、重启、Wi-Fi/移动网络切换、通知拒绝、用户停止以及两卡短信；本机测试不能证明真实锁屏来电及时性。

## 官方依据

- [Material Components 发布](https://github.com/material-components/material-components-android/releases) / [稳定版本元数据](https://dl.google.com/dl/android/maven2/com/google/android/material/material/maven-metadata.xml)
- [前台服务类型](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [Doze 与电池优化](https://developer.android.com/training/monitoring-device-state/doze-standby)
- [用户停止前台服务](https://developer.android.com/develop/background-work/services/fgs/handle-user-stopping)
- [Android 通知权限](https://developer.android.com/develop/ui/views/notifications/notification-permission)
- [Core-Telecom](https://developer.android.com/develop/connectivity/telecom/voip-app/telecom)
- [FCM Android 前提](https://firebase.google.com/docs/cloud-messaging/android/client)
