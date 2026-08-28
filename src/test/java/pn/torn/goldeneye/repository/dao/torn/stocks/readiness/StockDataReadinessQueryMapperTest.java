package pn.torn.goldeneye.repository.dao.torn.stocks.readiness;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;
import pn.torn.goldeneye.repository.dao.torn.stocks.TornStocksDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.TornStocksHistoryDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockMarketBar15mDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockMonthlyStateDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.TornStocksHistoryDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMonthlyStateDO;
import pn.torn.goldeneye.repository.model.torn.stocks.readiness.MonthlyEvidenceStatus;
import pn.torn.goldeneye.repository.model.torn.stocks.readiness.StockMinuteCoverage;
import pn.torn.goldeneye.repository.model.torn.stocks.readiness.StockMinuteCoverageSummary;
import pn.torn.goldeneye.torn.service.stocks.alert.market.Stock15mBarBuildService;
import pn.torn.goldeneye.torn.service.stocks.backfill.StockHistoryDataSourceEnum;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 数据就绪只读查询 Mapper 真实 PostgreSQL 测试。
 *
 * @author Bai
 * @version 1.4.8
 * @since 2026.08.23
 */
@SpringBootTest
@Tag("shared-db")
@DisplayName("数据就绪只读查询Mapper真实PostgreSQL测试")
@Transactional
@Rollback
class StockDataReadinessQueryMapperTest {

    @Autowired
    private StockDataReadinessQueryDAO queryDao;
    @Autowired
    private TornStocksHistoryDAO stocksHistoryDao;
    @Autowired
    private TornStocksDAO stocksDao;
    @Autowired
    private TornStockMarketBar15mDAO bar15mDao;
    @Autowired
    private TornStockMonthlyStateDAO monthlyStateDao;

    private static final LocalDateTime WIN_START = LocalDateTime.of(2099, 5, 1, 0, 0);
    private static final LocalDateTime WIN_END = WIN_START.plusDays(1);

