package com.callagent.gateway.ui

/**
 * The guided path that turns a spare phone into a remote dual-SIM gateway.
 * Kept free of Android types so each state transition is unit-tested; the
 * activity only gathers [Input] and renders [Step]s.
 */
object GatewaySetupChecklist {
    enum class StepId { PAIR, SIMS, BACKGROUND, VOICE }

    enum class Status {
        /** Finished; nothing for the user to do. */
        DONE,
        /** The next thing the user should do. */
        NEXT,
        /** Needs attention, but the gateway keeps working. */
        WARNING,
        /** Waits for an earlier step. */
        BLOCKED,
        /** Not required for SMS; offered when the server supports it. */
        OPTIONAL
    }

    enum class Action {
        OPEN_PAIRING,
        OPEN_SIMS,
        ENABLE_BACKGROUND,
        OPEN_BATTERY,
        OPEN_NOTIFICATIONS,
        CONFIGURE_VOICE,
        START_VOICE,
        NONE
    }

    data class Input(
        val paired: Boolean,
        val smsPermissionsGranted: Boolean,
        val activeSimCount: Int,
        val detectedSimCount: Int,
        val proposalPending: Boolean,
        val backgroundEnabled: Boolean,
        val backgroundRunning: Boolean,
        val notificationsEnabled: Boolean,
        val batteryExempt: Boolean,
        val voiceConfigured: Boolean,
        val voiceRunning: Boolean,
        val sipRegistered: Boolean
    )

    data class Step(
        val id: StepId,
        val title: String,
        val detail: String,
        val status: Status,
        val action: Action,
        val actionLabel: String?
    )

    data class Summary(val steps: List<Step>, val headline: String, val requiredComplete: Boolean)

    fun build(input: Input): Summary {
        val pair = if (input.paired) {
            Step(StepId.PAIR, "连接中转服务器", "已配对，可接收远程短信和通话任务。", Status.DONE, Action.NONE, null)
        } else {
            Step(StepId.PAIR, "连接中转服务器",
                "在服务器生成 gateway 配对码，填写服务器地址后配对。", Status.NEXT, Action.OPEN_PAIRING, "去配对")
        }

        val sims = when {
            !input.paired -> Step(StepId.SIMS, "确认 SIM 卡", "配对后由服务器分配 SIM 身份。",
                Status.BLOCKED, Action.NONE, null)
            !input.smsPermissionsGranted -> Step(StepId.SIMS, "确认 SIM 卡",
                "需要电话状态和短信权限才能读取本机 SIM。", Status.NEXT, Action.OPEN_SIMS, "授权并同步")
            input.proposalPending -> Step(StepId.SIMS, "确认 SIM 卡",
                "服务器已返回待确认的 SIM，请逐张核对卡槽后确认。", Status.NEXT, Action.OPEN_SIMS, "去确认")
            input.activeSimCount == 0 -> Step(StepId.SIMS, "确认 SIM 卡",
                if (input.detectedSimCount == 0) "未检测到已启用的 SIM，插卡后再同步。"
                else "检测到 ${input.detectedSimCount} 张 SIM，尚未绑定到服务器。",
                Status.NEXT, Action.OPEN_SIMS, "同步 SIM")
            input.activeSimCount < input.detectedSimCount -> Step(StepId.SIMS, "确认 SIM 卡",
                "已绑定 ${input.activeSimCount} 张，另有 ${input.detectedSimCount - input.activeSimCount} 张未绑定。",
                Status.WARNING, Action.OPEN_SIMS, "同步 SIM")
            else -> Step(StepId.SIMS, "确认 SIM 卡", "已绑定 ${input.activeSimCount} 张 SIM。",
                Status.DONE, Action.NONE, null)
        }

        val background = when {
            !input.paired -> Step(StepId.BACKGROUND, "保持后台在线", "配对后启用。", Status.BLOCKED, Action.NONE, null)
            !input.backgroundEnabled -> Step(StepId.BACKGROUND, "保持后台在线",
                "关闭时主手机无法实时收到短信和来电。", Status.NEXT, Action.ENABLE_BACKGROUND, "启用后台运行")
            !input.batteryExempt -> Step(StepId.BACKGROUND, "保持后台在线",
                "未豁免电池优化，熄屏后系统可能暂停同步。", Status.WARNING, Action.OPEN_BATTERY, "电池设置")
            !input.notificationsEnabled -> Step(StepId.BACKGROUND, "保持后台在线",
                "通知未允许，常驻状态不会显示在通知栏。", Status.WARNING, Action.OPEN_NOTIFICATIONS, "通知设置")
            !input.backgroundRunning -> Step(StepId.BACKGROUND, "保持后台在线",
                "已启用，等待系统启动后台服务。", Status.WARNING, Action.NONE, null)
            else -> Step(StepId.BACKGROUND, "保持后台在线", "后台运行中，电池优化已豁免。",
                Status.DONE, Action.NONE, null)
        }

        val voice = when {
            !input.paired -> Step(StepId.VOICE, "远程通话（需 root）", "配对后可从服务器下发 SIP 配置。",
                Status.BLOCKED, Action.NONE, null)
            input.voiceRunning && input.sipRegistered -> Step(StepId.VOICE, "远程通话（需 root）",
                "SIP 已注册，主手机可经服务器接打电话。", Status.DONE, Action.NONE, null)
            input.voiceRunning -> Step(StepId.VOICE, "远程通话（需 root）",
                "SIP 正在连接；若长时间未注册，请查看日志。", Status.WARNING, Action.NONE, null)
            input.voiceConfigured -> Step(StepId.VOICE, "远程通话（需 root）",
                "已有 SIP 配置。运行设备诊断确认 root 与通话音频后启动。", Status.OPTIONAL, Action.START_VOICE, "诊断并启动")
            else -> Step(StepId.VOICE, "远程通话（需 root）",
                "仅收发短信可跳过。需要通话时从服务器获取 SIP 配置。", Status.OPTIONAL, Action.CONFIGURE_VOICE, "获取 SIP 配置")
        }

        val steps = listOf(pair, sims, background, voice)
        val required = listOf(pair, sims, background)
        val requiredComplete = required.none { it.status == Status.NEXT || it.status == Status.BLOCKED }
        val remaining = required.count { it.status != Status.DONE }
        val headline = when {
            !requiredComplete -> "完成设置（还剩 $remaining 步）"
            remaining > 0 -> "网关可用 · 有 $remaining 项建议"
            voice.status == Status.DONE -> "网关已就绪 · 短信与通话"
            else -> "网关已就绪 · 短信"
        }
        return Summary(steps, headline, requiredComplete)
    }
}
