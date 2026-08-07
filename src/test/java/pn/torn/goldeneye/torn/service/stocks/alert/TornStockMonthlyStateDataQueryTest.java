package pn.torn.goldeneye.torn.service.stocks.alert;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockMaturityEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockMonthlyStateStatusEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockRiskLevelEnum;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockMarketBar15mDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockMonthlyStateDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMonthlyStateDO;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 月度状态计算数据查询真实PostgreSQL集成测试。
 * <p>
 * 使用隔离股票ID(2099xx)与远端未来时间,通过{@code @Transactional}回滚保证零残留。
 * 验证:
 * <ul>
 *   <li>{@code selectUsableEvidenceEdges}: 只返回截止前可用bar的首尾时间;</li>
 *   <li>{@code selectUsableByStocksAndTimeRange}: 只返回指定窗口可用bar且按时间升序;</li>
 *   <li>{@code selectPreviousConfirmedByStocks}: 每支股票只返回更早且已确认的最近月份。</li>
 * </ul>
 *
 * @author Bai
 * @version 1.2.14
 * @since 2026.08.06
 */
@SpringBootTest
@Transactional
@DisplayName("月度状态计算数据查询真实PostgreSQL集成测试")
class TornStockMonthlyStateDataQueryTest {

    @Autowired
    private TornStockMarketBar15mDAO bar15mDao;
    @Autowired
    private TornStockMonthlyStateDAO monthlyStateDao;

    /**
     * 隔离测试股票ID(远离生产35支股票)
     */
    private static final int STOCK_A = 2099001;
    private static final int STOCK_B = 2099002;

    @Test
    @DisplayName("真实PG_可用bar证据首尾时间只包含截止前可用bar")
    void selectUsableEvidenceEdges_returnsOnlyUsableBeforeCutoff() {
        LocalDateTime cutoff = LocalDateTime.of(2099, 9, 1, 0, 0);
        LocalDateTime usableTime = LocalDateTime.of(2099, 8, 31, 14, 0);
        LocalDateTime unusableTime = LocalDateTime.of(2099, 8, 31, 15, 0);
        LocalDateTime afterCutoff = LocalDateTime.of(2099, 9, 1, 0, 30);

        bar15mDao.upsertBar(buildBar(STOCK_A, usableTime, true));
        bar15mDao.upsertBar(buildBar(STOCK_A, unusableTime, false));
        bar15mDao.upsertBar(buildBar(STOCK_A, afterCutoff, true));

        List<TornStockMarketBar15mDO> edges = bar15mDao.selectUsableEvidenceEdges(
                List.of(STOCK_A), cutoff, Stock15mBarBuildService.BUILD_VERSION);

        assertEquals(1, edges.size(), "每支股票应只返回一条证据首尾时间");
        TornStockMarketBar15mDO edge = edges.getFirst();
        assertEquals(usableTime, edge.getFirstSampleTime(), "首bar应为截止前最早可用bar开始时间");
        assertEquals(usableTime.plusMinutes(15), edge.getBarEndTime(),
                "末bar应为截止前最晚可用bar的桶闭合时间(忽略不可用与截止后)");
    }

