package pn.torn.goldeneye.torn.service.faction.oc.planning.api;

import org.springframework.stereotype.Component;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcCurrentOccupancySummary;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlanReasonCodeEnum;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcRefreshInstructionPlan;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcReplanWindow;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;

/**
 * 将匿名时间线求解结果渲染为OC指挥官可执行的精简刷新指令。
 *
 * <p>始终匿名：不输出成员、ID、岗位、个人加入或释放时间、链内部运行明细，
 * 也不输出时间线评估、证明状态、原因码、停转上限或收益证据说明。</p>
 *
 * @author Bai
 * @version 1.4.4
 * @since 2026.07.17
 */
@Component
public class OcNewTeamPlanRenderer {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private static final String NO_NEXT_CRITICAL_RELEASE_TEXT = "当前无可证明的下一批关键成员释放时间";
    private static final String NEXT_REFRESH_TIME_PREFIX = "建议下次刷新时间: ";
    private static final String NO_PROVABLE_FUTURE_EVENT_TEXT = "下一次关键状态变化后重新评估";
    private static final String REEVALUATE_AFTER_SUFFIX = " 后重新评估";
    private static final String CRITICAL_RELEASE_PREFIX = "关键成员释放后（预计 ";
    private static final String NOW_TEXT = "现在";

    /**
     * 渲染刷新操作指令，不输出成员、岗位或加入时间。
     *
     * @param plan 刷新操作指令
     * @return 群消息文本
     */
    public String render(OcRefreshInstructionPlan plan) {
        StringBuilder result = new StringBuilder();
        appendHeader(result, plan);
        appendRefreshInstruction(result, plan);
        appendNextRefreshTime(result, plan);
        appendCurrentStatus(result, plan);
        return result.toString();
    }

    /**
     * 追加首行：模式与执行时间。
     *
     * @param result 输出缓冲区
     * @param plan   刷新操作指令
     */
    private void appendHeader(StringBuilder result, OcRefreshInstructionPlan plan) {
        result.append("【OC新队#").append(plan.mode().getCommand()).append("】 执行时间: ")
                .append(plan.snapshotTime().format(TIME_FORMAT));
    }

    /**
     * 追加刷新指令区块，只展示非零池次数与完成后重跑提示。
     *
     * @param result 输出缓冲区
     * @param plan   刷新操作指令
     */
    private void appendRefreshInstruction(StringBuilder result, OcRefreshInstructionPlan plan) {
        result.append("\n\n【刷新指令】");
        if (plan.normalRefreshCount() == 0 && plan.highRefreshCount() == 0) {
            result.append("\n暂不刷新");
            return;
        }
        if (plan.normalRefreshCount() > 0) {
            result.append("\n普通池: 刷新").append(plan.normalRefreshCount()).append("次");
        }
        if (plan.highRefreshCount() > 0) {
            result.append("\n高阶池: 刷新").append(plan.highRefreshCount()).append("次");
        }
        result.append("\n完成后重新运行 OC新队#").append(plan.mode().getCommand());
    }

    /**
     * 追加下次刷新时间区块。
     *
     * @param result 输出缓冲区
     * @param plan   刷新操作指令
     */
    private void appendNextRefreshTime(StringBuilder result, OcRefreshInstructionPlan plan) {
        result.append("\n\n【下次刷新时间】")
                .append("\n").append(NEXT_REFRESH_TIME_PREFIX)
                .append(formatNextRefreshTime(plan));
    }

    /**
     * 将重新评估窗口转换为用户可理解的时间状态文案。
     *
     * <p>零刷新（两池刷新次数均为0）走独立安全路径，绝不输出“现在”，
     * 避免暂不刷新时诱导指挥官立即重跑刷新命令；有刷新次数时保持既有
     * “立即状态、时间范围、未来关键成员释放、现在”的展示顺序。</p>
     *
     * @param plan 刷新操作指令
     * @return 零刷新时返回未来事实或安全兜底文案；有刷新时返回“现在”、时间范围或事件后预计时间
     */
    private String formatNextRefreshTime(OcRefreshInstructionPlan plan) {
        if (isNoRefresh(plan)) {
            return formatNoRefreshNextTime(plan);
        }
        return formatRefreshNextTime(plan);
    }

    /**
     * 判断本次指令是否为暂不刷新。
     *
     * @param plan 刷新操作指令
     * @return 两池刷新次数均为0时返回true
     */
    private boolean isNoRefresh(OcRefreshInstructionPlan plan) {
        return plan.normalRefreshCount() == 0 && plan.highRefreshCount() == 0;
    }

    /**
     * 输出零刷新场景下的下次刷新时间文案。
     *
     * <p>P0安全契约：暂不刷新时绝不输出“现在”。优先输出完整未来范围（Z1），
     * 其次未来关键成员释放（Z2，必须覆盖一切立即状态原因码），再次未来重评估
     * 时间点（Z3），最后回退到无动作指引的安全兜底文案（Z4）。</p>
     *
     * @param plan 刷新操作指令
     * @return 未来时间范围、关键成员释放后、指定时间后重新评估或安全兜底文案
     */
    private String formatNoRefreshNextTime(OcRefreshInstructionPlan plan) {
        OcReplanWindow window = plan.replanWindow();
        LocalDateTime snapshotTime = plan.snapshotTime();
        LocalDateTime next = window.nextReplanAt();
        LocalDateTime latest = window.latestReplanAt();
        if (!isImmediate(plan) && hasNormalRange(next, latest) && next.isAfter(snapshotTime)) {
            return next.format(TIME_FORMAT) + " - " + latest.format(TIME_FORMAT);
        }
        if (hasWaitingCriticalEvent(plan)) {
            return CRITICAL_RELEASE_PREFIX + plan.nextCriticalReleaseAt().format(TIME_FORMAT) + "）";
        }
        if (next != null && next.isAfter(snapshotTime)) {
            return next.format(TIME_FORMAT) + REEVALUATE_AFTER_SUFFIX;
        }
        return NO_PROVABLE_FUTURE_EVENT_TEXT;
    }

