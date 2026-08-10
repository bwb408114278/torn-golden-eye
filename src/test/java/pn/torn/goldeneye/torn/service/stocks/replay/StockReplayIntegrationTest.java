package pn.torn.goldeneye.torn.service.stocks.replay;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockMonthlyStateStatusEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockRiskLevelEnum;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.*;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMonthlyStateDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockStrategyFeature15mDO;
import pn.torn.goldeneye.torn.service.stocks.alert.Stock15mBarBuildService;
import pn.torn.goldeneye.torn.service.stocks.replay.model.StockReplayRequest;
import pn.torn.goldeneye.torn.service.stocks.replay.model.StockReplayResult;
import pn.torn.goldeneye.torn.service.stocks.replay.model.StockReplaySummary;
import pn.torn.goldeneye.torn.service.stocks.replay.model.StockReplayTrackEnum;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回放端到端真实PostgreSQL集成测试。
 * <p>
 * 使用隔离股票ID集合(远离生产股票1..35)与隔离的未来回放窗口(2099-08,生产数据之外),
 * 直接通过生产DAO(bar upsert、特征upsert与月度状态批量插入)种子bar/特征/CONFIRMED月度状态
 * 作为回放输入;输入加载使用 REQUIRES_NEW 只读事务,因此种子必须在独立事务中显式提交后才
 * 对回放可见;运行完整回放断言四类产物与摘要。全部种子数据与产物在{@code @AfterEach}按
 * 本测试唯一维度(隔离股票集合 + 测试月 + {@code REPLAY_IT_TEST})精确物理DELETE,不删除
 * 任何真实业务数据。
 *
 * @author Bai
 * @version 1.2.14
 * @since 2026.08.06
 */
@SpringBootTest
@DisplayName("回放端到端真实PostgreSQL集成测试")
class StockReplayIntegrationTest {

    @Autowired
    private TornStockMarketBar15mDAO bar15mDao;
    @Autowired
    private TornStockMonthlyStateDAO monthlyStateDao;
    @Autowired
    private TornStockStrategyFeature15mDAO featureDao;
    @Autowired
    private TornStockVirtualBatchDAO virtualBatchDao;
    @Autowired
    private TornStockSignalEventDAO signalEventDao;
    @Autowired
    private TornStockSignalStateDAO signalStateDao;
    @Autowired
    private TornStockNoticeAuditDAO noticeAuditDao;
    @Autowired
    private StockReplayRunner runner;
    @Autowired
    private NamedParameterJdbcTemplate namedJdbcTemplate;
    @Autowired
    private TransactionTemplate transactionTemplate;

    /**
     * 回放窗口起点(未来隔离时间,生产数据之外)。
     */
    private static final LocalDateTime START = LocalDateTime.of(2099, 8, 1, 9, 0);
    /**
     * 回放窗口终点。
     */
    private static final LocalDateTime END = LocalDateTime.of(2099, 8, 1, 14, 0);
    /**
     * 产物输出根目录(测试专用,结束后清理)。
     */
    private static final String OUTPUT_ROOT = "target/replay-it";
    /**
     * 种子月度状态确认人标记,用于精确物理清理。
     */
    private static final String SEED_CONFIRMED_BY = "REPLAY_IT_TEST";
    /**
     * 种子特征版本。
     */
    private static final String SEED_FEATURE_VERSION = "1.0.0";
    /**
     * 隔离股票ID集合(远离生产股票1..35,回放输入仅加载这些股票)。
     */
    private static final List<Integer> TEST_STOCKS = List.of(2099001, 2099002, 2099003, 2099004, 2099005);
    /**
     * 隔离股票短名前缀(总长不超过8字符)。
     */
    private static final String SHORTNAME_PREFIX = "I";

