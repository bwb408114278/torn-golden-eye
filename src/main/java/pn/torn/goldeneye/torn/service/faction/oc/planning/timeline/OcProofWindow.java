package pn.torn.goldeneye.torn.service.faction.oc.planning.timeline;

import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlanReasonCodeEnum;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * 一次时间线求解的有限证明窗口。
 *
 * @param proofWindowEnd    证明窗口结束时间；失效窗口收敛为快照时间
 * @param newRefreshBlocked 是否因操作提前区间已进入而阻断新增刷新
 * @param reasonCodes       窗口原因码集合
 * @author Bai
 * @version 1.3.0
 * @since 2026.08.15
 */
public record OcProofWindow(
        LocalDateTime proofWindowEnd,
        boolean newRefreshBlocked,
        Set<OcPlanReasonCodeEnum> reasonCodes) {
    public OcProofWindow {
        reasonCodes = reasonCodes == null ? Set.of() : Set.copyOf(reasonCodes);
    }

    /**
     * 构造一个有效证明窗口。
     *
     * @param proofWindowEnd 证明窗口结束时间
     * @return 有效证明窗口
     */
    public static OcProofWindow valid(LocalDateTime proofWindowEnd) {
        return new OcProofWindow(proofWindowEnd, false, Set.of());
    }

    /**
     * 构造一个失效证明窗口：只保留现状评估，阻断新增刷新。
     *
     * @param snapshotTime 快照时间
     * @return 失效证明窗口
     */
    public static OcProofWindow expired(LocalDateTime snapshotTime) {
        return new OcProofWindow(snapshotTime, true, Set.of(
                OcPlanReasonCodeEnum.REPLAN_LEAD_TIME_ALREADY_ENTERED,
                OcPlanReasonCodeEnum.PROOF_WINDOW_EXPIRED_FOR_NEW_REFRESH));
    }
}
