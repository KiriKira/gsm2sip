# Android 虚拟机：GitHub Actions + KVM 使用说明

更新：2026-10-07。本文记录本项目实际运行成功的方案，以及重新启动、获取结果和停止任务的方法。

## 最终采用的方案

在 GitHub 托管的标准 Ubuntu runner 上，通过 `/dev/kvm` 加速官方 Android Emulator。UI 测试使用 `reactivecircus/android-emulator-runner` 管理 AVD；Magisk 测试使用项目脚本创建独立的一次性 AVD，并用固定版本的 rootAVD 修补镜像。

```text
GitHub Actions 临时 Ubuntu 虚拟机
└── KVM / 官方 Android Emulator
    ├── API 35 AVD：两端 App 的 UI、旋转、折叠和打孔测试
    └── API 34 AVD + rootAVD/Magisk：模块挂载和启动生命周期测试
```

两个 AVD 分别由不同工作流创建，互不共享运行状态。这不是 Waydroid，也不是长期租用的云主机。现有工作流以无窗口模式运行，通过 ADB、UIAutomator 和 instrumentation 操作、截图，没有提供远程桌面或交互式 Android 窗口。

| 用途 | 实际配置 | 仓库 / 工作流文件 | 超时 |
| --- | --- | --- | --- |
| 旧手机网关 App UI | API 35 / Android 15，`google_apis`，`x86_64`，`7.6in Foldable`，2 核、2048 MB RAM | 本仓库 [android-ui-smoke.yml](../.github/workflows/android-ui-smoke.yml) | 25 分钟 |
| 主机 App UI 与原生探针 | 同上 | [主机 android-ui-smoke.yml](https://github.com/KiriKira/gsm2sip-client-android/blob/main/.github/workflows/android-ui-smoke.yml) | 35 分钟 |
| Magisk 模块 | API 34 / Android 14，`google_apis`，`x86_64`，`pixel_2`，2 核、4096 MB RAM，一次性 SDK/AVD 目录 | 本仓库 [magisk-avd-smoke.yml](../.github/workflows/magisk-avd-smoke.yml) | 90 分钟 |

`google_apis` 与 `google_apis_playstore` 是不同镜像。Magisk 路线实测使用前者，不要直接换成 Play Store 镜像。API 35 UI AVD 不安装 Magisk；也没有把 API 35 的 Magisk 支持视为已经验证。

## 一、启动现有 UI 测试

只运行现有工作流时，本机不需要安装 Android SDK、KVM 或 Waydroid。需要能访问对应仓库、启用 GitHub Actions，并有运行工作流的权限。CLI 示例需要已登录的 [GitHub CLI](https://cli.github.com/)。

网页操作：打开对应仓库的 **Actions → Android KVM UI smoke → Run workflow**，选择要验证的分支，点击运行。通常选 `main`；验证新改动时选已推送的开发分支。

旧手机端：

```bash
gh workflow run android-ui-smoke.yml --repo KiriKira/gsm2sip --ref main
gh run list --repo KiriKira/gsm2sip --workflow android-ui-smoke.yml --limit 5
```

主机端：

```bash
gh workflow run android-ui-smoke.yml --repo KiriKira/gsm2sip-client-android --ref main
gh run list --repo KiriKira/gsm2sip-client-android --workflow android-ui-smoke.yml --limit 5
```

从列表取得本次 `RUN_ID`，核对分支、创建时间和被测提交，避免下载别人的运行或历史版本。下面用网关仓库示例；主机端替换 `--repo`：

```bash
gh run view RUN_ID --repo KiriKira/gsm2sip \
  --json headSha,headBranch,status,conclusion,url
gh run watch RUN_ID --repo KiriKira/gsm2sip --exit-status
```

`gh run watch` 只是查看进度，按 Ctrl+C 退出查看不会停止远端任务。停止方法见下文。

现有 UI 工作流会自动准备 JDK 17、Android SDK 35 / build-tools 35.0.0、Gradle，构建 debug APK，然后启动 AVD。模拟器参数为：

```text
-no-window -gpu swiftshader_indirect -no-snapshot -noaudio -no-boot-anim
```

它使用软件图形渲染、关闭声音和快照，适合批量检查布局。`-noaudio` 和 UI 截图不能证明真实电话音频可用。

## 二、下载截图、APK 与原始结果

工作流结束后，在 Actions 运行页的 **Artifacts** 下载，或使用 CLI。网关完整包：

```bash
gh run download RUN_ID --repo KiriKira/gsm2sip \
  --name gsm2sip-gateway-android-ui-smoke --dir evidence/gateway
```

主机完整包和方便评审的精简包：

```bash
gh run download RUN_ID --repo KiriKira/gsm2sip-client-android \
  --name gsm2sip-host-android-ui-smoke --dir evidence/host
gh run download RUN_ID --repo KiriKira/gsm2sip-client-android \
  --name gsm2sip-host-ui-review-evidence --dir evidence/host-review
```

主机另有 `gsm2sip-host-ui-key-screenshots`（PNG）、`gsm2sip-host-ui-diagnostics`（诊断）和 `gsm2sip-host-runtime-apk`（被测 APK）。精简评审包可能不含所有中间截图；需要完整诊断时下载完整包。

现有产物保留 14 天，之后可能过期。解压后按包内目录定位这些文件：

| 文件 | 用途 |
| --- | --- |
| `artifacts/android-ui-smoke/results.json` | 未配对 UI 各项检查与 `overall` 结果 |
| `artifacts/android-ui-smoke/summary.md` | 人类可读的结果与截图索引 |
| `artifacts/android-ui-smoke/screenshot_manifest.json` | PNG、UI XML、阶段名和截图尺寸 |
| 同目录的 `*.png` / `*.xml` / 日志 | 原始图像、UIAutomator 层级及故障证据 |
| `app/build/outputs/apk/debug/app-debug.apk` | 同次运行构建的 debug APK |
| 主机 `native/results.json` | 原生 instrumentation 探针结果，`status` 使用 `PASS` / `FAIL` 等大写值 |
| 主机 `paired-fixture/results.json` 与 `fixture_manifest.json` | 离线合成配对界面、草稿及合成通话状态；结果字段分别为 `status` 与 `overall` |

保存报告时记录仓库、完整 source commit、run ID、artifact ID/digest，以及 APK 和图片 SHA-256。PNG 保留原始字节，Markdown 用相对链接引用；不要把旧版本的截图标成新版本已验证。失败或 `partial` 的检查也应保留，不因工作流显示绿色就忽略 `blocked` 项。

主机 `main` 的完整检查通过后，现有工作流会使用 `scripts/publish-ui-verification.py` 自动保存原图与中文报告。发布前核对被测 App/脚本仍与当前 `main` 匹配；失败运行不能覆盖为通过。网关目前只上传 artifact，不自动写回完整截图报告。

## 三、UI 测试实际执行什么

网关由 [android-ui-smoke.py](../scripts/android-ui-smoke.py) 驱动，涵盖未配对设置、拒绝权限后的界面、软键盘、横竖屏输入恢复、模拟折叠/展开、打孔按钮几何检查及备份界面。导入测试使用合成 SMS Backup & Restore XML，核对预览、只读归档和重复导入去重。

主机工作流依次尝试三个阶段；某阶段失败也会尝试其余阶段，整体仍判失败：

1. `android-ui-smoke.py --scenario host`：电话 / 短信 / 设置、独立拨号页、未配对表单及备份。
2. `run-native-smoke.sh`：PJSUA2 原生 endpoint 的 instrumentation 探针，使用 null audio。
3. `run-paired-ui-fixture.sh`：离线合成配对缓存，覆盖两张 SIM、会话、草稿、窗口变化和四种通话 UI 状态。

主机 fixture 打开飞行模式并禁用 Wi-Fi/移动数据，以合成数据填充本地缓存；地址为保留域名 `ui-smoke.invalid`。合成通话只设置进程内 UI 状态，不进行 SIP、Telecom、蜂窝拨号或接听。结束时清理合成缓存、停止服务、恢复无线设置并卸载 fixture APK。

fixture APK 必须和被测主机 APK 使用同一个 debug 签名。主机工作流从 Gradle `signingReport` 取得实际 keystore 并保存到 `RUNNER_TEMP`，通过 `PJSUA_SMOKE_KEYSTORE` 传给探针，避免猜测 `$HOME/.android/debug.keystore`。这些测试不需要正式 APK 的签名 Secrets。

## 四、启动 Magisk 模块测试

网页选择本仓库 **Actions → Magisk API 34 AVD module smoke → Run workflow**，或：

```bash
gh workflow run magisk-avd-smoke.yml --repo KiriKira/gsm2sip --ref main
gh run list --repo KiriKira/gsm2sip --workflow magisk-avd-smoke.yml --limit 5
gh run download RUN_ID --repo KiriKira/gsm2sip \
  --name gsm2sip-magisk-avd-smoke --dir evidence/magisk
```

下载后检查 `artifacts/magisk-avd-smoke/summary.json`（同内容也写入 `results.json`）：成功值是 `status=passed`，并应核对 `checks`、`boot_ids`、`probe_marker_boot_ids`，以及 `gateway-probe.txt` 中的模块和 broker 探测结果。包内还包括 Emulator 日志、rootAVD 日志/补丁、ramdisk 哈希和 `gateway-magisk.zip`；只看工作流结束状态不足以定位模块是否真正完成启动。

工作流固定了以下来源，重新验证时以 YAML 中的值为准：

| 项目 | 已实测值 |
| --- | --- |
| 镜像 | `system-images;android-34;google_apis;x86_64` |
| rootAVD | [newbit/rootAVD](https://gitlab.com/newbit/rootAVD)，commit `613caa44371f85e1a461bc030e07ddc2d71afe32` |
| 官方 Magisk | [v30.7](https://github.com/topjohnwu/Magisk/releases/tag/v30.7)，versionCode `30700` |
| Magisk APK SHA-256 | `e0d32d2123532860f97123d927b1bb86c4e08e6fd8a48bfc6b5bee0afae9ebd5` |
| APK 内官方 `assets/app_functions.sh` SHA-256 | `6c0acfadcfca72dcdc1a6bd37874ef0ef10ff793a19ead34a4b77e5b2ca13f0b` |

工作流把 SDK 与 AVD 放在 `RUNNER_TEMP` 的独立目录，下载固定 rootAVD 与官方 Magisk APK、校验哈希，执行 `SKIP_INSTALL=1 ./build.sh` 构建项目模块，再调用 [magisk-avd-smoke.py](../scripts/magisk-avd-smoke.py)。脚本负责启动、修补实际 ramdisk、冷启动和模块探测；不是在普通 UI AVD 中直接运行 `adb root`。

实际脚本调用如下，所需目录和已校验的下载由上面的工作流准备：

```bash
python3 scripts/magisk-avd-smoke.py \
  --artifact-dir artifacts/magisk-avd-smoke \
  --rootavd-dir "$ROOTAVD_DIR" \
  --android-home "$ANDROID_HOME" \
  --avd-home "$ANDROID_AVD_HOME" \
  --gateway-zip gateway-magisk.zip
```

脚本还会在临时 rootAVD checkout 上应用兼容补丁，禁用自更新、动态脚本/模块下载及网络 BusyBox 下载调用，并保存 `rootavd-patch.diff`。修补后调用官方 Magisk APK 的 `app_functions.sh` 内 `fix_env()`，补全实际运行环境，再冷启动复核。实测发现只完成 rootAVD 修补时可能缺少 `util_functions.sh`，因此不要把本流程简化成直接运行未适配的 `rootAVD.sh`，或跳过官方环境修复。

模块验收包括官方 Magisk 环境检查、systemless 文件挂载、模块启用状态、项目 APK/权限配置、受限账户 broker，以及探针 `post-fs-data` / `service` 标记在两个不同真实 kernel boot ID 上匹配。`su` 返回 UID 0 或模块目录存在，单独都不足以判模块通过。脚本还记录修补前后的 ramdisk 哈希和冷启动前后的 `util_functions.sh` 状态。

详细的兼容处理、生命周期证据和环境比较见 [Magisk 验证记录](magisk-validation-options.md)。测试会修改一次性镜像并安装模块；复用到本地时必须给它独立 SDK/AVD 目录，不能指向日常使用的模拟器镜像。

rootAVD commit、Magisk APK 与 emulator-runner action 已固定，但 Ubuntu runner、部分 setup actions、SDK 镜像 revision、platform-tools 和 Emulator 仍可能更新。API 号固定不表示整个运行环境逐字节固定；复测时保留工具版本与实际镜像/产物哈希。ramdisk 修补后哈希和项目模块 ZIP 哈希会随运行或代码变化，不能把某次的结果当成通用安装常量。

## 五、迁移到其他 Android 项目

下面是只启动 App、保存一张原图和 UI XML 的基础工作流模板，不包含本项目的完整检查。保存为 `.github/workflows/android-vm.yml`，把 `com.example.app/.MainActivity` 改为项目实际包名与 Activity；多模块项目也要调整 APK 路径。

```yaml
name: Android VM screenshot
on:
  workflow_dispatch:
permissions:
  contents: read
jobs:
  screenshot:
    runs-on: ubuntu-24.04
    timeout-minutes: 25
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
      - uses: android-actions/setup-android@v3
        with:
          packages: platform-tools platforms;android-35 build-tools;35.0.0
          log-accepted-android-sdk-licenses: false
      - uses: gradle/actions/setup-gradle@v4
      - name: Enable KVM
        run: |
          echo 'KERNEL=="kvm", GROUP="kvm", MODE="0666", OPTIONS+="static_node=kvm"' | sudo tee /etc/udev/rules.d/99-kvm4all.rules
          sudo udevadm control --reload-rules
          sudo udevadm trigger --name-match=kvm
          test -r /dev/kvm && test -w /dev/kvm
      - run: ./gradlew :app:assembleDebug --no-daemon --max-workers=2
      - uses: reactivecircus/android-emulator-runner@a421e43855164a8197daf9d8d40fe71c6996bb0d
        with:
          api-level: 35
          target: google_apis
          arch: x86_64
          profile: 7.6in Foldable
          cores: 2
          ram-size: 2048M
          emulator-boot-timeout: 360
          disable-animations: true
          emulator-options: -no-window -gpu swiftshader_indirect -no-snapshot -noaudio -no-boot-anim
          script: |
            mkdir -p artifacts/ui
            adb install -r app/build/outputs/apk/debug/app-debug.apk
            adb shell am start -W -n com.example.app/.MainActivity
            adb shell uiautomator dump /sdcard/ui.xml
            adb pull /sdcard/ui.xml artifacts/ui/ui.xml
            adb exec-out screencap -p > artifacts/ui/screen.png
      - uses: actions/upload-artifact@v4
        if: always()
        with:
          name: android-vm-ui
          path: artifacts/ui/**
          retention-days: 14
```

模板只捕获启动后的当前界面，可能是加载页或权限弹窗。可靠的功能测试需像本项目脚本一样等待目标控件出现，再操作并核对 UI 层级；一张截图本身不表示功能通过。

如果在自己的 Linux 机器复用，需要可用且有访问权限的 `/dev/kvm`、硬件虚拟化，以及相同 Android SDK、镜像和 JDK。容器内通常还需要宿主暴露 `/dev/kvm`；宿主没有硬件虚拟化时，仅安装 Emulator/Waydroid 不能补出 KVM 能力。本项目最终成功的运行发生在 GitHub runner，不依赖开发 workspace 提供 KVM。

## 六、停止、清理和费用

在运行页点击 **Cancel workflow**，或：

```bash
gh run cancel RUN_ID --repo KiriKira/gsm2sip
gh run list --repo KiriKira/gsm2sip --status in_progress
gh run list --repo KiriKira/gsm2sip --status queued
```

主机端替换仓库名。取消后核对运行最终为 `completed/cancelled`，不要只关闭浏览器或 CLI。正常结束、失败、取消或超时后，GitHub 托管 runner 会被清理，AVD 不会成为持续运行的云服务器。保留下来的 artifact 是文件存储，可以在运行页删除；已下载或已提交到仓库的 Markdown/PNG 不随 artifact 删除。

现有模拟器工作流没有 `schedule/cron`。相关源码推送与手动运行可以触发测试；保留 YAML 不等于保持 Android 开机。要彻底停止今后自动触发，可在 Actions 工作流菜单选择 **Disable workflow**；这与取消某次运行、删除某次 artifact 是三种不同操作。

公开仓库使用标准 GitHub 托管 runner 的计算通常免费；私有仓库使用账户套餐的免费额度，超额且启用付费后可能收费。较大 runner、产物存储和缓存有各自的计费规则，不应把“公开仓库”推断为所有资源无限免费。以账户 **Settings → Billing and licensing** 的实时用量、预算与 [GitHub Actions 计费说明](https://docs.github.com/en/billing/concepts/product-billing/github-actions) 为准。本文不代表已核对或承诺某账户的实际账单为零。

## 七、故障判断与验证边界

| 现象 | 应检查什么 |
| --- | --- |
| 一直 queued，随后失败，job 没有步骤或 runner 名 | Actions 平台状态、账户 Actions/费用限制及 job annotation。曾遇到 `The job was not acquired by Runner of type hosted even after multiple attempts`；这种运行没有 Android 测试结果，不能当成 App 失败或通过。 |
| 找不到或无权访问 `/dev/kvm` | runner 类型、KVM 设备及 udev 权限；不要回到无 KVM 的开发容器反复尝试同一硬件加速配置。 |
| instrumentation 签名不匹配 | 确保被测 APK 和 fixture 使用相同实际 debug keystore，检查 `signingReport` 和 `PJSUA_SMOKE_KEYSTORE`。 |
| API 35 的权限探测命令不存在 | 主机脚本使用 `dumpsys package check-permission PERMISSION PACKAGE 0`，返回 `0` / `-1`；不要假设 `pm check-permission` 在该镜像可用。 |
| UI 控件 XML 存在但点击无效 | 检查截图、ScrollView 可见范围、底栏覆盖、IME 和打孔；滚动到真正未被遮挡的视口再点击。 |
| Magisk 有 root，但模块启动钩子没运行 | 检查官方 app environment、systemless 挂载、实际冷启动 boot ID 和模块标记，不手动执行启动钩子制造通过记录。 |
| 下载不到截图 | 检查 artifact 是否上传、名称、运行 ID、访问权限及 14 天保留期；runner 未启动的任务可能根本没有产物。 |

已保存的成功证据：网关 UI [run 37349881431](https://github.com/KiriKira/gsm2sip/actions/runs/37349881431)；主机重构前 UI [run 37349696267](https://github.com/KiriKira/gsm2sip-client-android/actions/runs/37349696267)；Magisk 生命周期及只读 broker [run 37273307094](https://github.com/KiriKira/gsm2sip/actions/runs/37273307094)。两端原始截图与结果见 [联合报告](https://github.com/KiriKira/gsm2sip-server/blob/main/docs/ui-verification/README.md)，新版主机的具体状态见 [主机重构报告](https://github.com/KiriKira/gsm2sip-client-android/blob/main/docs/ui-redesign-verification/README.md)。历史成功运行不替代新版界面的重新验收。

这些环境可以验证 App 启动、UI、原生软件探针、Magisk 挂载和生命周期。它们不能代替真实双 SIM、短信运营商投递、蜂窝 HAL/数字音频、双向 SIP 媒体、锁屏来电及时性或 Z Fold8 真机验收。模拟器电话账户的只读查询成功也不等于物理 SIM 或通话成功。