    @AfterEach
    void cleanupArtifacts() throws IOException {
        Path root = Paths.get(OUTPUT_ROOT);
        if (Files.exists(root)) {
            try (var walk = Files.walk(root)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (IOException e) {
                        throw new IllegalStateException("产物清理失败: " + path, e);
                    }
                });
            }
        }
        LocalDateTime cleanStart = Stock15mBarBuildService.alignToBucket(START).minusMinutes(15);
        LocalDateTime cleanEnd = Stock15mBarBuildService.alignToBucket(END).plusMinutes(15);
        namedJdbcTemplate.update(
                "DELETE FROM torn_stock_market_bar_15m WHERE stocks_id IN (:stocks) "
                        + "AND bar_start_time >= :start AND bar_start_time <= :end",
                Map.of("stocks", TEST_STOCKS, "start", cleanStart, "end", cleanEnd));
        namedJdbcTemplate.update(
                "DELETE FROM torn_stock_strategy_feature_15m WHERE stocks_id IN (:stocks) "
                        + "AND bar_start_time >= :start AND bar_start_time <= :end",
                Map.of("stocks", TEST_STOCKS, "start", cleanStart, "end", cleanEnd));
        namedJdbcTemplate.update(
                "DELETE FROM torn_stock_monthly_state WHERE effective_month = :month AND confirmed_by = :confirmedBy",
                Map.of("month", START.toLocalDate().withDayOfMonth(1), "confirmedBy", SEED_CONFIRMED_BY));
    }

    @Test
    @DisplayName("种子窗口回放产出四类产物且不写业务表")
    void seededWindow_producesFourArtifactsWithoutBusinessWrites() throws Exception {
        transactionTemplate.executeWithoutResult(status -> {
            seedBars(START, END);
            seedFeatures(START, END);
            seedMonthlyStates();
        });

        long batchesBefore = virtualBatchDao.count();
        long eventsBefore = signalEventDao.count();
        long statesBefore = signalStateDao.count();
        long noticesBefore = noticeAuditDao.count();

        StockReplayRequest request = new StockReplayRequest(
                START, END, Stock15mBarBuildService.BUILD_VERSION, SEED_FEATURE_VERSION, "1.0.0", "1.0.0",
                Set.of(StockReplayTrackEnum.values()), OUTPUT_ROOT);
        StockReplayResult result = runner.run(request);

        assertEquals("COMPLETED", result.summary().status(), "回放应正常完成");
        assertEquals(StockReplayRunner.ANNUALIZED_BACKTEST_MARKER, result.summary().marker(),
                "摘要必须携带短历史年化回放标记");

        Path dir = Paths.get(OUTPUT_ROOT, result.runId());
        for (String suffix : List.of("-summary.json", "-trades.csv", "-rejections.csv", "-equity-curve.csv")) {
            assertTrue(Files.exists(dir.resolve(result.runId() + suffix)),
                    "缺少产物: " + result.runId() + suffix);
        }

        ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        StockReplaySummary parsed = mapper
                .readValue(dir.resolve(result.runId() + "-summary.json").toFile(), StockReplaySummary.class);
        assertEquals("COMPLETED", parsed.status(), "summary.json状态必须为COMPLETED");
        assertEquals(result.runId(), parsed.runId(), "summary.json runId必须一致");
        assertTrue(parsed.tracks().stream().anyMatch(t -> StockReplayTrackEnum.FORMAL_20E.getCode().equals(t.track())),
                "摘要必须包含正式生产轨道");

        assertEquals(batchesBefore, virtualBatchDao.count(), "回放不得写正式/影子批次");
        assertEquals(eventsBefore, signalEventDao.count(), "回放不得写信号事件");
        assertEquals(statesBefore, signalStateDao.count(), "回放不得写信号状态");
        assertEquals(noticesBefore, noticeAuditDao.count(), "回放不得写通知审计");
    }

    @Test
    @DisplayName("相同请求两次运行runId一致且已完成runId拒绝覆盖")
    void sameRequest_runIdDeterministicAndNoOverwrite() {
        transactionTemplate.executeWithoutResult(status -> {
            seedBars(START, END);
            seedFeatures(START, END);
            seedMonthlyStates();
        });

        StockReplayRequest request = new StockReplayRequest(
                START, END, Stock15mBarBuildService.BUILD_VERSION, SEED_FEATURE_VERSION, "1.0.0", "1.0.0",
                Set.of(StockReplayTrackEnum.FORMAL_20E), OUTPUT_ROOT);
        StockReplayResult first = runner.run(request);
        String runId = first.runId();

        StockReplayRequest same = new StockReplayRequest(
                START, END, Stock15mBarBuildService.BUILD_VERSION, SEED_FEATURE_VERSION, "1.0.0", "1.0.0",
                Set.of(StockReplayTrackEnum.FORMAL_20E), OUTPUT_ROOT);
        assertEquals(runId, StockReplayRunner.computeRunId(StockReplayRunner.normalize(same)),
                "相同输入必须生成相同runId");

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> runner.run(same), "已完成runId必须拒绝覆盖");
    }

    /**
     * 为隔离股票在隔离窗口直接种子可用bar(生产upsert),替代从真实历史构建,
     * 保证回放输入仅来自本测试的隔离数据。
     */
    private void seedBars(LocalDateTime start, LocalDateTime end) {
        LocalDateTime cursor = Stock15mBarBuildService.alignToBucket(start);
        LocalDateTime last = Stock15mBarBuildService.alignToBucket(end);
        while (!cursor.isAfter(last)) {
            for (Integer stock : TEST_STOCKS) {
                bar15mDao.upsertBar(usableBar(stock, cursor));
            }
            cursor = cursor.plusMinutes(Stock15mBarBuildService.BUCKET_MINUTES);
        }
    }

    /**
     * 为隔离股票构建可用bar(满足isUsable: 采样数≥10且尾部新鲜)。
     *
     * @param stock        隔离股票ID
     * @param barStartTime 桶开始时间
     * @return 可用bar
     */
    private TornStockMarketBar15mDO usableBar(int stock, LocalDateTime barStartTime) {
        LocalDateTime barEnd = barStartTime.plusMinutes(Stock15mBarBuildService.BUCKET_MINUTES);
        TornStockMarketBar15mDO bar = new TornStockMarketBar15mDO();
        bar.setStocksId(stock);
        bar.setStocksShortname(SHORTNAME_PREFIX + stock);
        bar.setBarStartTime(barStartTime);
        bar.setBarEndTime(barEnd);
        bar.setFirstSampleTime(barStartTime);
        bar.setLastSampleTime(barEnd.minusMinutes(1));
        bar.setFirstPrice(new BigDecimal("100.00"));
        bar.setLastPrice(new BigDecimal("100.00"));
        bar.setLowPrice(new BigDecimal("100.00"));
        bar.setHighPrice(new BigDecimal("100.00"));
        bar.setSampleCount(Stock15mBarBuildService.MIN_SAMPLE_COUNT);
        bar.setDuplicateCount(0);
        bar.setTailGapSeconds(60);
        bar.setUsable(true);
        bar.setBuildVersion(Stock15mBarBuildService.BUILD_VERSION);
        return bar;
    }

    private void seedFeatures(LocalDateTime start, LocalDateTime end) {
        for (LocalDateTime t = Stock15mBarBuildService.alignToBucket(start);
             !t.isAfter(Stock15mBarBuildService.alignToBucket(end));
             t = t.plusMinutes(Stock15mBarBuildService.BUCKET_MINUTES)) {
            for (Integer stock : TEST_STOCKS) {
                featureDao.upsertFeature(feature(stock, t));
            }
        }
    }

    private TornStockStrategyFeature15mDO feature(int stock, LocalDateTime t) {
        TornStockStrategyFeature15mDO feature = new TornStockStrategyFeature15mDO();
        feature.setStocksId(stock);
        feature.setStocksShortname(SHORTNAME_PREFIX + stock);
        feature.setBarStartTime(t);
        feature.setReferencePrice(new BigDecimal("100.00"));
        feature.setMa1d(new BigDecimal("100.0"));
        feature.setMa7d(new BigDecimal("100.0"));
        feature.setMa30d(new BigDecimal("100.0"));
        feature.setZscore1d(new BigDecimal("-1.0"));
        feature.setZscore7d(new BigDecimal("-0.5"));
        feature.setZscore30d(new BigDecimal("-0.3"));
        feature.setReturn6h(new BigDecimal("-0.005"));
        feature.setReturn1d(new BigDecimal("-0.01"));
        feature.setReturn7d(new BigDecimal("-0.02"));
        feature.setReturn14d(new BigDecimal("-0.03"));
        feature.setLow30d(new BigDecimal("98.00"));
        feature.setHigh30d(new BigDecimal("102.00"));
        feature.setWidth30d(new BigDecimal("0.05"));
        feature.setPosition30(new BigDecimal("0.05"));
        feature.setPctAbove30dLow(new BigDecimal("0.020408"));
        feature.setPctBelow30dHigh(new BigDecimal("0.019608"));
        feature.setStrategyReady(true);
        feature.setDataQualityReason("");
        feature.setFeatureVersion(SEED_FEATURE_VERSION);
        return feature;
    }

    private void seedMonthlyStates() {
        LocalDate month = START.toLocalDate().withDayOfMonth(1);
        List<TornStockMonthlyStateDO> states = new ArrayList<>();
        for (Integer stock : TEST_STOCKS) {
            TornStockMonthlyStateDO state = new TornStockMonthlyStateDO();
            state.setStocksId(stock);
            state.setStocksShortname(SHORTNAME_PREFIX + stock);
            state.setEffectiveMonth(month);
            state.setStrategyFitPrior("NARROW");
            state.setMaturity("M2_PROVISIONAL");
            state.setRiskLevel(StockRiskLevelEnum.NONE.getCode());
            state.setSuggestedPersonality("NARROW");
            state.setManualOverride(false);
            state.setMetricSnapshot("{}");
            state.setStateStatus(StockMonthlyStateStatusEnum.CONFIRMED.getCode());
            state.setCalculatedAt(LocalDateTime.of(2099, 8, 1, 8, 0));
            state.setConfirmedAt(LocalDateTime.of(2099, 8, 1, 8, 0));
            state.setConfirmedBy(SEED_CONFIRMED_BY);
            state.setEvidenceStartTime(LocalDateTime.of(2099, 7, 1, 0, 0));
            state.setEvidenceEndTime(LocalDateTime.of(2099, 7, 31, 23, 45));
            state.setPersonalityRuleVersion("PERSONALITY_RULE_V1");
            state.setRiskRuleVersion("RISK_RULE_V1_SHADOW");
            states.add(state);
        }
        monthlyStateDao.saveBatch(states);
    }
}
