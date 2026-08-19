package pn.torn.goldeneye.torn.service.faction.oc.planning.timeline;

import pn.torn.goldeneye.torn.model.faction.crime.planning.OcLiquidityAnchor;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcMemberInterval;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 流动性锚点与卡死验证器。验证的是连续完成—释放能力，而不是永久保护一个OC或成员。
 *
 * <p>“释放成员能承担一个岗位”不构成锚点：锚点只能来自完整完成—释放事件。
 * 锚点替换必须以成员级占用区间证明：前一锚点释放的成员被再次投入，
 * 且新的完整完成—释放事件在证明窗口内产生。只有确定性矛盾或完整无截断检查证明
 * 无路径时才判定卡死，且口径限定为本次规划窗口内。</p>
 *
 * @author Bai
 * @version 1.3.0
 * @since 2026.08.15
 */
public class OcLiquidityPathVerifier {

    /**
     * 验证候选时间线是否保留连续流动性锚点。
     *
     * @param anchors 候选时间线的完成—释放锚点链
     * @return 至少存在一个已证明完整释放锚点时返回true
     */
    public boolean hasContinuousAnchor(List<OcLiquidityAnchor> anchors) {
        return anchors != null && !anchors.isEmpty()
                && anchors.stream().anyMatch(anchor -> anchor.releasedMemberCount() > 0);
    }

    /**
     * 获取下一关键成员释放时间。
     *
     * @param anchors 候选时间线的完成—释放锚点链
     * @return 最早释放时间；无锚点时返回null
     */
    public LocalDateTime nextCriticalReleaseAt(List<OcLiquidityAnchor> anchors) {
        return anchors == null ? null : anchors.stream()
                .map(OcLiquidityAnchor::releaseAt)
                .filter(Objects::nonNull)
                .min(LocalDateTime::compareTo)
                .orElse(null);
    }

    /**
     * 以成员级占用区间验证锚点链的替换路径并回填替换标记。
     *
     * <p>一个锚点被标记为替换，当且仅当前一锚点释放的成员从该释放时间起再次投入，
     * 且其占用区间恰好在当前锚点对应的完整义务完成释放时结束；
     * 仅同一成员存在后续区间、或仅存在无关的更晚锚点，均不构成替换。</p>
     *
     * @param anchors   按释放时间排序的锚点链
     * @param intervals 候选时间线的全部成员占用区间
     * @return 回填替换标记后的锚点链
     */
    public List<OcLiquidityAnchor> verifyReplacementAnchors(List<OcLiquidityAnchor> anchors,
                                                            List<OcMemberInterval> intervals) {
        if (anchors == null || anchors.size() <= 1) {
            return anchors == null ? List.of() : anchors;
        }
        List<OcLiquidityAnchor> sorted = sortedAnchors(anchors);
        List<OcLiquidityAnchor> result = new ArrayList<>(sorted.size());
        result.add(sorted.getFirst());
        for (int index = 1; index < sorted.size(); index++) {
            OcLiquidityAnchor previous = sorted.get(index - 1);
            OcLiquidityAnchor current = sorted.get(index);
            boolean replaced = memberReinvestedAtRelease(previous, current, intervals);
            result.add(new OcLiquidityAnchor(current.anchorKey(), current.releaseAt(),
                    current.releasedMemberCount(), replaced));
        }
        return result;
    }

