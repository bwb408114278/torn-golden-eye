package pn.torn.goldeneye.napcat.strategy.faction.crime.benefit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.napcat.receive.parser.QqCommandMessage;
import pn.torn.goldeneye.napcat.strategy.faction.crime.benefit.BaseOcBenefitQueryStrategy.OcMonthParam;

import java.time.YearMonth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * OC收益查询指令月份参数解析测试。
 *
 * <p>纯函数验证月份尾段语法：单段内容消歧、两段目标+年月、未来月拒绝与非法形态拒绝，
 * 是OC收益类指令月份语法的唯一主证据。</p>
 *
 * @author Bai
 * @version 1.5.2
 * @since 2026.08.30
 */
@DisplayName("OC收益查询指令月份参数解析测试")
class BaseOcBenefitQueryStrategyTest {
    private static final YearMonth CURRENT = YearMonth.of(2026, 8);

    @Test
    @DisplayName("无参数查自己当月")
    void parseMonthParam_empty_targetsSelfCurrentMonth() {
        OcMonthParam param = BaseOcBenefitQueryStrategy.parseMonthParam(null, CURRENT);
        assertEquals("", param.targetText());
        assertEquals(CURRENT, param.month());

        param = BaseOcBenefitQueryStrategy.parseMonthParam("", CURRENT);
        assertEquals("", param.targetText());
        assertEquals(CURRENT, param.month());
    }

    @Test
    @DisplayName("单段用户ID或at标记按目标段处理并保持当月")
    void parseMonthParam_singleTargetSegment_keepsCurrentMonth() {
        OcMonthParam param = BaseOcBenefitQueryStrategy.parseMonthParam("123456", CURRENT);
        assertEquals("123456", param.targetText());
        assertEquals(CURRENT, param.month());

        String atMarker = QqCommandMessage.buildAtMarker(10001L);
        param = BaseOcBenefitQueryStrategy.parseMonthParam(atMarker, CURRENT);
        assertEquals(atMarker, param.targetText());
        assertEquals(CURRENT, param.month());
    }

    @Test
    @DisplayName("单段严格年月识别为月份并查自己")
    void parseMonthParam_singleMonthSegment_targetsSelf() {
        OcMonthParam param = BaseOcBenefitQueryStrategy.parseMonthParam("2026-07", CURRENT);
        assertEquals("", param.targetText());
        assertEquals(YearMonth.of(2026, 7), param.month());

        param = BaseOcBenefitQueryStrategy.parseMonthParam("2026-08", CURRENT);
        assertEquals("", param.targetText());
        assertEquals(CURRENT, param.month());
    }

    @Test
    @DisplayName("两段按目标段+月份尾段解析")
    void parseMonthParam_targetAndMonth_parsesBoth() {
        OcMonthParam param = BaseOcBenefitQueryStrategy.parseMonthParam("123456#2026-07", CURRENT);
        assertEquals("123456", param.targetText());
        assertEquals(YearMonth.of(2026, 7), param.month());

        String atMarker = QqCommandMessage.buildAtMarker(10001L);
        param = BaseOcBenefitQueryStrategy.parseMonthParam(atMarker + "#2026-07", CURRENT);
        assertEquals(atMarker, param.targetText());
        assertEquals(YearMonth.of(2026, 7), param.month());
    }

    @Test
    @DisplayName("未来月份拒绝")
    void parseMonthParam_futureMonth_rejected() {
        assertNull(BaseOcBenefitQueryStrategy.parseMonthParam("2026-09", CURRENT));
        assertNull(BaseOcBenefitQueryStrategy.parseMonthParam("123456#2026-09", CURRENT));
        assertNull(BaseOcBenefitQueryStrategy.parseMonthParam("2099-01", CURRENT));
    }

    @Test
    @DisplayName("两段末段非严格年月拒绝；尾部#被丢弃按单目标段处理")
    void parseMonthParam_invalidMonthTail_rejected() {
        assertNull(BaseOcBenefitQueryStrategy.parseMonthParam("123456#456", CURRENT));
        assertNull(BaseOcBenefitQueryStrategy.parseMonthParam("123456#2026-7", CURRENT));
        assertNull(BaseOcBenefitQueryStrategy.parseMonthParam("123456#2026-07 ", CURRENT));
        assertNull(BaseOcBenefitQueryStrategy.parseMonthParam("2026-07#123456", CURRENT));

        // String.split丢弃尾部空段："123456#"等价于单目标段"123456"，容忍尾部#
        OcMonthParam param = BaseOcBenefitQueryStrategy.parseMonthParam("123456#", CURRENT);
        assertEquals("123456", param.targetText());
        assertEquals(CURRENT, param.month());
    }

    @Test
    @DisplayName("段数超过上限拒绝")
    void parseMonthParam_tooManySegments_rejected() {
        assertNull(BaseOcBenefitQueryStrategy.parseMonthParam("123456#2026-07#8", CURRENT));
    }

    @Test
    @DisplayName("月份文案当月省年份、历史月带年份")
    void monthLabel_currentAndHistory() {
        YearMonth current = YearMonth.now();
        assertEquals(current.getMonthValue() + "月", BaseOcBenefitQueryStrategy.monthLabel(current));

        YearMonth history = current.minusMonths(1);
        assertEquals(history.getYear() + "年" + history.getMonthValue() + "月",
                BaseOcBenefitQueryStrategy.monthLabel(history));

        YearMonth lastYear = current.minusYears(1);
        assertEquals(lastYear.getYear() + "年" + lastYear.getMonthValue() + "月",
                BaseOcBenefitQueryStrategy.monthLabel(lastYear));
    }
}
