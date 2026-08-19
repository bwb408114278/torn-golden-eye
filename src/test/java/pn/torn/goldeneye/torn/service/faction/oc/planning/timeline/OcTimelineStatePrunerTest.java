package pn.torn.goldeneye.torn.service.faction.oc.planning.timeline;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.torn.service.faction.oc.planning.timeline.OcTimelineStatePruner.DominanceDimensions;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 时间线搜索状态裁剪器测试。聚焦支配判定的剩余义务签名等价与稀缺岗位覆盖要求。
 *
 * @author Bai
 * @version 1.3.0
 * @since 2026.08.15
 */
@DisplayName("时间线搜索状态裁剪")
class OcTimelineStatePrunerTest {
    private final OcTimelineStatePruner pruner = new OcTimelineStatePruner();

    /**
     * 测试用状态元组。
     */
    private record State(int scheduled, int anchors, long pauseNanos, long availabilitySum,
                         String remainingSignature, long scarceSlotCoverage) {
    }

    @Test
    @DisplayName("应移除被支配的状态")
    void shouldRemoveDominatedStates() {
        State dominated = new State(2, 1, 100, 50, "oc:1", 3);
        State dominant = new State(3, 2, 100, 50, "oc:1", 3);

        OcTimelineStatePruner.PruneResult<State> result = pruner.prune(
                List.of(dominated, dominant), dimensions());

        assertEquals(List.of(dominant), result.kept());
        assertFalse(result.truncated());
    }

    @Test
    @DisplayName("互不支配的状态应全部保留")
    void shouldKeepAllNonDominatedStates() {
        State moreScheduled = new State(3, 1, 100, 80, "oc:1", 3);
        State earlierRelease = new State(2, 1, 100, 50, "oc:1", 3);

        OcTimelineStatePruner.PruneResult<State> result = pruner.prune(
                List.of(moreScheduled, earlierRelease), dimensions());

        assertEquals(2, result.kept().size());
        assertFalse(result.truncated());
    }

    @Test
    @DisplayName("剩余义务签名不同时不得仅凭可用时间合计相等判定支配")
    void shouldNotDominateWhenRemainingSignaturesDiffer() {
        State fewerObligations = new State(3, 1, 100, 50, "oc:1", 3);
        State moreObligations = new State(2, 1, 100, 50, "oc:1|oc:2", 3);

        OcTimelineStatePruner.PruneResult<State> result = pruner.prune(
                List.of(fewerObligations, moreObligations), dimensions());

        assertEquals(2, result.kept().size(), "剩余义务不同的状态互不支配，必须全部保留");
        assertFalse(result.truncated());
    }

    @Test
    @DisplayName("稀缺岗位覆盖不同时同签名状态不得错误支配剪枝")
    void shouldNotDominateWhenScarceSlotCoverageDiffers() {
        State wideCoverage = new State(2, 1, 100, 50, "oc:1", 5);
        State narrowCoverage = new State(3, 1, 100, 50, "oc:1", 1);

        OcTimelineStatePruner.PruneResult<State> result = pruner.prune(
                List.of(narrowCoverage, wideCoverage), dimensions());

        assertEquals(2, result.kept().size(), "稀缺岗位覆盖不同的状态互不支配，必须全部保留");
        assertFalse(result.truncated());
    }

    @Test
    @DisplayName("状态数量超过上限时应截断并暴露截断标记")
    void shouldTruncateAndExposeTruncationWhenStatesExceedLimit() {
        List<State> states = java.util.stream.IntStream.rangeClosed(1, pruner.maxActiveStates() + 10)
                .mapToObj(index -> new State(index, index, index, index,
                        "oc:" + index, index))
                .toList();

        OcTimelineStatePruner.PruneResult<State> result = pruner.prune(states, dimensions());

        assertTrue(result.kept().size() <= pruner.maxActiveStates());
        assertTrue(result.truncated());
    }

    /**
     * 构造测试用支配维度。
     *
     * @return 支配维度
     */
    private DominanceDimensions<State> dimensions() {
        return new DominanceDimensions<>(State::scheduled, State::anchors,
                State::pauseNanos, State::availabilitySum, State::remainingSignature,
                State::scarceSlotCoverage);
    }
}