    @Test
    @DisplayName("真实PG_月度证据查询_JSONB raw/adjusted正确映射且V1无新键返回null")
    void monthlyEvidenceStatuses_jsonbRawAdjustedMapped_v1KeysNull() {
        // 隔离未来月份2099-06: 插入一条V2 DRAFT(带raw/adjusted/exclusion键)与一条V1 DRAFT(无新键)
        LocalDate v2Month = LocalDate.of(2099, 6, 1);
        LocalDate v1Month = LocalDate.of(2099, 7, 1);
        TornStockMonthlyStateDO v2State = monthlyState(990601, v2Month);
        v2State.setPersonalityRuleVersion("PERSONALITY_RULE_V2_OUTAGE_EXCLUSION");
        v2State.setRiskRuleVersion("RISK_RULE_V2_OUTAGE_EXCLUSION");
        v2State.setStateStatus("DRAFT");
        v2State.setMetricSnapshot("{\"rawPersonality\":null,\"rawUsableBarCoverage\":0.994351,"
                + "\"rawMaxMissingBucketGap\":450,\"usableBarCoverage\":0.999468,"
                + "\"maxMissingBucketGap\":45,\"excludedBucketCount\":29,\"excludedMinutes\":435,"
                + "\"appliedExclusionIds\":[\"TORN_MARKET_OUTAGE_20260214_0801_1515\"],"
                + "\"incompleteReason\":\"MONTHLY_EVIDENCE_INCOMPLETE\"}");
        monthlyStateDao.save(v2State);
        TornStockMonthlyStateDO v1State = monthlyState(990701, v1Month);
        v1State.setPersonalityRuleVersion("PERSONALITY_RULE_V1");
        v1State.setRiskRuleVersion("RISK_RULE_V1_SHADOW");
        v1State.setStateStatus("CONFIRMED");
        // CHECK约束: CONFIRMED行必须完整携带风格/风险/建议与确认审计字段
        v1State.setStrategyFitPrior("STEADY");
        v1State.setRiskLevel("NONE");
        v1State.setSuggestedPersonality("STEADY");
        v1State.setConfirmedAt(LocalDateTime.now());
        v1State.setConfirmedBy("TEST");
        v1State.setMetricSnapshot("{\"rawPersonality\":\"STEADY\",\"usableBarCoverage\":0.98,"
                + "\"maxMissingBucketGap\":60}");
        monthlyStateDao.save(v1State);

        LocalDateTime start = v2Month.atStartOfDay();
        LocalDateTime end = v1Month.plusMonths(1).atStartOfDay();
        List<MonthlyEvidenceStatus> statuses = queryDao.selectMonthlyEvidenceStatuses(start, end);

        MonthlyEvidenceStatus v2Row = statuses.stream()
                .filter(s -> s.stocksId() == 990601).findFirst().orElseThrow();
        assertEquals(v2Month, v2Row.effectiveMonth());
        assertEquals("DRAFT", v2Row.stateStatus());
        assertEquals("PERSONALITY_RULE_V2_OUTAGE_EXCLUSION", v2Row.personalityRuleVersion());
        assertEquals("RISK_RULE_V2_OUTAGE_EXCLUSION", v2Row.riskRuleVersion());
        assertEquals(Double.valueOf(0.994351), v2Row.rawUsableBarCoverage(), "JSONB raw覆盖率必须正确映射");
        assertEquals(Long.valueOf(450L), v2Row.rawMaxMissingBucketGap(), "JSONB raw最大间隔必须正确映射");
        assertEquals(Double.valueOf(0.999468), v2Row.adjustedUsableBarCoverage());
        assertEquals(Long.valueOf(45L), v2Row.adjustedMaxMissingBucketGap());
        assertEquals(Long.valueOf(29L), v2Row.excludedBucketCount());
        assertEquals(Long.valueOf(435L), v2Row.excludedMinutes());
        assertTrue(v2Row.appliedExclusionIdsJson().contains("TORN_MARKET_OUTAGE_20260214_0801_1515"),
                "排除ID JSON必须保留");
        assertEquals("MONTHLY_EVIDENCE_INCOMPLETE", v2Row.incompleteReason());

        MonthlyEvidenceStatus v1Row = statuses.stream()
                .filter(s -> s.stocksId() == 990701).findFirst().orElseThrow();
        assertNull(v1Row.rawUsableBarCoverage(), "V1快照无raw新键时必须为null,不得解释为0");
        assertNull(v1Row.rawMaxMissingBucketGap());
        assertNull(v1Row.excludedBucketCount());
        assertNull(v1Row.excludedMinutes());
        assertNull(v1Row.appliedExclusionIdsJson());
        assertNull(v1Row.incompleteReason(), "V1完整快照无incompleteReason时为null");
        // V1快照本身含usableBarCoverage/maxMissingBucketGap(V1无排除,raw=adjusted),按真实值映射
        assertEquals(Double.valueOf(0.98), v1Row.adjustedUsableBarCoverage());
        assertEquals(Long.valueOf(60L), v1Row.adjustedMaxMissingBucketGap());

        // 月度边界: 查询范围只含2099-06/07两个月,不得泄漏窗口外月份
        assertEquals(2, statuses.size(), "范围批量查询必须只返回指定月份内的状态");
        assertTrue(statuses.stream().allMatch(s -> !s.effectiveMonth().isBefore(v2Month)
                && !s.effectiveMonth().isAfter(v1Month)));
    }

    private TornStockMonthlyStateDO monthlyState(int stocksId, LocalDate effectiveMonth) {
        TornStockMonthlyStateDO state = new TornStockMonthlyStateDO();
        state.setStocksId(stocksId);
        state.setStocksShortname("M" + stocksId % 100);
        state.setEffectiveMonth(effectiveMonth);
        state.setMaturity("M4_MATURE");
        state.setManualOverride(false);
        state.setEvidenceStartTime(effectiveMonth.minusDays(30).atStartOfDay());
        state.setEvidenceEndTime(effectiveMonth.atStartOfDay());
        state.setCalculatedAt(LocalDateTime.now());
        return state;
    }

