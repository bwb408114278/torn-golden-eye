package pn.torn.goldeneye.torn.service.faction.oc.planning;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToLongFunction;

/**
 * 时间线搜索状态裁剪器，是时间线事件推进器多状态搜索的实际引擎组件。
 * 按已排程数量、锚点、停转和成员可用性做支配剪枝，并限制同时展开的状态数，
 * 防止单节点排列的阶乘搜索。
 *
 * <p>状态上限截断必须由调用方准确记录为{@code UNPROVEN_SEARCH_BUDGET}，
 * 不得据此返回已证明不可行或卡死；剪枝本身不改变已证明安全向量的语义。</p>
 *
 * @author Bai
 * @version 1.3.0
 * @since 2026.08.15
 */
@Component
public class OcTimelineStatePruner {
    private static final int MAX_ACTIVE_STATES = 16;

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
     * 保留非支配状态并限制状态数量。当且仅当某状态在全部维度不差且至少一维更优时支配另一状态。
     *
     * @param states          当前候选状态列表，按探索顺序排列
     * @param scheduledCount  已排程数量维度
     * @param anchorCount     锚点数量维度
     * @param pauseNanos      停转时长维度
     * @param availabilitySum 成员可用性维度
     * @param <T>             状态类型
     * @return 裁剪结果
     */
    public <T> PruneResult<T> prune(List<T> states, ToLongFunction<T> scheduledCount,
                                    ToLongFunction<T> anchorCount,
                                    ToLongFunction<T> pauseNanos,
                                    ToLongFunction<T> availabilitySum) {
        List<T> kept = new ArrayList<>();
        boolean truncated = false;
        for (T state : states) {
            if (kept.size() >= MAX_ACTIVE_STATES) {
                truncated = true;
                break;
            }
            appendIfNotDominated(kept, state, scheduledCount, anchorCount, pauseNanos,
                    availabilitySum);
        }
        return new PruneResult<>(kept, truncated);
    }

    /**
     * 将状态加入保留集合，并移除被其支配的既有状态。
     *
     * @param kept            保留状态列表
     * @param state           待加入状态
     * @param scheduledCount  已排程数量维度
     * @param anchorCount     锚点数量维度
     * @param pauseNanos      停转时长维度
     * @param availabilitySum 成员可用性维度
     * @param <T>             状态类型
     */
    private <T> void appendIfNotDominated(List<T> kept, T state,
                                          ToLongFunction<T> scheduledCount,
                                          ToLongFunction<T> anchorCount,
                                          ToLongFunction<T> pauseNanos,
                                          ToLongFunction<T> availabilitySum) {
        boolean dominated = kept.stream().anyMatch(other -> dominates(other, state,
                scheduledCount, anchorCount, pauseNanos, availabilitySum));
        if (dominated) {
            return;
        }
        kept.removeIf(other -> dominates(state, other, scheduledCount, anchorCount,
                pauseNanos, availabilitySum));
        kept.add(state);
    }

    /**
     * 判断支配关系：dominant在全部维度不差且至少一维更优。
     *
     * @param dominant        候选支配状态
     * @param other           被比较状态
     * @param scheduledCount  已排程数量维度
     * @param anchorCount     锚点数量维度
     * @param pauseNanos      停转时长维度
     * @param availabilitySum 成员可用性维度
     * @param <T>             状态类型
     * @return dominant支配other时返回true
     */
    private <T> boolean dominates(T dominant, T other, ToLongFunction<T> scheduledCount,
                                  ToLongFunction<T> anchorCount, ToLongFunction<T> pauseNanos,
                                  ToLongFunction<T> availabilitySum) {
        long dominantScheduled = scheduledCount.applyAsLong(dominant);
        long otherScheduled = scheduledCount.applyAsLong(other);
        long dominantAnchors = anchorCount.applyAsLong(dominant);
        long otherAnchors = anchorCount.applyAsLong(other);
        long dominantPause = pauseNanos.applyAsLong(dominant);
        long otherPause = pauseNanos.applyAsLong(other);
        long dominantAvailability = availabilitySum.applyAsLong(dominant);
        long otherAvailability = availabilitySum.applyAsLong(other);
        return dominantScheduled >= otherScheduled
                && dominantAnchors >= otherAnchors
                && dominantPause <= otherPause
                && dominantAvailability <= otherAvailability
                && (dominantScheduled > otherScheduled
                || dominantAnchors > otherAnchors
                || dominantPause < otherPause
                || dominantAvailability < otherAvailability);
    }

    /**
     * 获取同时展开的状态上限。
     *
     * @return 状态上限
     */
    public int maxActiveStates() {
        return MAX_ACTIVE_STATES;
    }
}
