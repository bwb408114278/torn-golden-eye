package pn.torn.goldeneye.torn.service.faction.oc.planning;

import org.springframework.stereotype.Component;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcLiquidityAnchor;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcMemberInterval;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * 流动性锚点与卡死验证器。验证的是连续完成—释放能力，而不是永久保护一个OC或成员。
 *
 * <p>“释放成员能承担一个岗位”不构成锚点：锚点只能来自完整完成—释放事件。
 * 锚点替换必须以成员级占用区间证明：前一锚点释放的成员被再次投入，
 * 且新的完整完成—释放事件在窗口内产生。只有确定性矛盾或完整无截断检查证明
 * 无路径时才判定卡死，且口径限定为本次规划窗口内。</p>
 *
 * @author Bai
 * @version 1.3.0
 * @since 2026.08.15
 */
@Component
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
                .filter(java.util.Objects::nonNull)
                .min(LocalDateTime::compareTo)
                .orElse(null);
    }

    /**
     * 以成员级占用区间验证锚点链的替换路径并回填替换标记。
     *
     * <p>一个锚点被标记为替换，当且仅当前一锚点释放的成员存在从该释放时间起
     * 再次投入、且在当前锚点释放时间前完成的占用区间；单成员仅承担一个岗位
     * 而当前锚点对应的完整义务未完成释放时，不构成替换。</p>
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
        List<OcLiquidityAnchor> sorted = anchors.stream()
                .sorted(Comparator.comparing(OcLiquidityAnchor::releaseAt)
                        .thenComparing(OcLiquidityAnchor::anchorKey))
                .toList();
        List<OcLiquidityAnchor> result = new java.util.ArrayList<>(sorted.size());
        result.add(sorted.getFirst());
        for (int index = 1; index < sorted.size(); index++) {
            OcLiquidityAnchor previous = sorted.get(index - 1);
            OcLiquidityAnchor current = sorted.get(index);
            boolean replaced = memberReinvestedBeforeRelease(previous, current, intervals);
            result.add(new OcLiquidityAnchor(current.anchorKey(), current.releaseAt(),
                    current.releasedMemberCount(), replaced));
        }
        return result;
    }

    /**
     * 判断前一锚点释放的成员是否在当前锚点释放前被再次投入并完成释放。
     *
     * @param previous  前一锚点
     * @param current   当前锚点
     * @param intervals 全部成员占用区间
     * @return 存在成员级再投入证据时返回true
     */
    private boolean memberReinvestedBeforeRelease(OcLiquidityAnchor previous,
                                                  OcLiquidityAnchor current,
                                                  List<OcMemberInterval> intervals) {
        for (OcMemberInterval release : intervals) {
            if (!release.occupiedUntil().equals(previous.releaseAt())) {
                continue;
            }
            for (OcMemberInterval reinvestment : intervals) {
                if (reinvestment.userId() == release.userId()
                        && !reinvestment.occupiedFrom().isBefore(previous.releaseAt())
                        && !reinvestment.occupiedUntil().isAfter(current.releaseAt())) {
                    return true;
                }
            }
        }
        return false;
    }
}
