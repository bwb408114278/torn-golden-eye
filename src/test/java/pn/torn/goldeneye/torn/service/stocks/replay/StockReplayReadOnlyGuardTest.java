package pn.torn.goldeneye.torn.service.stocks.replay;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.annotation.Transactional;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.*;
import pn.torn.goldeneye.repository.mapper.torn.stocks.portfolio.StockReplayReadOnlyProbeMapper;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockSignalEventDO;
import pn.torn.goldeneye.torn.service.stocks.alert.market.Stock15mBarBuildService;
import pn.torn.goldeneye.torn.service.stocks.replay.model.StockReplayRequest;
import pn.torn.goldeneye.torn.service.stocks.replay.model.StockReplayResult;
import pn.torn.goldeneye.torn.service.stocks.replay.model.StockReplayTrackEnum;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 回放只读事务守卫真实PostgreSQL测试。
 * <p>
 * 验证: 独立只读事务内 {@code current_setting('transaction_read_only')} 为on; 只读事务内任何
 * DAO写入被数据库拒绝; 一次真实回放运行不改变任何业务表行数(业务表写0)。产物写入
 * {@code target/replay-guard} 并在测试后清理。同时验证输入加载使用 REQUIRES_NEW 只读事务,
 * 即使调用方处于外层可写/READ_COMMITTED事务,输入事务仍保持只读 + Repeatable Read。</p>
 *
 * @author Bai
 * @version 1.2.14
 * @since 2026.08.06
 */
@SpringBootTest
@Tag("shared-db")
@DisplayName("回放只读事务守卫真实PostgreSQL测试")
class StockReplayReadOnlyGuardTest {

    @Autowired
    private StockReplayReadOnlyGuard readOnlyGuard;
    @Autowired
    private StockReplayRunner runner;
    @Autowired
    private TornStockSignalEventDAO signalEventDao;
    @Autowired
    private TornStockVirtualBatchDAO virtualBatchDao;
    @Autowired
    private TornStockSignalStateDAO signalStateDao;
    @Autowired
    private TornStockNoticeAuditDAO noticeAuditDao;
    @Autowired
    private TornStockMarketRoundDAO marketRoundDao;
    @Autowired
    private StockReplayReadOnlyProbeMapper probeMapper;

    /**
     * 产物输出根目录(测试专用,结束后清理)。
     */
    private static final String OUTPUT_ROOT = "target/replay-guard";

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
    @DisplayName("独立只读事务校验通过(transaction_read_only=on)")
    void verifyReadOnlySession_passes() {
        readOnlyGuard.verifyReadOnlySession();
    }

    @Test
    @DisplayName("只读事务内DAO写入被数据库拒绝")
    void readOnlyTransaction_rejectsWrite() {
        TornStockSignalEventDO event = buildIsolatedEvent();
        assertThrows(DataAccessException.class, () ->
                        readOnlyGuard.inReadOnlyTransaction(status -> {
                            signalEventDao.save(event);
                            return null;
                        }),
                "只读事务内写入必须抛出数据库只读错误");
    }

    @Test
    @DisplayName("真实回放运行不改变任何业务表行数")
    void replayRun_writesZeroBusinessRows() {
        Map<String, Long> before = businessRowCounts();

        LocalDateTime start = LocalDateTime.of(2099, 2, 1, 10, 0);
        LocalDateTime end = start.plusMinutes(45);
        StockReplayRequest request = new StockReplayRequest(
                start, end, Stock15mBarBuildService.BUILD_VERSION, "1.0.0", "1.0.0", "1.0.0",
                Set.of(StockReplayTrackEnum.FORMAL_20E), OUTPUT_ROOT);
        StockReplayResult result = runner.run(request);

        assertEquals("COMPLETED", result.summary().status(), "空窗口回放仍应标记完成");
        assertTrue(Paths.get(OUTPUT_ROOT, result.runId(), result.runId() + "-summary.json").toFile().exists(),
                "应生成summary.json");

        Map<String, Long> after = businessRowCounts();
        assertEquals(before, after, "回放运行不得写入或删除任何业务表行");
    }

    @Test
    @Transactional
    @DisplayName("外层READ_COMMITTED可写事务_输入事务仍只读+Repeatable Read")
    void outerWritableTx_inputTransactionStillReadOnlyRepeatableRead() {
        // 测试方法默认处于 READ_COMMITTED 可写外层事务;输入加载必须使用REQUIRES_NEW,
        // 新建独立的只读+Repeatable Read事务,不受外层污染
        String[] settings = readOnlyGuard.inReadOnlyTransaction(status -> new String[]{
                probeMapper.selectTransactionReadOnly(),
                probeMapper.selectTransactionIsolationLevel()});
        assertEquals("on", settings[0], "输入事务必须保持只读(on)");
        assertTrue(settings[1].toLowerCase().contains("repeatable read"),
                "输入事务必须保持Repeatable Read,实际=" + settings[1]);

        // 在同样外层事务内运行真实回放,证明加载全程使用只读+RR输入事务
        LocalDateTime start = LocalDateTime.of(2099, 3, 1, 10, 0);
        LocalDateTime end = start.plusMinutes(45);
        StockReplayRequest request = new StockReplayRequest(
                start, end, Stock15mBarBuildService.BUILD_VERSION, "1.0.0", "1.0.0", "1.0.0",
                Set.of(StockReplayTrackEnum.FORMAL_20E), OUTPUT_ROOT);
        StockReplayResult result = runner.run(request);
        assertEquals("COMPLETED", result.summary().status(), "外层事务内回放加载仍应完成");
    }

    private TornStockSignalEventDO buildIsolatedEvent() {
        TornStockSignalEventDO event = new TornStockSignalEventDO();
        event.setEventNo("E" + System.nanoTime() + "RO");
        event.setRoundTime(LocalDateTime.of(2099, 3, 1, 10, 0));
        event.setStocksId(2099);
        event.setStocksShortname("RO");
        event.setStrategyType("RANGE_LOWER_BUY");
        event.setSignalReferencePrice(new BigDecimal("100.00"));
        event.setBuyRuleVersion("1.0.0");
        event.setQualityScore(new BigDecimal("80.0"));
        return event;
    }

    private Map<String, Long> businessRowCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("torn_stock_signal_event", signalEventDao.count());
        counts.put("torn_stock_virtual_batch", virtualBatchDao.count());
        counts.put("torn_stock_signal_state", signalStateDao.count());
        counts.put("torn_stock_notice_audit", noticeAuditDao.count());
        counts.put("torn_stock_market_round", marketRoundDao.count());
        return counts;
    }
}