    @Test
    @DisplayName("真实PG_末日23:45可用末桶_证据终点取桶闭合时间_最近已闭合月参与统计")
    void selectUsableEvidenceEdges_2345LastBucket_recentClosedMonthParticipates() {
        // 2026-08 最近完整月,末日23:45末桶为最后一个可用bar
        LocalDateTime cutoff = LocalDateTime.of(2026, 9, 1, 0, 0);
        LocalDateTime evidenceStart = LocalDateTime.of(2026, 7, 1, 0, 0);
        LocalDateTime lastBarStart = LocalDateTime.of(2026, 8, 31, 23, 45);
        // 逐日两根bar: 08:00=100、12:00=110,末日23:45=112,保证月均可用全部bar而非日末价
        for (int day = 1; day <= 31; day++) {
            LocalDate date = LocalDate.of(2026, 8, day);
            bar15mDao.upsertBar(buildBar(STOCK_A, date.atTime(8, 0), true));
            bar15mDao.upsertBar(buildBar(STOCK_A, date.atTime(12, 0), true));
        }
        bar15mDao.upsertBar(buildBar(STOCK_A, lastBarStart, true));
        // 前月7月两根bar,用于计算相邻月变化
        for (int day = 1; day <= 31; day++) {
            LocalDate date = LocalDate.of(2026, 7, day);
            bar15mDao.upsertBar(buildBar(STOCK_A, date.atTime(8, 0), true));
            bar15mDao.upsertBar(buildBar(STOCK_A, date.atTime(12, 0), true));
        }

        List<TornStockMarketBar15mDO> edges = bar15mDao.selectUsableEvidenceEdges(
                List.of(STOCK_A), cutoff, Stock15mBarBuildService.BUILD_VERSION);
        assertEquals(1, edges.size(), "每支股票应只返回一条证据首尾时间");
        TornStockMarketBar15mDO edge = edges.getFirst();
        assertEquals(LocalDateTime.of(2026, 7, 1, 8, 0), edge.getFirstSampleTime(),
                "证据起点应为最早可用bar开始时间");
        assertEquals(lastBarStart.plusMinutes(15), edge.getBarEndTime(),
                "证据终点应为末日23:45末桶的闭合时间(次日00:00),而非bar_start_time 23:45");

        // 服务级重算: 以证据终点为桶闭合时间,最近完整自然月(2026-08)必须参与月均/负月统计
        List<TornStockMarketBar15mDO> bars = bar15mDao.selectUsableByStocksAndTimeRange(
                List.of(STOCK_A), LocalDateTime.of(2026, 7, 1, 0, 0), lastBarStart,
                Stock15mBarBuildService.BUILD_VERSION);
        StockMonthlyEvidenceMetrics metrics = StockMonthlyEvidenceComputer.computeMetrics(
                evidenceStart, edge.getBarEndTime(), bars);
        assertEquals(2, metrics.completeMonthCount(),
                "证据终点取桶闭合时间后,7月与8月均应视为完整自然月");
        assertEquals(0.0, metrics.negativeMonthRatio(),
                "8月月均高于7月,负月占比应为0,证明最近已闭合月参与统计");
        assertEquals(0, metrics.negativeMonthStreak(), "最近已闭合月参与后末尾连续负月为0");
    }

    @Test
    @DisplayName("真实PG_末日22:30末桶未闭合_最近完整月fail-closed")
    void selectUsableEvidenceEdges_incompleteLastBucket_recentMonthFailClosed() {
        // 最近月8月最后一个可用bar为22:30(桶未闭合至23:59:59),8月不得视为完整自然月
        LocalDateTime cutoff = LocalDateTime.of(2026, 9, 1, 0, 0);
        LocalDateTime evidenceStart = LocalDateTime.of(2026, 7, 1, 0, 0);
        LocalDateTime lastBarStart = LocalDateTime.of(2026, 8, 31, 22, 30);
        for (int day = 1; day <= 31; day++) {
            LocalDate date = LocalDate.of(2026, 8, day);
            bar15mDao.upsertBar(buildBar(STOCK_A, date.atTime(8, 0), true));
            bar15mDao.upsertBar(buildBar(STOCK_A, date.atTime(12, 0), true));
        }
        bar15mDao.upsertBar(buildBar(STOCK_A, lastBarStart, true));
        for (int day = 1; day <= 31; day++) {
            LocalDate date = LocalDate.of(2026, 7, day);
            bar15mDao.upsertBar(buildBar(STOCK_A, date.atTime(8, 0), true));
            bar15mDao.upsertBar(buildBar(STOCK_A, date.atTime(12, 0), true));
        }

        List<TornStockMarketBar15mDO> edges = bar15mDao.selectUsableEvidenceEdges(
                List.of(STOCK_A), cutoff, Stock15mBarBuildService.BUILD_VERSION);
        TornStockMarketBar15mDO edge = edges.getFirst();
        assertEquals(lastBarStart.plusMinutes(15), edge.getBarEndTime(),
                "末桶22:30闭合时间为22:45,不覆盖月末23:59:59");

        List<TornStockMarketBar15mDO> bars = bar15mDao.selectUsableByStocksAndTimeRange(
                List.of(STOCK_A), LocalDateTime.of(2026, 7, 1, 0, 0), lastBarStart,
                Stock15mBarBuildService.BUILD_VERSION);
        StockMonthlyEvidenceMetrics metrics = StockMonthlyEvidenceComputer.computeMetrics(
                evidenceStart, edge.getBarEndTime(), bars);
        assertEquals(1, metrics.completeMonthCount(),
                "末桶未闭合时最近月8月不得计入完整自然月(fail-closed)");
    }

