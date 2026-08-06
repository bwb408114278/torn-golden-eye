package pn.torn.goldeneye.torn.service.stocks.replay;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
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
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回放端到端真实PostgreSQL集成测试。
 * <p>
 * 使用现有 {@link Stock15mBarBuildService} 在本地库种子一个小窗口的bar,并通过现有DAO
 * (生产upsert与月度状态批量插入)种子 strategyReady特征与CONFIRMED月度状态作为回放输入;
 * 运行完整回放断言四类产物与摘要;全部种子数据与产物随{@code @Transactional}回滚并在测试后清理。
 * 特征与月度状态不调用对应构建器的原因: 30天特征lookback与月度证据构建在测试中不可行,
 * 这两个构建器已有各自聚焦测试。
 *
 * @author Bai
 * @version 1.2.14
 * @since 2026.08.06
 */
@SpringBootTest
@Transactional
@DisplayName("回放端到端真实PostgreSQL集成测试")
class StockReplayIntegrationTest {

    @Autowired
    private Stock15mBarBuildService barBuildService;
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

    /**
     * 回放窗口起点(2026-08-01上午,历史行情覆盖)。
     */
    private static final LocalDateTime START = LocalDateTime.of(2026, 8, 1, 9, 0);
    /**
     * 回放窗口终点。
     */
    private static final LocalDateTime END = LocalDateTime.of(2026, 8, 1, 14, 0);
    /**
     * 产物输出根目录(测试专用,结束后清理)。
     */
    private static final String OUTPUT_ROOT = "target/replay-it";

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
    }

    @Test
    @DisplayName("种子窗口回放产出四类产物且不写业务表")
    void seededWindow_producesFourArtifactsWithoutBusinessWrites() throws Exception {
        List<TornStockMarketBar15mDO> seededBars = seedBars(START, END);
        seedFeatures(seededBars);
        seedMonthlyStates();

        long batchesBefore = virtualBatchDao.count();
        long eventsBefore = signalEventDao.count();
        long statesBefore = signalStateDao.count();
        long noticesBefore = noticeAuditDao.count();

        StockReplayRequest request = new StockReplayRequest(
                START, END, Stock15mBarBuildService.BUILD_VERSION, "1.0.0", "1.0.0", "1.0.0",
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
        StockReplayRequest request = new StockReplayRequest(
                START, END, Stock15mBarBuildService.BUILD_VERSION, "1.0.0", "1.0.0", "1.0.0",
                Set.of(StockReplayTrackEnum.FORMAL_20E), OUTPUT_ROOT);
        StockReplayResult first = runner.run(request);
        String runId = first.runId();

        StockReplayRequest same = new StockReplayRequest(
                START, END, Stock15mBarBuildService.BUILD_VERSION, "1.0.0", "1.0.0", "1.0.0",
                Set.of(StockReplayTrackEnum.FORMAL_20E), OUTPUT_ROOT);
        assertEquals(runId, StockReplayRunner.computeRunId(StockReplayRunner.normalize(same)),
                "相同输入必须生成相同runId");

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> runner.run(same), "已完成runId必须拒绝覆盖");
    }

    private List<TornStockMarketBar15mDO> seedBars(LocalDateTime start, LocalDateTime end) {
        List<TornStockMarketBar15mDO> all = new ArrayList<>();
        LocalDateTime cursor = Stock15mBarBuildService.alignToBucket(start);
        LocalDateTime last = Stock15mBarBuildService.alignToBucket(end);
        while (!cursor.isAfter(last)) {
            all.addAll(barBuildService.buildBars(cursor));
            cursor = cursor.plusMinutes(Stock15mBarBuildService.BUCKET_MINUTES);
        }
        return all;
    }

    private void seedFeatures(List<TornStockMarketBar15mDO> bars) {
        Set<Integer> stocks = new TreeSet<>();
        for (TornStockMarketBar15mDO bar : bars) {
            stocks.add(bar.getStocksId());
        }
        for (LocalDateTime t = START; !t.isAfter(END);
             t = t.plusMinutes(Stock15mBarBuildService.BUCKET_MINUTES)) {
            for (Integer stock : stocks) {
                featureDao.upsertFeature(feature(stock, t));
            }
        }
    }

    private TornStockStrategyFeature15mDO feature(int stock, LocalDateTime t) {
        TornStockStrategyFeature15mDO feature = new TornStockStrategyFeature15mDO();
        feature.setStocksId(stock);
        feature.setStocksShortname("T" + stock);
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
        feature.setFeatureVersion("1.0.0");
        return feature;
    }

    private void seedMonthlyStates() {
        LocalDate month = START.toLocalDate().withDayOfMonth(1);
        List<TornStockMonthlyStateDO> states = new ArrayList<>();
        for (int stock = 1; stock <= 35; stock++) {
            TornStockMonthlyStateDO state = new TornStockMonthlyStateDO();
            state.setStocksId(stock);
            state.setStocksShortname("T" + stock);
            state.setEffectiveMonth(month);
            state.setStrategyFitPrior("NARROW");
            state.setMaturity("M2_PROVISIONAL");
            state.setRiskLevel(StockRiskLevelEnum.NONE.getCode());
            state.setSuggestedPersonality("NARROW");
            state.setManualOverride(false);
            state.setMetricSnapshot("{}");
            state.setStateStatus(StockMonthlyStateStatusEnum.CONFIRMED.getCode());
            state.setCalculatedAt(LocalDateTime.of(2026, 8, 1, 8, 0));
            state.setConfirmedAt(LocalDateTime.of(2026, 8, 1, 8, 0));
            state.setEvidenceStartTime(LocalDateTime.of(2025, 12, 1, 0, 0));
            state.setEvidenceEndTime(LocalDateTime.of(2026, 7, 31, 23, 45));
            state.setPersonalityRuleVersion("PERSONALITY_RULE_V1");
            state.setRiskRuleVersion("RISK_RULE_V1_SHADOW");
            states.add(state);
        }
        monthlyStateDao.saveBatch(states);
    }
}