    @Test
    @DisplayName("真实PG_空范围覆盖统计返回全空缺口而非0")
    void emptyRange_returnsFullEmptyCoverage() {
        StockMinuteCoverageSummary summary = queryDao.selectMinuteCoverageSummary(WIN_START, WIN_END);
        assertNotNull(summary);
        assertTrue(summary.stockWithoutAnyMinuteCount() > 0, "未来空窗口应识别全空股票");
        assertTrue(summary.gapSegmentCount() > 0, "全空股票应计入缺口段数");
        assertTrue(summary.totalMissingStockMinutes() > 0, "全空股票应计入累计缺失分钟");
        assertEquals(0L, queryDao.selectValidMinuteCount(WIN_START, WIN_END));
        assertEquals(0L, queryDao.selectInvalidMinuteCount(WIN_START, WIN_END));
        assertEquals(0L, queryDao.selectBarCount(WIN_START, WIN_END, Stock15mBarBuildService.BUILD_VERSION));
        assertEquals(0L, queryDao.selectFeatureCount(WIN_START, WIN_END, "1.0.0"));
    }

    @Test
    @DisplayName("真实PG_分钟范围左闭右开且逻辑删除不计入")
    void minuteRange_isHalfOpenAndDeletedExcluded() {
        int stocksId = activeStockIds().getFirst();
        insertHistory(stocksId, WIN_START);
        insertHistory(stocksId, WIN_END.minusMinutes(1));
        insertHistory(stocksId, WIN_END);
        TornStocksHistoryDO deleted = history(stocksId, WIN_START.plusMinutes(1));
        deleted.setDeleted(1);
        stocksHistoryDao.save(deleted);

        assertEquals(2L, queryDao.selectValidMinuteCount(WIN_START, WIN_END), "终点本身排除且deleted=1不计入");
        StockMinuteCoverage coverage = findCoverage(stocksId);
        assertEquals(2L, coverage.minuteCount(), "deleted=1不计入自然分钟");
        assertEquals(0L, coverage.leadingGapMinutes());
        assertEquals(0L, coverage.trailingGapMinutes());
    }

    @Test
    @DisplayName("真实PG_bar版本过滤_只统计指定版本")
    void barVersion_isFiltered() {
        int stocksId = 998201;
        LocalDateTime barStart = WIN_START;
        bar15mDao.upsertBars(List.of(
                bar(stocksId, barStart, Stock15mBarBuildService.BUILD_VERSION, true),
                bar(stocksId, barStart.plusMinutes(15), "9.9.9", true)));

        assertEquals(1L, queryDao.selectBarCount(WIN_START, WIN_END, Stock15mBarBuildService.BUILD_VERSION));
        assertEquals(1L, queryDao.selectUsableBarCount(WIN_START, WIN_END, Stock15mBarBuildService.BUILD_VERSION));
    }