    @Test
    @DisplayName("真实PG_窗口内可用bar查询只返回可用且按时间升序")
    void selectUsableByStocksAndTimeRange_filtersUsableAndOrdersAsc() {
        LocalDateTime start = LocalDateTime.of(2099, 8, 1, 0, 0);
        LocalDateTime end = LocalDateTime.of(2099, 8, 31, 23, 59);
        LocalDateTime t1 = LocalDateTime.of(2099, 8, 10, 10, 0);
        LocalDateTime t2 = LocalDateTime.of(2099, 8, 20, 10, 0);
        LocalDateTime outside = LocalDateTime.of(2099, 9, 1, 10, 0);

        bar15mDao.upsertBar(buildBar(STOCK_A, t2, true));
        bar15mDao.upsertBar(buildBar(STOCK_A, t1, true));
        bar15mDao.upsertBar(buildBar(STOCK_A, t1.plusMinutes(15), false));
        bar15mDao.upsertBar(buildBar(STOCK_A, outside, true));

        List<TornStockMarketBar15mDO> bars = bar15mDao.selectUsableByStocksAndTimeRange(
                List.of(STOCK_A), start, end, Stock15mBarBuildService.BUILD_VERSION);

        assertEquals(2, bars.size(), "应只返回窗口内2个可用bar(排除不可用与窗口外)");
        assertEquals(t1, bars.get(0).getBarStartTime(), "应按时间升序返回首个bar");
        assertEquals(t2, bars.get(1).getBarStartTime(), "应按时间升序返回第二个bar");
    }

    @Test
    @DisplayName("真实PG_多股票证据首尾时间批量返回")
    void selectUsableEvidenceEdges_multiStock_batchReturn() {
        LocalDateTime cutoff = LocalDateTime.of(2099, 9, 1, 0, 0);
        LocalDateTime a1 = LocalDateTime.of(2099, 8, 1, 10, 0);
        LocalDateTime a2 = LocalDateTime.of(2099, 8, 15, 10, 0);
        LocalDateTime b1 = LocalDateTime.of(2099, 8, 2, 10, 0);
        LocalDateTime b2 = LocalDateTime.of(2099, 8, 16, 10, 0);

        bar15mDao.upsertBar(buildBar(STOCK_A, a1, true));
        bar15mDao.upsertBar(buildBar(STOCK_A, a2, true));
        bar15mDao.upsertBar(buildBar(STOCK_B, b1, true));
        bar15mDao.upsertBar(buildBar(STOCK_B, b2, true));

        List<TornStockMarketBar15mDO> edges = bar15mDao.selectUsableEvidenceEdges(
                List.of(STOCK_A, STOCK_B), cutoff, Stock15mBarBuildService.BUILD_VERSION);

        assertEquals(2, edges.size(), "应返回两支股票各一条证据边");
        Map<Integer, TornStockMarketBar15mDO> byStock = edges.stream()
                .collect(Collectors.toMap(TornStockMarketBar15mDO::getStocksId, e -> e));
        assertEquals(a2.plusMinutes(15), byStock.get(STOCK_A).getBarEndTime());
        assertEquals(b2.plusMinutes(15), byStock.get(STOCK_B).getBarEndTime());
    }

    @Test
    @DisplayName("真实PG_每支股票只返回更早且已确认的最近月份")
    void selectPreviousConfirmedByStocks_returnsOnlyEarlierConfirmedNearest() {
        LocalDate targetMonth = LocalDate.of(2099, 9, 1);
        LocalDate monthM1 = LocalDate.of(2099, 8, 1);
        LocalDate monthM2 = LocalDate.of(2099, 7, 1);

        // A: 更早7月CONFIRMED + 8月CONFIRMED -> 应返回8月(最近)
        monthlyStateDao.insertDraftStatesIgnoreConflict(List.of(
                buildState(STOCK_A, monthM2, StockMonthlyStateStatusEnum.CONFIRMED.getCode()),
                buildState(STOCK_A, monthM1, StockMonthlyStateStatusEnum.CONFIRMED.getCode())));
        // B: 更早8月DRAFT + 7月CONFIRMED -> 应返回7月(忽略DRAFT)
        monthlyStateDao.insertDraftStatesIgnoreConflict(List.of(
                buildState(STOCK_B, monthM2, StockMonthlyStateStatusEnum.CONFIRMED.getCode()),
                buildState(STOCK_B, monthM1, StockMonthlyStateStatusEnum.DRAFT.getCode())));
        // 同月M0不参与(目标月本身)

        List<TornStockMonthlyStateDO> previous = monthlyStateDao.selectPreviousConfirmedByStocks(
                List.of(STOCK_A, STOCK_B), targetMonth);

        assertEquals(2, previous.size(), "应返回两支股票各一条更早CONFIRMED");
        Map<Integer, TornStockMonthlyStateDO> byStock = previous.stream()
                .collect(Collectors.toMap(TornStockMonthlyStateDO::getStocksId, s -> s));
        assertEquals(monthM1, byStock.get(STOCK_A).getEffectiveMonth(),
                "A应返回最近更早CONFIRMED月份8月");
        assertEquals(monthM2, byStock.get(STOCK_B).getEffectiveMonth(),
                "B的8月是DRAFT,应回退到7月CONFIRMED");
        assertEquals("M4_MATURE", byStock.get(STOCK_A).getMaturity());
        assertEquals("STEADY", byStock.get(STOCK_A).getStrategyFitPrior());
        assertEquals("NONE", byStock.get(STOCK_A).getRiskLevel());
    }

