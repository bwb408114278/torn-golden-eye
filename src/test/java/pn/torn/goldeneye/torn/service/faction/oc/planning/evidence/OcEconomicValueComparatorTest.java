package pn.torn.goldeneye.torn.service.faction.oc.planning.evidence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 候选时间线经济价值比较器测试。
 *
 * @author Bai
 * @version 1.3.0
 * @since 2026.08.15
 */
@DisplayName("候选时间线经济价值比较")
class OcEconomicValueComparatorTest {
    private final OcEconomicValueComparator comparator = new OcEconomicValueComparator();
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 1, 8, 0);

    @Test
    @DisplayName("窗口总价值更高者更优")
    void shouldPreferHigherWindowValue() {
        int result = comparator.compare(BigDecimal.valueOf(500), 10, NOW,
                BigDecimal.valueOf(300), 10, NOW);

        assertTrue(result < 0);
    }

    @Test
    @DisplayName("总价值接近时应按增量单位成员人天比较")
    void shouldComparePerMemberDayWhenValueEqual() {
        int result = comparator.compare(BigDecimal.valueOf(300), 10, NOW,
                BigDecimal.valueOf(300), 21, NOW);

        assertTrue(result < 0);
    }

    @Test
    @DisplayName("价值与人天相同时更早释放者更优")
    void shouldPreferEarlierReleaseWhenValueEqual() {
        int result = comparator.compare(BigDecimal.valueOf(300), 10, NOW.plusHours(8),
                BigDecimal.valueOf(300), 10, NOW.plusHours(16));

        assertTrue(result < 0);
    }

    @Test
    @DisplayName("金额证据不足时不得据价值区分候选")
    void shouldNotDistinguishWhenValueEvidenceInsufficient() {
        int result = comparator.compare(null, 10, NOW, BigDecimal.valueOf(300), 10, NOW);

        assertEquals(0, result);
    }
}
