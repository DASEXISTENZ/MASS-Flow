package com.yuanqian.autofarm.data.model

import com.yuanqian.autofarm.data.resource.ServerTimezone
import com.yuanqian.autofarm.domain.models.UserDataUpdateTriggerInterval
import com.yuanqian.autofarm.domain.models.isUserDataUpdateDue
import com.yuanqian.autofarm.maa.task.MaaTaskParams
import com.yuanqian.autofarm.maa.task.MaaTaskType
import kotlinx.serialization.Serializable

/** 更新数据：按间隔展开为 0~2 个识别任务。 */
@Serializable
data class UserDataUpdateConfig(
    val updateOperBox: Boolean = true,
    val updateDepot: Boolean = true,
    val triggerInterval: UserDataUpdateTriggerInterval = UserDataUpdateTriggerInterval.EVERY_TIME,
) : TaskParamProvider {

    override fun toTaskParams(ctx: TaskParamContext): List<MaaTaskParams> {
        if (!updateOperBox && !updateDepot) {
            return emptyList()
        }

        val yjToday = ServerTimezone.getYjDate(ctx.clientType)
        val yjZone = ServerTimezone.getServerZone(ctx.clientType)
        val operDue = updateOperBox && isUserDataUpdateDue(
            lastSyncMillis = ctx.operBoxRepository.snapshot.value.syncTimeMillis,
            interval = triggerInterval,
            yjToday = yjToday,
            yjZone = yjZone,
        )
        val depotDue = updateDepot && isUserDataUpdateDue(
            lastSyncMillis = ctx.depotRepository.snapshot.value.syncTimeMillis,
            interval = triggerInterval,
            yjToday = yjToday,
            yjZone = yjZone,
        )
        if (!operDue && !depotDue) {
            return emptyList()
        }

        // 对齐上游：按序执行。
        return buildList {
            if (operDue) add(MaaTaskParams(MaaTaskType.OPER_BOX, "{}"))
            if (depotDue) add(MaaTaskParams(MaaTaskType.DEPOT, "{}"))
        }
    }
}