    @Test
    @DisplayName("真实PG_覆盖汇总_全空/末分钟/中间缺口/0总股数")
    void coverageSummary_reportsEdgeAndEmptyGaps() {
        List<Integer> ids = activeStockIds();
        int empty = ids.get(0);
        int edge = ids.get(1);
        int internal = ids.get(2);
        int zeroShares = ids.get(3);
        insertHistory(edge, WIN_END.minusMinutes(1));
        insertHistory(internal, WIN_START.plusMinutes(1));
        insertHistory(internal, WIN_START.plusMinutes(4));
        TornStocksHistoryDO zero = history(zeroShares, WIN_START.plusMinutes(2));
        zero.setTotalShares(0L);
        stocksHistoryDao.save(zero);

        StockMinuteCoverageSummary summary = queryDao.selectMinuteCoverageSummary(WIN_START, WIN_END);
        StockMinuteCoverage emptyCoverage = findCoverage(summary, empty);
        assertNotNull(emptyCoverage);
        assertEquals(1440L, emptyCoverage.totalMissingMinutes());
        assertEquals(1440L, emptyCoverage.leadingGapMinutes());
        assertEquals(0L, emptyCoverage.trailingGapMinutes());
        assertTrue(summary.stockWithoutAnyMinuteCount() >= 1);

        StockMinuteCoverage edgeCoverage = findCoverage(summary, edge);
        assertEquals(1439L, edgeCoverage.leadingGapMinutes());
        assertEquals(0L, edgeCoverage.trailingGapMinutes());
        assertEquals(1439L, edgeCoverage.totalMissingMinutes());

        StockMinuteCoverage internalCoverage = findCoverage(summary, internal);
        assertEquals(1L, internalCoverage.leadingGapMinutes());
        assertEquals(1L, internalCoverage.internalGapSegmentCount(), "相邻+1和+4分钟之间是一个连续缺口段");
        assertEquals(2L, internalCoverage.internalMaxGapMinutes(), "该段缺口长度为2分钟");
        assertEquals(1435L, internalCoverage.trailingGapMinutes());
        assertEquals(1L + 2L + 1435L, internalCoverage.totalMissingMinutes());

        StockMinuteCoverage zeroSharesCoverage = findCoverage(summary, zeroShares);
        assertNotNull(zeroSharesCoverage);
        assertEquals(1L, zeroSharesCoverage.minuteCount(), "total_shares=0 仍应计为存在分钟事实");
        assertEquals(2L, zeroSharesCoverage.leadingGapMinutes());
        assertEquals(1437L, zeroSharesCoverage.trailingGapMinutes());
        assertEquals(1439L, zeroSharesCoverage.totalMissingMinutes());

        assertEquals(3L, queryDao.selectValidMinuteCount(WIN_START, WIN_END), "total_shares=0 不计入有效分钟");
        assertEquals(1L, queryDao.selectInvalidMinuteCount(WIN_START, WIN_END), "total_shares=0 计入非法分钟");
        assertEquals(0L, summary.duplicateMinuteGroupCount(), "唯一索引保证自然分钟无重复组");
        assertEquals(0L, summary.duplicateMinuteRedundantRowCount(), "唯一索引保证自然分钟无冗余行");
    }

    private List<Integer> activeStockIds() {
        return stocksDao.list().stream()
                .map(pn.torn.goldeneye.repository.model.torn.stocks.TornStocksDO::getId)
                .sorted()
                .limit(4)
                .toList();
    }

    private StockMinuteCoverage findCoverage(int stocksId) {
        return findCoverage(queryDao.selectMinuteCoverageSummary(WIN_START, WIN_END), stocksId);
    }

    private StockMinuteCoverage findCoverage(StockMinuteCoverageSummary summary, int stocksId) {
        return summary.coverages().stream()
                .filter(c -> c.stocksId() == stocksId)
                .findFirst()
                .orElseThrow();
    }

    private void insertHistory(int stocksId, LocalDateTime minute) {
        stocksHistoryDao.save(history(stocksId, minute));
    }

    private TornStocksHistoryDO history(int stocksId, LocalDateTime minute) {
        TornStocksHistoryDO history = new TornStocksHistoryDO();
        history.setStocksId(stocksId);
        history.setStocksName("Test Stock " + stocksId);
        history.setStocksShortname("T" + stocksId % 100);
        history.setCurrentPrice(new BigDecimal("10.00"));
        history.setMarketCap(null);
        history.setTotalShares(1000000L);
        history.setInvestors(null);
        history.setRegDateTime(minute);
        history.setDataSource(StockHistoryDataSourceEnum.TORNSY_BACKFILL.getCode());
        return history;
    }

    private TornStockMarketBar15mDO bar(int stocksId, LocalDateTime barStart, String version, boolean usable) {
        TornStockMarketBar15mDO bar = new TornStockMarketBar15mDO();
        bar.setStocksId(stocksId);
        bar.setStocksShortname("T" + stocksId % 100);
        bar.setBarStartTime(barStart);
        bar.setBarEndTime(barStart.plusMinutes(15));
        bar.setFirstSampleTime(barStart);
        bar.setLastSampleTime(barStart.plusMinutes(14));
        bar.setFirstPrice(new BigDecimal("10.00"));
        bar.setLastPrice(new BigDecimal("10.00"));
        bar.setLowPrice(new BigDecimal("10.00"));
        bar.setHighPrice(new BigDecimal("10.00"));
        bar.setSampleCount(15);
        bar.setDuplicateCount(0);
        bar.setTailGapSeconds(60);
        bar.setUsable(usable);
        bar.setBuildVersion(version);
        return bar;
    }
}
