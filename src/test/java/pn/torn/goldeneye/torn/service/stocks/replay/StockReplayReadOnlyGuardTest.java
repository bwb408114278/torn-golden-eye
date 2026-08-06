package pn.torn.goldeneye.torn.service.stocks.replay;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockSignalEventDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockSignalEventDO;
import pn.torn.goldeneye.torn.service.stocks.alert.Stock15mBarBuildService;
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
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回放只读事务守卫真实PostgreSQL测试。
 * <p>
 * 验证: 独立只读事务内 {@code pg_is_in_transaction_read_only()} 为true; 只读事务内任何
 * DAO写入被数据库拒绝; 一次真实回放运行不改变任何业务表行数(业务表写0)。产物写入
 * {@code target/replay-guard} 并在测试后清理。
 *
 * @author Bai
 * @version 1.2.14
 * @since 2026.08.06
 */
@SpringBootTest
@DisplayName("回放只读事务守卫真实PostgreSQL测试")
class StockReplayReadOnlyGuardTest {

    @Autowired
    private StockReplayReadOnlyGuard readOnlyGuard;
    @Autowired
    private StockReplayRunner runner;
    @Autowired
    private TornStockSignalEventDAO signalEventDao;
    @Autowired
    private JdbcTemplate jdbcTemplate;

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
    @DisplayName("独立只读事务校验通过(pg_is_in_transaction_read_only=true)")
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
        for (String table : List.of(
                "torn_stock_signal_event",
                "torn_stock_virtual_batch",
                "torn_stock_signal_state",
                "torn_stock_notice_audit",
                "torn_stock_market_round")) {
            Long count = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM " + table + " WHERE deleted = 0", Long.class);
            counts.put(table, count == null ? 0L : count);
        }
        return counts;
    }
}
