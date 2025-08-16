package pn.torn.goldeneye.torn.service.faction.oc.notice;

/**
 * OC校验通知逻辑对象
 *
 * @param planId           计划OC ID
 * @param planKey        计划队伍配置Key
 * @param excludePlanKey 排除的计划队伍配置Key
 * @param recKey         招募队伍配置Key
 * @param excludeRecKey  排除的招募队伍配置Key
 * @param refreshOc      刷新OC的方式
 * @param reloadSchedule 重载定时任务的方式
 * @param lackCount      确认队伍的数量
 * @param freeCount      空闲人数
 * @param rank           查询的OC级别
 * @author Bai
 * @version 0.1.0
 * @since 2025.08.16
 */
public record TornFactionOcValidNoticeBO(
        long planId,
        String planKey,
        String excludePlanKey,
        String recKey,
        String excludeRecKey,
        Runnable refreshOc,
        Runnable reloadSchedule,
        int lackCount,
        int freeCount,
        int... rank) {
}