    /**
     * 输出有刷新指令时的下次刷新时间文案，保持既有展示顺序。
     *
     * <p>顺序为立即状态、完整时间范围、未来关键成员释放，最后回退“现在”。</p>
     *
     * @param plan 刷新操作指令
     * @return “现在”、时间范围或事件后预计时间
     */
    private String formatRefreshNextTime(OcRefreshInstructionPlan plan) {
        OcReplanWindow window = plan.replanWindow();
        LocalDateTime next = window.nextReplanAt();
        LocalDateTime latest = window.latestReplanAt();
        if (isImmediate(plan)) {
            return NOW_TEXT;
        }
        if (hasNormalRange(next, latest)) {
            return next.format(TIME_FORMAT) + " - " + latest.format(TIME_FORMAT);
        }
        if (hasWaitingCriticalEvent(plan)) {
            return CRITICAL_RELEASE_PREFIX + plan.nextCriticalReleaseAt().format(TIME_FORMAT) + "）";
        }
        return NOW_TEXT;
    }

    /**
     * 判断当前建议是否已经不能安全延后。
     *
     * <p>窗口起点已到达当前时刻（nextReplanAt 等于快照时间）、窗口终点已不晚于当前时刻、
     * 或明确要求立即重评估时，必须立即重新执行，不能沿用看似可延后的时间范围。</p>
     *
     * @param plan 刷新操作指令
     * @return 需要立即重新评估时返回true
     */
    private boolean isImmediate(OcRefreshInstructionPlan plan) {
        OcReplanWindow window = plan.replanWindow();
        Set<OcPlanReasonCodeEnum> windowReasonCodes = window.reasonCodes();
        if (windowReasonCodes.contains(OcPlanReasonCodeEnum.REPLAN_REQUIRED_NOW)
                || windowReasonCodes.contains(OcPlanReasonCodeEnum.REPLAN_LEAD_TIME_ALREADY_ENTERED)
                || windowReasonCodes.contains(OcPlanReasonCodeEnum.RANDOM_OUTCOME_CHANGED)
                || plan.reasonCodes().contains(OcPlanReasonCodeEnum.REPLAN_REQUIRED_NOW)
                || plan.reasonCodes().contains(OcPlanReasonCodeEnum.REPLAN_LEAD_TIME_ALREADY_ENTERED)
                || plan.reasonCodes().contains(OcPlanReasonCodeEnum.RANDOM_OUTCOME_CHANGED)
                || plan.reasonCodes().contains(OcPlanReasonCodeEnum.PROOF_WINDOW_EXPIRED_FOR_NEW_REFRESH)) {
            return true;
        }
        LocalDateTime snapshotTime = plan.snapshotTime();
        LocalDateTime next = window.nextReplanAt();
        LocalDateTime latest = window.latestReplanAt();
        if (next == null || latest == null) {
            return plan.nextCriticalReleaseAt() == null;
        }
        if (next.equals(snapshotTime)) {
            return true;
        }
        if (latest.isBefore(snapshotTime)) {
            return true;
        }
        return latest.equals(snapshotTime) && !next.isAfter(snapshotTime);
    }

    /**
     * 判断是否可输出正常时间范围。
     *
     * <p>起点不晚于终点时输出时间范围；单点窗口按正常范围处理，
     * 只有明确立即重评估的等点窗口才在调用前被转换为“现在”。</p>
     *
     * @param next   建议变化事件时间
     * @param latest 最晚重新评估时间
     * @return 可输出正常时间范围时返回true
     */
    private boolean hasNormalRange(LocalDateTime next, LocalDateTime latest) {
        return next != null && latest != null && !next.isAfter(latest);
    }

    /**
     * 判断是否存在可用于等待的明确未来关键成员释放事件。
     *
     * @param plan 刷新操作指令
     * @return 存在未来关键成员释放事件时返回true
     */
    private boolean hasWaitingCriticalEvent(OcRefreshInstructionPlan plan) {
        return plan.nextCriticalReleaseAt() != null
                && plan.nextCriticalReleaseAt().isAfter(plan.snapshotTime());
    }

    /**
     * 追加精简的当前OC状态区块。
     *
     * @param result 输出缓冲区
     * @param plan   刷新操作指令
     */
    private void appendCurrentStatus(StringBuilder result, OcRefreshInstructionPlan plan) {
        OcCurrentOccupancySummary summary = plan.occupancySummary();
        result.append("\n\n【当前OC状态】")
                .append("\n当前队伍: ").append(summary.currentTeamCount())
                .append("个（含").append(summary.emptyTeamCount()).append("个无人OC）")
                .append("\n实际占用成员: ").append(summary.occupiedMemberCount()).append("人")
                .append("\n空闲达标成员: ").append(summary.idleQualifiedMemberCount()).append("人");
        if (plan.nextCriticalReleaseAt() != null) {
            result.append("\n下一批关键成员预计 ")
                    .append(plan.nextCriticalReleaseAt().format(TIME_FORMAT)).append(" 释放");
        } else {
            result.append("\n").append(NO_NEXT_CRITICAL_RELEASE_TEXT);
        }
    }
}