    /**
     * 验证锚点链拥有贯穿有限证明窗口的连续完成—释放路径。
     *
     * <p>释放资源已被后续义务消耗的窗口内锚点，必须证明该资源参与形成了
     * 落在证明窗口内的下一次完整完成—释放事件：前锚点资源释放、
     * 已投入义务仍能完整履行、前资源（或已证明的等价替代资源）形成新的完整完成—释放、
     * 新释放事件不晚于证明窗口结束。仅同一成员存在后续区间，
     * 或仅存在无关锚点，均不构成连续性；窗口外锚点不消耗窗口内流动性证明。
     * {@code proofWindowEnd}为null时不施加窗口约束，对全部锚点做结构性连续判定，
     * 仅允许用于独立结构性单测或明确无窗口的非生产调用；生产时间线完成分支
     * 必须传入当前模拟的非空有限证明窗口。</p>
     *
     * @param anchors        候选时间线的完成—释放锚点链
     * @param intervals      候选时间线的全部成员占用区间
     * @param proofWindowEnd 证明窗口结束时间；null表示不施加窗口约束
     * @return 全部被消耗的窗口内锚点均存在窗口内接续释放时返回true
     */
    public boolean hasContinuousCompletionPath(List<OcLiquidityAnchor> anchors,
                                               List<OcMemberInterval> intervals,
                                               LocalDateTime proofWindowEnd) {
        if (anchors == null || anchors.isEmpty()) {
            return true;
        }
        List<OcLiquidityAnchor> sorted = sortedAnchors(anchors);
        for (int index = 0; index < sorted.size(); index++) {
            OcLiquidityAnchor anchor = sorted.get(index);
            if (anchor.releaseAt() != null && proofWindowEnd != null
                    && anchor.releaseAt().isAfter(proofWindowEnd)) {
                continue;
            }
            if (consumedWithoutInWindowSuccessor(anchor, sorted, index, intervals,
                    proofWindowEnd)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 判断窗口内锚点是否在释放资源被消耗后缺少窗口内的接续完整释放。
     *
     * @param anchor         待检查锚点
     * @param sorted         按释放时间排序的锚点链
     * @param anchorIndex    待检查锚点在链中的下标
     * @param intervals      全部成员占用区间
     * @param proofWindowEnd 证明窗口结束时间
     * @return 资源被消耗且窗口内无成员级接续释放时返回true
     */
    private boolean consumedWithoutInWindowSuccessor(OcLiquidityAnchor anchor,
                                                     List<OcLiquidityAnchor> sorted,
                                                     int anchorIndex,
                                                     List<OcMemberInterval> intervals,
                                                     LocalDateTime proofWindowEnd) {
        Set<Long> reinvestedMembers = releasedMembers(anchor, intervals).stream()
                .filter(userId -> hasLaterInterval(userId, anchor.releaseAt(), intervals))
                .collect(Collectors.toSet());
        if (reinvestedMembers.isEmpty()) {
            return false;
        }
        for (int successorIndex = anchorIndex + 1; successorIndex < sorted.size();
             successorIndex++) {
            OcLiquidityAnchor successor = sorted.get(successorIndex);
            if (successor.releaseAt() != null && proofWindowEnd != null
                    && successor.releaseAt().isAfter(proofWindowEnd)) {
                continue;
            }
            if (successorFormedByMembers(successor, reinvestedMembers, anchor, intervals)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 判断接续锚点是否由被消耗锚点释放的成员参与形成。
     *
     * @param successor         接续锚点
     * @param reinvestedMembers 被消耗锚点释放并再次投入的成员集合
     * @param anchor            被消耗锚点
     * @param intervals         全部成员占用区间
     * @return 存在成员级再投入恰好在该锚点释放时完成时返回true
     */
    private boolean successorFormedByMembers(OcLiquidityAnchor successor,
                                             Set<Long> reinvestedMembers,
                                             OcLiquidityAnchor anchor,
                                             List<OcMemberInterval> intervals) {
        if (successor.releaseAt() == null || anchor.releaseAt() == null) {
            return false;
        }
        for (OcMemberInterval interval : intervals) {
            if (reinvestedMembers.contains(interval.userId())
                    && !interval.occupiedFrom().isBefore(anchor.releaseAt())
                    && interval.occupiedUntil().equals(successor.releaseAt())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 获取在指定锚点释放的成员集合。
     *
     * @param anchor    锚点
     * @param intervals 全部成员占用区间
     * @return 占用区间恰好在该锚点释放时结束的成员ID集合
     */
    private Set<Long> releasedMembers(OcLiquidityAnchor anchor,
                                      List<OcMemberInterval> intervals) {
        return intervals.stream()
                .filter(interval -> interval.occupiedUntil().equals(anchor.releaseAt()))
                .map(OcMemberInterval::userId)
                .collect(Collectors.toSet());
    }

    /**
     * 判断成员在指定时间后是否存在再次投入的占用区间。
     *
     * @param userId    成员用户ID
     * @param releaseAt 释放时间
     * @param intervals 全部成员占用区间
     * @return 存在开始时间不早于释放时间的区间时返回true
     */
    private boolean hasLaterInterval(long userId, LocalDateTime releaseAt,
                                     List<OcMemberInterval> intervals) {
        return intervals.stream().anyMatch(interval -> interval.userId() == userId
                && !interval.occupiedFrom().isBefore(releaseAt)
                && interval.occupiedUntil().isAfter(releaseAt));
    }

    /**
     * 按释放时间和锚点键稳定排序锚点链。
     *
     * @param anchors 原始锚点链
     * @return 排序后的锚点链
     */
    private List<OcLiquidityAnchor> sortedAnchors(List<OcLiquidityAnchor> anchors) {
        return anchors.stream()
                .sorted(Comparator.comparing(OcLiquidityAnchor::releaseAt,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(OcLiquidityAnchor::anchorKey))
                .toList();
    }

    /**
     * 判断前一锚点释放的成员是否恰好投入当前锚点对应的完整义务并随其释放。
     *
     * @param previous  前一锚点
     * @param current   当前锚点
     * @param intervals 全部成员占用区间
     * @return 存在成员级再投入且恰在当前锚点释放时完成时返回true
     */
    private boolean memberReinvestedAtRelease(OcLiquidityAnchor previous,
                                              OcLiquidityAnchor current,
                                              List<OcMemberInterval> intervals) {
        for (OcMemberInterval release : intervals) {
            if (!release.occupiedUntil().equals(previous.releaseAt())) {
                continue;
            }
            for (OcMemberInterval reinvestment : intervals) {
                if (reinvestment.userId() == release.userId()
                        && !reinvestment.occupiedFrom().isBefore(previous.releaseAt())
                        && reinvestment.occupiedUntil().equals(current.releaseAt())) {
                    return true;
                }
            }
        }
        return false;
    }
}
