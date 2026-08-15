package pn.torn.goldeneye.torn.service.faction.oc.planning.matching;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * OC准备阶段时间计算器测试。
 *
 * @author Bai
 * @version 1.3.0
 * @since 2026.08.15
 */
@DisplayName("OC准备阶段时间计算")
class OcPreparationTimeCalculatorTest {
    private static final LocalDateTime A_JOIN = LocalDateTime.of(2026, 7, 17, 7, 0);

    @Test
    @DisplayName("应计算首人加入后的阶段时间和理想完成时间")
    void shouldCalculateFirstJoinReadyTimeAndIdealCompletion() {
        LocalDateTime readyTime = OcPreparationTimeCalculator.nextReadyTime(null, A_JOIN);

        assertEquals(LocalDateTime.of(2026, 7, 18, 7, 0), readyTime);
        assertEquals(LocalDateTime.of(2026, 7, 23, 7, 0),
                OcPreparationTimeCalculator.idealCompletionTime(readyTime, 6, 1));
    }

    @Test
    @DisplayName("后续成员在停转前加入时应从当前阶段时间顺延")
    void shouldExtendFromCurrentReadyTimeWhenNextMemberJoinsBeforePause() {
        LocalDateTime currentReadyTime = LocalDateTime.of(2026, 7, 18, 7, 0);
        LocalDateTime bJoin = LocalDateTime.of(2026, 7, 17, 15, 0);

        LocalDateTime readyTime = OcPreparationTimeCalculator.nextReadyTime(
                currentReadyTime, bJoin);

        assertEquals(LocalDateTime.of(2026, 7, 19, 7, 0), readyTime);
        assertEquals(LocalDateTime.of(2026, 7, 23, 7, 0),
                OcPreparationTimeCalculator.idealCompletionTime(readyTime, 6, 2));
    }

    @Test
    @DisplayName("OC停转后应从成员实际加入时间重启")
    void shouldRestartFromJoinTimeWhenOcHasPaused() {
        LocalDateTime currentReadyTime = LocalDateTime.of(2026, 7, 19, 7, 0);
        LocalDateTime cJoin = LocalDateTime.of(2026, 7, 19, 9, 0);

        LocalDateTime readyTime = OcPreparationTimeCalculator.nextReadyTime(
                currentReadyTime, cJoin);

        assertEquals(LocalDateTime.of(2026, 7, 20, 9, 0), readyTime);
        assertEquals(LocalDateTime.of(2026, 7, 23, 9, 0),
                OcPreparationTimeCalculator.idealCompletionTime(readyTime, 6, 3));
    }
}
