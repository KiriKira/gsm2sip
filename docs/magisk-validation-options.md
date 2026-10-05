# Magisk 模块验证环境调研

## 最新实测结果（2026-10-05）

[GitHub Actions run 37247032002](https://github.com/KiriKira/gsm2sip/actions/runs/37247032002) 对提交 `88fbadff2b59b28f95e7e6b27fd171381b34dcd7` 完成了 40/40 项检查。环境为远端 KVM、API 34 非 Play Store `google_apis` x86_64，使用下文固定的 rootAVD 和官方 Magisk v30.7。

实际安装了探针模块及项目 `sip-gsm-gateway` 模块，并验证 systemless 挂载、APK/priv-app allowlist、root-owned `0700` 配置目录和项目 service hook。探针的 `post-fs-data` 与 `service` 标记在两个不同的实际 kernel boot ID 上匹配；不是手工调用钩子来制造通过记录。

本次 `gsm2sipctl accounts 0` 经真实 Magisk `su` 启动 UID 1000 的只读 broker，返回退出码 0、`broker.status=ok`、`broker.query_completed=true`、`broker.count=1`，只留存数量，不记录账户标识。这条账户来自 Emulator 的模拟电话框架，不能当成实体 SIM 验收。`voice.call_acceptance=not_tested`，音频权限探测仍为 `unknown`；本次未拨号、未采集麦克风、未验收蜂窝 HAL/PCM 或双向 SIP 媒体。

KVM 验证发现并修复了独立 `app_process` 的两个问题：synthetic system `ApplicationInfo.uid` 默认值不能用于进程身份判断；Android 11+ 必须先执行普通 App 启动所用的进程内 mainline 初始化，才能正常取得 Telephony 服务管理器。Android 8–10 继续使用旧查询路径；缺少框架能力时仍明确 unavailable，不伪造空账户列表。源码依据见 AOSP [ActivityThread 启动](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/core/java/android/app/ActivityThread.java#8145)、[mainline 初始化](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/core/java/android/app/ActivityThread.java#8186)和 [Telephony subscription 服务查找](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/telephony/java/android/telephony/TelephonyManager.java#16341)。

API 34 的 KVM AVD 已能验证本项目 Magisk 软件路径，因此无需为这项验证切换 Waydroid 或其他镜像。实际旧手机的双 SIM、基带及音频路由仍须真机检查。

## 首次模块生命周期通过记录（2026-10-04）

[GitHub Actions run 37220218785](https://github.com/KiriKira/gsm2sip/actions/runs/37220218785) 在 KVM 上通过了真实 Magisk 模块生命周期验证。可从 [magisk-avd-smoke.yml](../.github/workflows/magisk-avd-smoke.yml) 重新运行 `workflow_dispatch`。该 run 对应提交 `cedfec6a862ea944c988f53ffba05ef38decac61`，40 项检查全部通过。

- 环境为 Android Emulator API 34 `google_apis`（非 Play Store）x86_64；rootAVD 固定在 `613caa44371f85e1a461bc030e07ddc2d71afe32`，官方 Magisk v30.7 APK SHA-256 为 `e0d32d2123532860f97123d927b1bb86c4e08e6fd8a48bfc6b5bee0afae9ebd5`。rootAVD 将实际 ramdisk 从 `66b2074665da2aaa88229b2b495cc34ca6c7aa1db6b1c8992a306d3afddc5bbe` 修补为 `e84cb680c65f65354bfb22be222da0a2eeb992d012881ef80f7528d4bfb4d3bc`。
- Magisk CLI 报告 `30.7:MAGISK:R` / `30700`。官方 app environment check 在冷启动后通过；`/data/adb/magisk/util_functions.sh` 冷启动前后均为 20,418 字节，SHA-256 `efe385ffdb72c7fc9b5bcef4267acc4f68ad73b2ac02bb577328f77ea66643eb`。
- 独立的 `magisk-validation-smoke` 测试模块安装成功。systemless 文件可见，`post-fs-data` 与 `service` 标记在两个不同的冷启动 ID 上匹配：`e314d99c-304f-4f48-b6b0-c3dc550f677d` 和 `9d0d9813-3fb8-4dde-a225-04cdfa681aa4`。
- 项目的 `sip-gsm-gateway` 模块安装并启用；`gsm2sipctl probe` 返回 `module.state=active`、`module.enabled=true`、`root.uid=0`，同时确认模块 APK、本体 APK、`0:700` 配置目录及本次 boot 的 `GatewayMagisk` service hook。

这次验证证明该 API 34 KVM AVD 可运行真实 Magisk、systemless 模块挂载和项目模块启动钩子。Android Emulator 的 telephony broker 只返回结构化的 `status=unavailable`、`count=0`、`error=context`，这不是账户查询功能通过；音频权限状态为 `unknown`，`voice.call_acceptance=not_tested`。因此结果不代表 SIM/HAL、蜂窝音频或通话成功，仍需目标手机验证。

## 实测前的调研背景

以下环境比较和建议记录自实测前的文档/维护者资料调查（2026-10-04），保留作为方案背景；上面的 API 34 结果是后续实际 workflow 验证。早期调查建议从 AOSP 镜像开始，实际通过的配置使用了 rootAVD 支持范围内的非 Play Store `google_apis` 镜像。

调研阶段只读取官方文档、项目维护者资料和本地已有验证记录，没有下载、安装或启动第三方镜像，也没有改动应用代码。

### 调研结论

验证 Magisk 模块要确认的是 Magisk 自身完成启动接管、模块文件 systemless 挂载，以及 `post-fs-data.sh` 和 `service.sh` 分别在启动阶段运行。`su` 能返回 UID 0 只证明有 root shell，不足以说明 Magisk 已启动或模块工作。

最合适的下一步是复用已有 KVM runner，创建一台一次性 Android Emulator x86_64 AVD，先用 API 34 的官方 AOSP 镜像打通 Magisk 启动与模块生命周期，再尝试 API 35。由 rootAVD 维护者提供的脚本面向 Android Studio AVD、支持对指定 `ramdisk.img` 安装 Magisk；不过文档没有明确确认 API 35，因此 API 35 需要单独验证。Google Play 镜像使用 release key，Android 官方文档明确说明不能取得 root；模块验证不需要 Play Store。

Waydroid 有第三方 Magisk 集成脚本，但没有可直接归为 Waydroid 官方支持的 Magisk 镜像。本机 Waydroid 初始化在下载镜像前就因缺少 Binder 内核驱动失败，因此不能在当前宿主上验证。Waydroid 的 Android 用户态共享宿主 Linux 内核，和拥有独立来宾内核的 Android Emulator / QEMU VM 也不是同一种测试环境。

### 什么才算 Magisk 模块验证

Magisk 官方安装说明要求为目标启动链修补 `boot.img`、`init_boot.img` 或 `recovery.img`，再启动修补后的镜像。官方模块指南定义 `/data/adb/modules/<id>` 模块目录；未设置 `skip_mount` 时，模块中的 `system/` 树会挂到系统路径，并可包含在 `post-fs-data` 和 late-start `service` 阶段运行的脚本。参见 [Magisk 安装文档](https://topjohnwu.github.io/Magisk/install.html) 和 [模块及启动脚本指南](https://topjohnwu.github.io/Magisk/guides.html#boot-scripts)。

因此建议用一个无副作用、ID 固定为 `magisk-validation-smoke` 的测试模块同时验证三件事：在 `system/` 放一个标记文件；由 `post-fs-data.sh` 和 `service.sh` 分别写入独立启动标记；重启后检查 Magisk 版本、模块目录和状态、两个标记，以及系统路径里的模块文件。`adb shell su -c id` 只能作为 root 连通性检查，不能代替以上检查。

Magisk 官方工具文档列出的版本选项是 `magisk -v`（运行中 daemon 的版本）和 `magisk -V`（运行中 daemon 的版本码）；`magisk -c` 显示当前二进制版本。官方 CLI 的 `--list` 用来列出 applets，不是模块列表；当前官方 options 未列出 `--list-modules`。可用以下命令检查 daemon 和模块元数据/状态：

```sh
adb shell su -c 'magisk -v'
adb shell su -c 'magisk -V'
adb shell su -c 'cat /data/adb/modules/magisk-validation-smoke/module.prop'
adb shell su -c 'test ! -e /data/adb/modules/magisk-validation-smoke/disable && echo enabled'
adb shell su -c 'test ! -e /data/adb/modules/magisk-validation-smoke/remove && echo no-removal-pending'
```

模块目录中的 `module.prop` 用来核对 ID 和版本元数据；没有 `disable` 表示没有禁用标志，没有 `remove` 表示没有排定下次启动移除。再在 Magisk app 的 **Modules** 页面确认测试模块卡片存在且开关为启用状态。上述元数据和 UI 状态都不能单独证明启动脚本或挂载成功，仍需检查标记和模块系统文件。

为使生命周期检查可重复，可制作以下无副作用测试模块。将这些文件放在 ZIP 根目录的对应路径，脚本设为可执行；ZIP 结构按 [Magisk 官方模块安装器说明](https://topjohnwu.github.io/Magisk/guides.html#magisk-module-installer)制作，再从 Magisk app 的 Modules 页面安装：

```text
module.prop
post-fs-data.sh
service.sh
system/etc/magisk-validation-smoke
```

`module.prop` 至少包含以下字段：

```text
id=magisk-validation-smoke
name=Magisk Validation Smoke
version=1.0
versionCode=1
author=workspace
description=No-op boot and systemless mount validation
```

`post-fs-data.sh` 内容：

```sh
#!/system/bin/sh
mkdir -p /data/adb/magisk-validation-smoke
echo post-fs-data > /data/adb/magisk-validation-smoke/post-fs-data
```

`service.sh` 内容：

```sh
#!/system/bin/sh
mkdir -p /data/adb/magisk-validation-smoke
echo service > /data/adb/magisk-validation-smoke/service
```

`system/etc/magisk-validation-smoke` 内容为 `systemless-mounted`。每次复测前先清除脚本标记并重启；在 Linux/KVM runner 的 shell 中执行以下命令，等待 Android 启动完成后再读取标记：

```sh
adb shell su -c 'rm -f /data/adb/magisk-validation-smoke/post-fs-data /data/adb/magisk-validation-smoke/service'
adb reboot
adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ]; do sleep 1; done
adb shell su -c 'cat /data/adb/magisk-validation-smoke/post-fs-data'
adb shell su -c 'cat /data/adb/magisk-validation-smoke/service'
adb shell su -c 'cat /system/etc/magisk-validation-smoke'
```

### 环境比较

| 环境 | 能验证什么 | Magisk 支持清晰度 | 对本任务的判断 |
| --- | --- | --- | --- |
| Android Emulator AVD + Magisk/rootAVD | 可测试真实 Magisk 启动、模块目录、systemless 挂载和模块启动脚本。AVD 有独立 Android 内核，不依赖宿主 Binder 驱动。 | 中等。Magisk 官方文档说明安装与模块行为，但不背书 AVD。rootAVD 项目明确用于在 AVD 上安装 Magisk，文档列出 API 34，并提供 `google_apis` 与 Play Store 镜像的 `ramdisk.img` 示例；没有明确写 API 35。 | **首选。** 先 API 34，跑通后用 API 35 重复；使用一次性 AVD 和可回滚的镜像副本。 |
| Android-x86 / Bliss OS 的 QEMU/KVM 或 VirtualBox VM | 完整 Android 来宾系统和来宾内核；可测试能在 x86_64 上运行的模块及内核交互。 | 低至中等。维护者文档说明如何启动 VM，但没有给出当前版本、Magisk 启动脚本和 systemless 挂载兼容性的完整验收。 | 可作为备用全系统 VM。先确认所选发行版能用 Magisk 修补启动链；单有系统内 `su` 不算通过。 |
| Waydroid + 第三方 Magisk 集成 | 可能覆盖 Magisk root、模块及部分 Zygisk 用户态行为；Android 用户态运行于 Linux 容器中。 | 中低且依项目而异。见下方桥接项目差异；不能从项目宣传直接推出自定义模块的两个启动阶段及挂载都有效。 | **当前宿主不可用。** 缺 Binder 和模块加载支持；即使换宿主，也应按相同标记模块实测。 |
| 仅有 `adb root`、`su` 或容器 root | shell UID 0 和一部分 root 命令。 | 不构成 Magisk 支持。 | 只能用于前置诊断，不能验收 Magisk 模块。 |

Android 官方 AVD 文档说明，Google Play Store 镜像用 release key 签名，不能取得 root；如需 root 调试，建议选没有 Google apps/services 的 AOSP 系统镜像，并用 `adb root` / `adb unroot`。这和 Magisk root 是两件事：AOSP 的 `adb root` 不能证明 Magisk 模块生命周期。需要 Google Play services 时可评估非 Play Store 的 `google_apis` 镜像，但先确认实际镜像能修补启动链；不要把 `google_apis_playstore` 作为首选。来源：[Android 官方 AVD 系统镜像文档](https://developer.android.com/studio/run/managing-avds#system-images)。

rootAVD 已从 GitHub 移到维护者的 [GitLab 项目](https://gitlab.com/newbit/rootAVD)。其 README 称脚本用于给 Android Studio AVD 安装 Magisk，要求 AVD 已运行且 `adb shell` 可连接；操作参数是 AVD system image 的 `ramdisk.img` 路径，并列出 API 25、29–34 等版本。README 中有 API 25 `google_apis` x86_64 和 API 33 `google_apis_playstore` 示例，但这些示例不等于 API 35 已支持。GitLab 项目 API 显示最近活动为 2026-06-12。脚本会修改 AVD 镜像；实际采用时应先固定 revision、检查脚本内容、保留原始镜像或用一次性 AVD，并按项目的备份/恢复方式回滚。

### Android-x86、Bliss OS 与 Waydroid

[Android-x86 官方 VirtualBox 指南](https://www.android-x86.org/documentation/virtualbox.html)描述了以 ISO 建立 Android VM；该指南列出的已测版本是 Android-x86 6.0-r3 和 VirtualBox 5.2，不能视为当前版本 Magisk 兼容性证明。[Bliss OS 官方文档](https://docs.blissos.org/installation/install-in-a-virtual-machine/)收录 VirtualBox 和 QEMU 安装指南；其 [QEMU 指南](https://docs.blissos.org/installation/install-in-a-virtual-machine/install-in-qemu/)也记载了版本和图形兼容限制。二者适合在有独立来宾内核时测试一般 Android/x86 行为，但目前找到的项目资料没有清楚承诺 Magisk 模块启动脚本及 systemless overlay 兼容。它们的 VM 音频设备也不能替代手机蜂窝音频 HAL。

[Waydroid 官方仓库](https://github.com/waydroid/waydroid)将其描述为以 Linux namespace 运行的完整 Android 容器；容器复用宿主内核。Linux [binderfs 文档](https://docs.kernel.org/admin-guide/binderfs.html)说明 Binder IPC 是 Android 进程间通信的内核驱动接口。Waydroid 本机初始化结果记录在本地 workspace 的 `/workspace/artifacts/android-lightweight/README.md` 与 `/workspace/artifacts/android-lightweight/kernel-prerequisites.txt`：Waydroid 1.6.2 的 `waydroid init -s VANILLA` 以退出码 1 失败，报错 `Binder node "binder" for waydroid not found`；`CONFIG_ANDROID_BINDER_IPC` 和 `CONFIG_MODULES` 都未启用，privileged binderfs 挂载也报 `unknown filesystem type binder`。失败发生在镜像下载前。

目前找到的桥接项目有三类：

- [casualsnek/waydroid_script](https://github.com/casualsnek/waydroid_script) 的维护者 README 提供 `install magisk` 步骤，并声称下次启动会安装 Magisk，Zygisk 和例如 LSPosed 的模块“应该”可用。GitHub API 显示该项目未归档，最近推送于 2026-09-11。这是最值得评估的 Waydroid 路线，但 README 没有提供自定义模块生命周期探针结果，需实测 `post-fs-data`、`service` 和模块挂载。
- [nitanmarcel/waydroid-magisk](https://github.com/nitanmarcel/waydroid-magisk) 的 README 说明它是 Kitsune Mask（Magisk 分支）管理器，并列出 root、Zygisk、modules；但 README 又限定模块只在 Kitsune Canary 可用。GitHub API 显示仓库已归档，最近推送于 2024-07-16，不应把它当作当前官方 Magisk 的通用支持声明。
- [pagkly/MagiskOnWaydroid](https://github.com/pagkly/MagiskOnWaydroid) 面向 Waydroid 11 / Android 11。维护者说明其 initrc 注入方式当时无法让 Zygisk 按时替换 zygote，并提到只有部分普通模块可用；最近推送于 2023-06-13。适合解释历史限制，不适合作为新验证基线。

### 实测前推荐的可复现验证步骤

1. 在已有 KVM runner 上创建全新的 x86_64 AVD，优先用 Android 官方 API 34 AOSP 镜像（无 Play Store）；API 34 是 rootAVD 文档明确列出的级别。先做一个干净启动快照。若应用必须依赖 Play services，再另建非 Play Store 的 `google_apis` AVD。不要复用或覆盖现有 API 35 Google APIs AVD。
2. 先核对 Android Emulator/KVM 正常启动。`adb root` 成功只说明镜像有 root 调试能力；然后从维护者 GitLab 固定 rootAVD revision，检查它实际调用的下载地址和修改逻辑，按文档将 Magisk 装入该 AVD 的 `ramdisk.img`。记录 AVD 镜像类型、API、ABI、rootAVD revision、Magisk 版本，保留未修补镜像以便回滚。
3. 用 Magisk app 的 **Modules** 页面从存储安装测试模块并重启。在启动标记检查前运行上文的 daemon 版本与状态检查；确认 Modules 页面列出该模块、开关开启，`module.prop` 中 ID 正确、`disable`/`remove` 标志不存在。再执行上文三条只读 `cat` 命令，确认两个阶段脚本都运行、模块 `system/` 文件出现在预期系统路径。重启后重复检查，确认它们来自启动生命周期，而非单次手工 `su` 执行。
4. 在 API 34 通过后，再单独用 API 35 x86_64 镜像复测。当前远端 GitHub Actions 的 KVM/API 35 Google APIs 镜像已经可以启动应用，但现有记录明确**没有验证 Magisk 或模块**；不要把普通 APK 成功启动登记为模块测试通过。相关本地 workspace 记录见 `/workspace/artifacts/README.md` 和 `/workspace/artifacts/verification.json`。

这条路径能验证 Magisk、模块挂载、shell 启动脚本和 x86_64 用户态兼容。它不能验收真实 SIM、基带/运营商交互、目标手机的 Qualcomm/Exynos 音频 HAL 或蜂窝数字 PCM 路由。Android Emulator 可提供模拟设备行为，但不是目标手机的基带和音频硬件；本项目文档也明确 Magisk 不提供统一蜂窝 PCM API，详见 [Magisk runtime 说明](magisk-runtime.md)。任何模拟器或容器的通话音频测试都只能作为软件路径检查，真 SIM 和 HAL 路由仍需目标设备实测。

### 主要来源

- Magisk 官方：[安装](https://topjohnwu.github.io/Magisk/install.html)、[模块安装器](https://topjohnwu.github.io/Magisk/guides.html#magisk-module-installer)、[启动脚本](https://topjohnwu.github.io/Magisk/guides.html#boot-scripts)、[CLI 工具选项](https://topjohnwu.github.io/Magisk/tools.html#magisk)、[`su -c` 选项](https://topjohnwu.github.io/Magisk/tools.html#su)
- Magisk 源码：[模块状态/开关/移除标记](https://github.com/topjohnwu/Magisk/blob/master/app/core/src/main/java/com/topjohnwu/magisk/core/model/module/LocalModule.kt)、[Modules 页面开关](https://github.com/topjohnwu/Magisk/blob/master/app/apk/src/main/java/com/topjohnwu/magisk/ui/module/ModuleScreen.kt)
- Android 官方：[AVD 系统镜像与 root 限制](https://developer.android.com/studio/run/managing-avds#system-images)
- rootAVD 维护者：[GitLab 项目与 README](https://gitlab.com/newbit/rootAVD)
- Android-x86 官方：[VirtualBox 指南](https://www.android-x86.org/documentation/virtualbox.html)
- Bliss OS 官方：[VM 指南索引](https://docs.blissos.org/installation/install-in-a-virtual-machine/)、[QEMU 指南](https://docs.blissos.org/installation/install-in-a-virtual-machine/install-in-qemu/)
- Waydroid 官方：[waydroid 仓库](https://github.com/waydroid/waydroid)；桥接维护者：[waydroid_script](https://github.com/casualsnek/waydroid_script)、[waydroid-magisk](https://github.com/nitanmarcel/waydroid-magisk)、[MagiskOnWaydroid](https://github.com/pagkly/MagiskOnWaydroid)
