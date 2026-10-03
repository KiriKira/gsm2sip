# Magisk 通用适配

本批按用户要求先实现 Magisk 通用能力层，不按手机型号自动选配置，也不
以 API 31 为整体语音门槛。APK 仍沿用最低 API 26 的构建基线；具体系统
方法按存在性和权限探测。主机与 server 的 SIP/ARI 尚未完成。

## 模块与固定控制入口

模块 id 为 `sip-gsm-gateway`，安装 zip 包含 APK 的 systemless priv-app
overlay、权限 allowlist、模块生命周期脚本和固定入口：

```sh
/data/adb/modules/sip-gsm-gateway/bin/gsm2sipctl probe
/data/adb/modules/sip-gsm-gateway/bin/gsm2sipctl status
/data/adb/modules/sip-gsm-gateway/bin/gsm2sipctl accounts 0
```

入口只支持这组本地只读命令，先验证 root、启用的模块和必要工具；不
接受任意 shell、远程 APK 路径、UID 或 mixer 命令。`probe/status` 输出
版本化 key=value 能力信息，包括 boot、模块标记、root、app_process、
权限、工具与本地配置状态。这些信息不等于已通过通话或双向音频验收。

`service.sh` 在非阻塞 late_start 阶段等待系统启动完成；
`post-fs-data.sh` 只做短时间的模块/持久 root 配置目录准备，不等待启动
完成或查询 Telephony。
模块不会修改全局 su 授权数据库、全局 SELinux 模式或默认 SIM，也不再
默认覆盖 Qualcomm/Exynos 的 vendor 音频 properties。

## 精确订阅与账户映射

公开接口可用时在 app 进程查询。公开接口不足时，入口从 PackageManager
读取固定包 `com.callagent.gateway` 当前安装的 base APK，启动其中固定
broker 主类。外层必须是 root；通过 Magisk `su -s /system/bin/sh 1000`
启动短生命周期 system-UID 子进程，将 Binder 调用归因到真实的 `android`
系统包。Magisk 没有 `su -u` 选项；也不能用 UID 0 假冒该系统包。

子进程只查询 Telephony/Telecom：当前 active subscription、call-capable
PhoneAccount、框架注册的 SIM capability、精确 account→subId 关联及可用
时的正向关联。映射必须唯一且属于相同 Android user。handle.id 保持
opaque，不从 ID、slot 排序或默认卡推断。root broker 服务 user 0；其他
user 只能使用其自己进程内的可信公开关联。

成功协议固定为 `protocol=1`、`broker_version=1`、当前 `app_version`、
`broker_uid=1000`、`source=magisk-system-telephony`、`status=ok`、`user_id`、
`count` 和逐条 `account.N.sub_id/component_b64/id_b64/user_id`。字符串用
canonical Base64URL；最多 32 条、32 KiB，无重复 key、subId 或 account。
版本、UID、user、完整 schema 或 UTF-8 不一致时拒绝整个结果，不取部分行。
账户 ID 可能含敏感标识，输出只在本地用于映射，不上传或写入业务日志。

broker 使用进程内 hidden API 能力探测，不修改系统全局 hidden-API 策略。
子进程执行上限 8 秒，外层上限 10 秒，app 等待上限 12 秒。root 输出
有界，超时或截断不作为有效映射。查询在工作线程进行，迟到结果不能
恢复已结束的通话。broker 暂不可用只使 voice 不可用，不伪造新 SIM 身份。

## 本地音频配置

持久文件为 `/data/adb/gsm2sip/audio-profile.json`，由 root 管理，server
和主机不能写。文件不存在时采用 generic 默认；存在但无效、不可读取或
超限时明确报配置错误。修改配置后重启 gateway app 以重新载入；不在
正在进行的通话中更换 profile。

配置上限 8 KiB，严格检查版本、重复 key、类型、范围和未知字段。例如：

```json
{
  "version": 1,
  "preset": "generic",
  "capture": {
    "allowMicFallback": false,
    "telephonyRxRequired": false,
    "gain": 1,
    "silenceFrames": 100
  },
  "playback": {
    "telephonyTxRequired": true,
    "gain": 2,
    "bufferMs": 0
  }
}
```

默认只探测数字 VOICE_CALL/VOICE_DOWNLINK，并尝试可用的 Telephony Rx
和要求 Telephony Tx 路由。未暴露 Rx 设备仍允许尝试特权数字录音源；
本地设 `telephonyRxRequired=true` 才要求严格 Rx 设备路由。初始化
AudioRecord 或设置 preferred device 成功，不能当成语音送达成功；检查
实际路由和持续 capture 读数，失败通过媒体错误回调停止。
不会自动使用 MIC/扬声器耦合来掩盖数字路径缺失。麦克风 fallback 仅在
本地显式允许时加入候选，必要的声学路由会保存和恢复之前状态。

历史 preset 仅允许本地显式选择：`legacy_msm8930`、`legacy_exynos9820`、
`legacy_sm6150`、`legacy_qualcomm`、`legacy_exynos`。这些旧适配会操作其
既定 vendor mixer，须由操作者确认与系统匹配；默认 generic 不操作。
配置不支持任意命令或自定义 mixer shell。

## 构建与验证

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
sh tools/test-magisk-runtime.sh
SKIP_INSTALL=1 bash build.sh debug
```

最后一步生成 `gateway.apk` 和 `gateway-magisk.zip`，不连接或安装手机。
tinymix 按 arm64、arm、x86_64、x86 ABI 打包；工具缺失不阻止通用数字
API 的能力探测。模块安装后可通过 `tools/check-device.sh <adb-serial>`
执行只读探测，不再限定 Qualcomm。

Magisk 提供权限、UID 与 systemless 模块机制，不提供统一蜂窝 PCM API。
接口层实现与后续实机验证分开推进；系统未暴露的 HAL/基带能力必须
报告 unavailable。实际双 SIM 收发、通话、DSDS 和长时间运行尚未验证。

参考：[Magisk CLI](https://topjohnwu.github.io/Magisk/tools.html#su)、
[模块生命周期](https://topjohnwu.github.io/Magisk/guides.html#boot-scripts)、
[Android O TelephonyManager](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-8.0.0_r1/telephony/java/android/telephony/TelephonyManager.java)、
[Android R TelephonyManager](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-11.0.0_r1/telephony/java/android/telephony/TelephonyManager.java)。
