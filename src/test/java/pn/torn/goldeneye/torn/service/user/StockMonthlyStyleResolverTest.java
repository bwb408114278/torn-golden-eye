package pn.torn.goldeneye.torn.service.user;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pn.torn.goldeneye.constants.torn.enums.stocks.StockPersonalityEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockMaturityEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockRiskLevelEnum;
import pn.torn.goldeneye.repository.dao.torn.stocks.TornStocksDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockMonthlyStateDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMonthlyStateDO;
import pn.torn.goldeneye.torn.service.user.StockMonthlyStyleResolver.ResolvedMonthlyStyle;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 月度风格解析组件单元测试。
 * <p>
 * 覆盖月度规范§13消费口径: 当月正常使用、上月沿用、连续2个月停推、
 * 无CONFIRMED行不推荐、{@code ALPHA_NOT_EVALUATED}视为无可用风格、禁止默认STEADY。
 *
 * @author Bai
 * @version 1.8.0
 * @since 2026.10.04
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("月度风格解析组件测试")
class StockMonthlyStyleResolverTest {

    private static final int STOCKS_ID = 1;
    private static final LocalDate TARGET_MONTH = LocalDate.of(2026, 10, 1);

    @Mock
    private TornStockMonthlyStateDAO monthlyStateDao;

    @Mock
    private TornStocksDAO tornStocksDao;

    @InjectMocks
    private StockMonthlyStyleResolver resolver;

    @Test
    @DisplayName("当月有CONFIRMED行_正常使用且不标记沿用")
    void resolveAll_currentMonthConfirmed_availableWithoutCarryOver() {
        when(monthlyStateDao.selectLatestConfirmedUpToMonth(any()))
                .thenReturn(List.of(confirmed(TARGET_MONTH, "RANGING", "M3_SEASONED", "HIGH")));

        Map<Integer, ResolvedMonthlyStyle> result = resolver.resolveAll(TARGET_MONTH);

        ResolvedMonthlyStyle style = result.get(STOCKS_ID);
        assertTrue(style.styleAvailable(), "当月CONFIRMED应可用");
        assertEquals(StockPersonalityEnum.RANGING, style.personality(), "风格1:1映射评分档位");
        assertEquals(StockMaturityEnum.M3_SEASONED, style.maturity());
        assertEquals(StockRiskLevelEnum.HIGH, style.riskLevel());
        assertEquals(java.time.YearMonth.of(2026, 10), style.effectiveMonth());
        assertFalse(style.carriedOver(), "当月生效不标记沿用");
        assertNull(style.blockedReason());
    }

    @Test
    @DisplayName("最近CONFIRMED为上月_沿用该月并标记carriedOver")
    void resolveAll_lastMonthConfirmed_carriedOver() {
        when(monthlyStateDao.selectLatestConfirmedUpToMonth(any()))
                .thenReturn(List.of(confirmed(TARGET_MONTH.minusMonths(1), "STEADY", "M4_MATURE", "NONE")));

        Map<Integer, ResolvedMonthlyStyle> result = resolver.resolveAll(TARGET_MONTH);

        ResolvedMonthlyStyle style = result.get(STOCKS_ID);
        assertTrue(style.styleAvailable(), "恰好1个月沿用应可用");
        assertTrue(style.carriedOver(), "必须标记沿用,由调用方留痕");
        assertEquals(java.time.YearMonth.of(2026, 9), style.effectiveMonth());
    }

    @Test
    @DisplayName("连续2个月无新CONFIRMED_停止推荐(fail-closed)")
    void resolveAll_twoMonthsGap_blocked() {
        when(monthlyStateDao.selectLatestConfirmedUpToMonth(any()))
                .thenReturn(List.of(confirmed(TARGET_MONTH.minusMonths(2), "STEADY", "M4_MATURE", "NONE")));

        Map<Integer, ResolvedMonthlyStyle> result = resolver.resolveAll(TARGET_MONTH);

        assertFalse(result.get(STOCKS_ID).styleAvailable(), "连续2个月未生成必须停止推荐");
        assertNotNull(result.get(STOCKS_ID).blockedReason());
    }

    @Test
    @DisplayName("风格为ALPHA_NOT_EVALUATED_视为无可用风格而非风格")
    void resolveAll_alphaNotEvaluated_blocked() {
        when(monthlyStateDao.selectLatestConfirmedUpToMonth(any()))
                .thenReturn(List.of(confirmed(TARGET_MONTH, "ALPHA_NOT_EVALUATED",
                        "ALPHA_NOT_EVALUATED", "ALPHA_NOT_EVALUATED")));

        Map<Integer, ResolvedMonthlyStyle> result = resolver.resolveAll(TARGET_MONTH);

        assertFalse(result.get(STOCKS_ID).styleAvailable(),
                "ALPHA_NOT_EVALUATED是α专用伪值,不得作为私聊风格");
    }

    @Test
    @DisplayName("M1_EARLY可用(门槛加成由消费方处理);M0同样可用不拦截")
    void resolveAll_earlyAndUnmatureMaturity_available() {
        when(monthlyStateDao.selectLatestConfirmedUpToMonth(any())).thenReturn(List.of(
                confirmedWithId(1, TARGET_MONTH, "STEADY", "M1_EARLY", "NONE"),
                confirmedWithId(2, TARGET_MONTH, "STEADY", "M0_UNMATURE", "NONE")));

        Map<Integer, ResolvedMonthlyStyle> result = resolver.resolveAll(TARGET_MONTH);

        assertEquals(StockMaturityEnum.M1_EARLY, result.get(1).maturity(), "M1_EARLY必须可用");
        assertEquals(StockMaturityEnum.M0_UNMATURE, result.get(2).maturity(),
                "M0由消费方强制HOLD,解析层不拦截");
    }

    @Test
    @DisplayName("无任何CONFIRMED行_该股不在结果中,由调用方按无风格停止推荐(禁止默认STEADY)")
    void resolveAll_noConfirmedRow_absentFromResult() {
        when(monthlyStateDao.selectLatestConfirmedUpToMonth(any())).thenReturn(List.of());

        Map<Integer, ResolvedMonthlyStyle> result = resolver.resolveAll(TARGET_MONTH);

        assertFalse(result.containsKey(STOCKS_ID), "无CONFIRMED行不得产生任何风格映射");
    }

    /**
     * 构建指定月份的CONFIRMED状态行(股票ID=1)。
     *
     * @param effectiveMonth 生效月份
     * @param style          风格编码
     * @param maturity       成熟度编码
     * @param risk           风险编码
     * @return CONFIRMED状态DO
     */
    private static TornStockMonthlyStateDO confirmed(LocalDate effectiveMonth, String style,
                                                     String maturity, String risk) {
        return confirmedWithId(STOCKS_ID, effectiveMonth, style, maturity, risk);
    }

    /**
     * 构建指定股票与月份的CONFIRMED状态行。
     *
     * @param stocksId       股票ID
     * @param effectiveMonth 生效月份
     * @param style          风格编码
     * @param maturity       成熟度编码
     * @param risk           风险编码
     * @return CONFIRMED状态DO
     */
    private static TornStockMonthlyStateDO confirmedWithId(int stocksId, LocalDate effectiveMonth,
                                                           String style, String maturity, String risk) {
        TornStockMonthlyStateDO state = new TornStockMonthlyStateDO();
        state.setStocksId(stocksId);
        state.setEffectiveMonth(effectiveMonth);
        state.setStrategyFitPrior(style);
        state.setMaturity(maturity);
        state.setRiskLevel(risk);
        state.setStateStatus("CONFIRMED");
        return state;
    }
}
