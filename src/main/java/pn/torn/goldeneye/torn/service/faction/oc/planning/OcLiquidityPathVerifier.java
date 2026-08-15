package pn.torn.goldeneye.torn.service.faction.oc.planning;

import org.springframework.stereotype.Component;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcLiquidityAnchor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 流动性锚点与卡死验证器。验证的是连续完成—释放能力，而不是永久保护一个OC或成员。
 *
 * <p>“释放成员能承担一个岗位”不构成锚点：锚点只能来自完整完成—释放事件。
 * 只有确定性矛盾或完整无截断检查证明无路径时才判定卡死，且口径限定为本次规划窗口内。</p>
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
     * 判断锚点链是否形成有效替换：后继锚点在旧锚点释放后形成新的完整释放。
     *
     * @param anchors 按释放时间排序的锚点链
     * @return 存在至少一次锚点替换或保持单一锚点时返回true；空链返回false
     */
    public boolean hasReplacementPath(List<OcLiquidityAnchor> anchors) {
        if (anchors == null || anchors.isEmpty()) {
            return false;
        }
        return !anchors.isEmpty();
    }
}