    @Test
    @DisplayName("真实PG_无更早确认记录时返回空列表")
    void selectPreviousConfirmedByStocks_noEarlierConfirmed_empty() {
        LocalDate targetMonth = LocalDate.of(2099, 9, 1);
        monthlyStateDao.insertDraftStatesIgnoreConflict(List.of(
                buildState(STOCK_A, LocalDate.of(2099, 8, 1), StockMonthlyStateStatusEnum.DRAFT.getCode())));

        List<TornStockMonthlyStateDO> previous = monthlyStateDao.selectPreviousConfirmedByStocks(
                List.of(STOCK_A), targetMonth);

        assertEquals(0, previous.size(), "只有更早DRAFT时不应返回任何记录");
    }

    // ==================== 辅助方法 ====================

    /**
     * 构建bar DO。
     *
     * @param stocksId  股票ID
     * @param startTime bar开始时间
     * @param usable    是否可用
     * @return bar DO
     */
    private TornStockMarketBar15mDO buildBar(int stocksId, LocalDateTime startTime, boolean usable) {
        TornStockMarketBar15mDO bar = new TornStockMarketBar15mDO();
        bar.setStocksId(stocksId);
        bar.setStocksShortname("IT" + (stocksId % 10000));
        bar.setBarStartTime(startTime);
        bar.setBarEndTime(startTime.plusMinutes(15));
        bar.setFirstSampleTime(startTime.plusMinutes(1));
        bar.setLastSampleTime(startTime.plusMinutes(14));
        bar.setFirstPrice(new BigDecimal("100.00"));
        bar.setLastPrice(new BigDecimal("100.50"));
        bar.setLowPrice(new BigDecimal("99.90"));
        bar.setHighPrice(new BigDecimal("100.60"));
        bar.setSampleCount(12);
        bar.setDuplicateCount(0);
        bar.setTailGapSeconds(60);
        bar.setUsable(usable);
        bar.setQualityReason(usable ? null : "TAIL_GAP_TOO_LARGE");
        bar.setBuildVersion(Stock15mBarBuildService.BUILD_VERSION);
        return bar;
    }

    /**
     * 构建月度状态DO(全部NOT NULL字段填充)。
     *
     * @param stocksId       股票ID
     * @param effectiveMonth 生效月份
     * @param stateStatus    状态
     * @return 月度状态DO
     */
    private TornStockMonthlyStateDO buildState(int stocksId, LocalDate effectiveMonth, String stateStatus) {
        TornStockMonthlyStateDO state = new TornStockMonthlyStateDO();
        state.setStocksId(stocksId);
        state.setStocksShortname("IT" + (stocksId % 10000));
        state.setEffectiveMonth(effectiveMonth);
        state.setStrategyFitPrior("STEADY");
        state.setMaturity(StockMaturityEnum.M4_MATURE.getCode());
        state.setRiskLevel(StockRiskLevelEnum.NONE.getCode());
        state.setSuggestedPersonality("STEADY");
        state.setPreviousPersonality(null);
        state.setManualOverride(false);
        state.setOverrideReason(null);
        state.setMetricSnapshot("{\"rawPersonality\":\"STEADY\",\"rawRiskLevel\":\"NONE\"}");
        state.setPersonalityRuleVersion(StockMonthlyStateCalculator.PERSONALITY_RULE_VERSION);
        state.setRiskRuleVersion(StockMonthlyStateCalculator.RISK_RULE_VERSION);
        state.setEvidenceStartTime(LocalDateTime.of(2098, 1, 1, 0, 0));
        state.setEvidenceEndTime(LocalDateTime.of(2099, 8, 1, 0, 0));
        state.setStateStatus(stateStatus);
        state.setCalculatedAt(LocalDateTime.now());
        state.setConfirmedAt(StockMonthlyStateStatusEnum.CONFIRMED.getCode().equals(stateStatus)
                ? LocalDateTime.now() : null);
        state.setConfirmedBy(StockMonthlyStateStatusEnum.CONFIRMED.getCode().equals(stateStatus)
                ? "IT_TEST" : null);
        return state;
    }
}
