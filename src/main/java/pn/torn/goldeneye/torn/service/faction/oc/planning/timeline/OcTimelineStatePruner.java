package pn.torn.goldeneye.torn.service.faction.oc.planning.timeline;


import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.ToLongFunction;

/**
 * 时间线搜索状态裁剪器，是时间线事件推进器多状态搜索的实际引擎组件。
 * 按已排程数量、锚点、停转、成员可用性、剩余义务签名和稀缺岗位覆盖做支配剪枝，
 * 并限制同时展开的状态数，防止单节点排列的阶乘搜索。
 *
 * <p>支配关系至少要求剩余义务签名等价：签名不同的状态剩余义务不同，
 * 不得仅凭成员可用时间合计相等而互相支配，否则会丢弃连续性信息。
 * 状态上限截断必须由调用方准确记录为{@code UNPROVEN_SEARCH_BUDGET}，
 * 不得据此返回已证明不可行或卡死；剪枝本身不改变已证明安全向量的语义。</p>
 *
 * @author Bai
 * @version 1.3.0
 * @since 2026.08.15
 */
public class OcTimelineStatePruner {

    /**
     * 裁剪结果。
     *
     * @param kept      保留的非支配状态，保持稳定顺序
     * @param truncated 是否因状态上限截断了候选状态
     * @param <T>       状态类型
     */
    public record PruneResult<T>(List<T> kept, boolean truncated) {
    }

    /**
     * 支配判定的全部维度。数值维度越大越优时以越大为不差，停转以越小为不差；
     * 剩余义务签名必须完全等价才允许支配。
     *
     * @param scheduledCount     已排程义务数量维度
     * @param anchorCount        锚点数量维度
     * @param pauseNanos         新增停转时长纳秒维度
     * @param availabilitySum    成员可用时间合计维度
     * @param remainingSignature 剩余义务稳定签名维度
     * @param scarceSlotCoverage 剩余义务稀缺岗位的可用成员覆盖数维度
     * @param <T>                状态类型
     */
    public record DominanceDimensions<T>(
            ToLongFunction<T> scheduledCount,
            ToLongFunction<T> anchorCount,
            ToLongFunction<T> pauseNanos,
            ToLongFunction<T> availabilitySum,
            Function<T, String> remainingSignature,
            ToLongFunction<T> scarceSlotCoverage) {
    }

    /**
     * 保留非支配状态并限制状态数量。当且仅当某状态在全部维度不差、
     * 剩余义务签名等价且至少一维更优时支配另一状态。
     *
     * @param states     当前候选状态列表，按探索顺序排列
     * @param dimensions 支配判定维度
     * @param <T>        状态类型
     * @return 裁剪结果
     */
    public <T> PruneResult<T> prune(List<T> states, DominanceDimensions<T> dimensions) {
        List<T> kept = new ArrayList<>();
        boolean truncated = false;
        for (T state : states) {
            if (kept.size() >= OcSearchBudgetLimits.MAX_ACTIVE_STATES) {
                truncated = true;
                break;
            }
            appendIfNotDominated(kept, state, dimensions);
        }
        return new PruneResult<>(kept, truncated);
    }

    /**
     * 将状态加入保留集合，并移除被其支配的既有状态。
     *
     * @param kept       保留状态列表
     * @param state      待加入状态
     * @param dimensions 支配判定维度
     * @param <T>        状态类型
     */
    private <T> void appendIfNotDominated(List<T> kept, T state,
                                          DominanceDimensions<T> dimensions) {
        boolean dominated = kept.stream()
                .anyMatch(other -> dominates(other, state, dimensions));
        if (dominated) {
            return;
        }
        kept.removeIf(other -> dominates(state, other, dimensions));
        kept.add(state);
    }

    /**
     * 判断支配关系：剩余义务签名等价，dominant在全部数值维度不差且至少一维更优。
     *
     * @param dominant   候选支配状态
     * @param other      被比较状态
     * @param dimensions 支配判定维度
     * @param <T>        状态类型
     * @return dominant支配other时返回true
     */
    private <T> boolean dominates(T dominant, T other, DominanceDimensions<T> dimensions) {
        String dominantSignature = dimensions.remainingSignature().apply(dominant);
        String otherSignature = dimensions.remainingSignature().apply(other);
        if (!dominantSignature.equals(otherSignature)) {
            return false;
        }
        long dominantScheduled = dimensions.scheduledCount().applyAsLong(dominant);
        long otherScheduled = dimensions.scheduledCount().applyAsLong(other);
        long dominantAnchors = dimensions.anchorCount().applyAsLong(dominant);
        long otherAnchors = dimensions.anchorCount().applyAsLong(other);
        long dominantPause = dimensions.pauseNanos().applyAsLong(dominant);
        long otherPause = dimensions.pauseNanos().applyAsLong(other);
        long dominantAvailability = dimensions.availabilitySum().applyAsLong(dominant);
        long otherAvailability = dimensions.availabilitySum().applyAsLong(other);
        long dominantCoverage = dimensions.scarceSlotCoverage().applyAsLong(dominant);
        long otherCoverage = dimensions.scarceSlotCoverage().applyAsLong(other);
        return dominantScheduled >= otherScheduled
                && dominantAnchors >= otherAnchors
                && dominantPause <= otherPause
                && dominantAvailability <= otherAvailability
                && dominantCoverage >= otherCoverage
                && (dominantScheduled > otherScheduled
                || dominantAnchors > otherAnchors
                || dominantPause < otherPause
                || dominantAvailability < otherAvailability
                || dominantCoverage > otherCoverage);
    }

    /**
     * 获取同时展开的状态上限。
     *
     * @return 状态上限
     */
    public int maxActiveStates() {
        return OcSearchBudgetLimits.MAX_ACTIVE_STATES;
    }
}
