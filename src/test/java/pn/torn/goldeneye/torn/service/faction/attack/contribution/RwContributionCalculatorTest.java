package pn.torn.goldeneye.torn.service.faction.attack.contribution;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * RW真赛贡献分计算器测试。
 *
 * <p>本类是 §2 得分口径的唯一完整边界层，门槛与档位边界只在此处穷举，
 * 上层服务不重复断言同一规则。</p>
 *
 * @author Bai
 * @version 1.6.4
 * @since 2026.09.16
 */
@DisplayName("RW真赛贡献分计算器测试")
class RwContributionCalculatorTest {
    private final RwContributionCalculator calculator = new RwContributionCalculator();

    @Test
    @DisplayName("对手得分3000与6000之间统一按0.5系数计算")
    void coefficient_betweenThresholds_returnsLowCoefficient() {
        assertCoefficient("0.5", 3001);
        assertCoefficient("0.5", 3752);
        assertCoefficient("0.5", 5999);
    }

    @Test
    @DisplayName("对手得分不低于6000时按1.0加档位递增")
    void coefficient_stepsUpBySixThousand() {
        assertCoefficient("1.0", 6000);
        assertCoefficient("1.0", 11999);
        assertCoefficient("1.1", 12000);
        assertCoefficient("1.1", 15000);
        assertCoefficient("1.1", 16126);
        assertCoefficient("1.1", 17999);
        assertCoefficient("1.2", 18000);
        assertCoefficient("1.2", 22625);
        assertCoefficient("1.3", 24000);
    }

    @Test
    @DisplayName("对手得分不大于3000属于无效场次并抛出参数异常")
    void coefficient_invalidOpponentScore_throwsException() {
        assertThrows(IllegalArgumentException.class, () -> calculator.coefficient(3000));
        assertThrows(IllegalArgumentException.class, () -> calculator.coefficient(0));
        assertThrows(IllegalArgumentException.class, () -> calculator.coefficient(-1));
    }

    @Test
    @DisplayName("基础分为101减去战神榜名次")
    void baseScore_isRadixMinusRank() {
        assertEquals(100, calculator.baseScore(1));
        assertEquals(92, calculator.baseScore(9));
        assertEquals(51, calculator.baseScore(50));
        assertEquals(1, calculator.baseScore(100));
    }

    @Test
    @DisplayName("单场得分等于基础分乘系数并保留一位小数")
    void warScore_multipliesBaseScoreByCoefficient() {
        assertEquals(new BigDecimal("120.0"), calculator.warScore(1, 22625));
        assertEquals(new BigDecimal("110.4"), calculator.warScore(9, 22625));
        assertEquals(new BigDecimal("50.0"), calculator.warScore(1, 3752));
        assertEquals(new BigDecimal("110.0"), calculator.warScore(1, 16126));
        assertEquals(new BigDecimal("120.0"), calculator.warScore(1, 18000));
        assertEquals(1, calculator.warScore(47, 22625).scale());
    }

    @Test
    @DisplayName("单场得分对无效对手得分同样拒绝计算")
    void warScore_invalidOpponentScore_throwsException() {
        assertThrows(IllegalArgumentException.class, () -> calculator.warScore(1, 3000));
    }

    private void assertCoefficient(String expected, int opponentScore) {
        assertEquals(0, new BigDecimal(expected).compareTo(calculator.coefficient(opponentScore)),
                "对手得分" + opponentScore + "的系数应为" + expected);
    }
}
