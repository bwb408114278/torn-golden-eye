package pn.torn.goldeneye.torn.service.stocks.alert.market.round;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockLedgerTypeEnum;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockSignalEventDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockVirtualBatchDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockSignalEventDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchDO;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import pn.torn.goldeneye.torn.service.stocks.alert.market.StockRuleVersion;
import pn.torn.goldeneye.torn.service.stocks.alert.shadow.StockShadowTrackRecorder;
import pn.torn.goldeneye.torn.service.stocks.alert.signal.StockSignalEventContext;

/**
 * 信号事件/影子批次幂等并发真实PostgreSQL集成测试。
 * <p>
 * 覆盖第三轮 Review 11.3/11.6: 事件与批次编号改用稳定业务键(roundTime/eventId)后,
 * 重试与积压不再因墙钟分钟碰撞产生唯一约束冲突:
 * <ul>
 *   <li>同 {@code roundTime} 同股票同策略重复调用记录器(模拟第一次事务失败后重试):
 *       不抛唯一异常, event 与每种应有影子/拒绝批次数量保持 1;</li>
 *   <li>两个独立事务竞争同一 event key: 提交后仅一个 event、每账本一个 batch,
 *       关联字段完整;</li>
 *   <li>两个不同历史 {@code roundTime}(同一墙钟分钟内连续处理)同股票同策略:
 *       各自事件/批次独立落库, 事件编号基于 roundTime 互不相同, 无唯一冲突。</li>
 * </ul>
 * 使用隔离股票ID与远端未来时间,{@code @AfterEach}按本测试股票集合精确物理DELETE,
 * 不删除任何业务数据(不以{@code @Rollback}代替提交态验证)。
 *
 * @author Bai
 * @version 1.2.14
 * @since 2026.08.11
 */
@SpringBootTest
@Tag("shared-db")
@DisplayName("信号事件/影子批次幂等并发真实PostgreSQL集成测试")
class StockEventBatchIdempotencyItTest {

    @Autowired
    private TornStockSignalEventDAO signalEventDao;
    @Autowired
    private TornStockVirtualBatchDAO virtualBatchDao;
    @Autowired
    private NamedParameterJdbcTemplate namedJdbcTemplate;
    @Autowired
    private TransactionTemplate transactionTemplate;

    /**
     * 隔离股票ID(远离生产股票1..35与其他测试命名空间)
     */
    private static final int STOCK_A = 2099601;
    /**
     * 隔离股票ID2
     */
    private static final int STOCK_B = 2099602;
    /**
     * 隔离股票ID3(积压回归用)
     */
    private static final int STOCK_C = 2099603;
    /**
     * 隔离轮次时间A(未来,远离生产数据)
     */
    private static final LocalDateTime ROUND_TIME_A = LocalDateTime.of(2099, 12, 1, 10, 0);
    /**
     * 隔离轮次时间B(与A同一墙钟分钟处理,代表积压 catch-up 的另一个历史 round)
     */
    private static final LocalDateTime ROUND_TIME_B = LocalDateTime.of(2099, 12, 1, 10, 15);
    /**
     * 策略类型
     */
    private static final String STRATEGY = "RANGE_LOWER_BUY";

    @AfterEach
    void cleanupIsolatedData() {
        namedJdbcTemplate.update(
                "DELETE FROM torn_stock_virtual_batch WHERE stocks_id IN (:stocks)",
                Map.of("stocks", List.of(STOCK_A, STOCK_B, STOCK_C)));
        namedJdbcTemplate.update(
                "DELETE FROM torn_stock_signal_event WHERE stocks_id IN (:stocks)",
                Map.of("stocks", List.of(STOCK_A, STOCK_B, STOCK_C)));
    }

    @Test
    @DisplayName("真实PG_同roundTime重试记录事件与三类批次_数量保持1且不抛唯一异常")
    void retrySameBusinessKey_eventAndBatchesStaySingle() {
        StockShadowTrackRecorder recorder = new StockShadowTrackRecorder(signalEventDao, virtualBatchDao);

        // 第一次记录(事务成功后失败再重试的第一次)
        StockSignalEventContext context = buildContext(STOCK_A, ROUND_TIME_A);
        TornStockSignalEventDO first = recorder.recordSignalEvent(context);
        // 第二次调用同一业务键(模拟事务失败后重试)
        TornStockSignalEventDO second = recorder.recordSignalEvent(context);
        assertEquals(first.getId(), second.getId(), "重试必须复用同一事件");
        assertEquals(1, countEvents(STOCK_A), "同一业务键重试后事件必须仅一行");

        // 无限资金影子批次: 同一事件重复创建必须复用
        TornStockVirtualBatchDO unlimited1 = recorder.createUnlimitedShadowBatch(first);
        TornStockVirtualBatchDO unlimited2 = recorder.createUnlimitedShadowBatch(first);
        assertEquals(unlimited1.getId(), unlimited2.getId(), "无限资金影子批次重试必须复用");
        assertEquals(1, countBatches(STOCK_A, StockLedgerTypeEnum.UNLIMITED_SHADOW.getCode()),
                "同一事件无限资金影子批次必须仅一行");

        // 拒绝观察批次: 同一事件重复创建必须复用(readback 字段满足 buildBaseBatch 前置条件)
        recorder.createRejectedObservationBatch(first, "COOLDOWN_ACTIVE");
        recorder.createRejectedObservationBatch(first, "COOLDOWN_ACTIVE");
        assertEquals(1, countBatches(STOCK_A, StockLedgerTypeEnum.REJECTED_OBSERVATION.getCode()),
                "同一事件拒绝观察批次必须仅一行");
    }

    @Test
    @DisplayName("真实PG_两个独立事务竞争同一event key_提交后仅一个事件与每账本一个批次")
    void twoIndependentTransactions_sameEventKey_singleEventAndBatchPerLedger() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Future<?> first = pool.submit(() -> recordEventConcurrently(STOCK_B, ready, go, failure));
        Future<?> second = pool.submit(() -> recordEventConcurrently(STOCK_B, ready, go, failure));
        ready.await(10, TimeUnit.SECONDS);
        go.countDown();
        first.get(30, TimeUnit.SECONDS);
        second.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertNull(failure.get(), () -> "并发记录不应泄漏任何异常: " + failure.get());
        assertEquals(1, countEvents(STOCK_B), "两个独立事务竞争后同一业务键事件必须仅一行");

        StockShadowTrackRecorder recorder = new StockShadowTrackRecorder(signalEventDao, virtualBatchDao);
        TornStockSignalEventDO event = signalEventDao.selectByBusinessKeyForUpdate(
                STOCK_B, STRATEGY, ROUND_TIME_A, StockRuleVersion.BUY);
        assertNotNull(event, "竞争后应能按业务键读回事件");
        TornStockVirtualBatchDO unlimited = recorder.createUnlimitedShadowBatch(event);
        assertEquals(1, countBatches(STOCK_B, StockLedgerTypeEnum.UNLIMITED_SHADOW.getCode()),
                "竞争后无限资金影子批次必须仅一行");
        assertNotNull(unlimited.getBatchNo(), "批次编号必须基于事件ID生成且非空");
    }

    @Test
    @DisplayName("真实PG_两个历史round同一墙钟分钟连续处理_事件各自独立且同股同策略无限批次收敛复用")
    void twoHistoricalRounds_sameWallClockMinute_independentEventsAndBatches() {
        StockShadowTrackRecorder recorder = new StockShadowTrackRecorder(signalEventDao, virtualBatchDao);

        // round A: 2099-12-01 10:00, round B: 2099-12-01 10:15
        // 模拟启动补偿/正常 catch-up 在同一墙钟分钟内连续处理两个未完成历史 round
        TornStockSignalEventDO eventA = recorder.recordSignalEvent(buildContext(STOCK_C, ROUND_TIME_A));
        TornStockSignalEventDO eventB = recorder.recordSignalEvent(buildContext(STOCK_C, ROUND_TIME_B));

        assertNotNull(eventA.getEventNo(), "round A 事件编号不得为空");
        assertNotNull(eventB.getEventNo(), "round B 事件编号不得为空");
        assertNotEquals(eventA.getEventNo(), eventB.getEventNo(), "两个不同 roundTime 的事件编号必须基于业务轮次互不相同");
        assertEquals(2, countEvents(STOCK_C), "两个历史 round 必须各自落一条事件");

        recorder.createUnlimitedShadowBatch(eventA);
        recorder.createUnlimitedShadowBatch(eventB);
        assertEquals(1, countBatches(STOCK_C, StockLedgerTypeEnum.UNLIMITED_SHADOW.getCode()),
                "同股同策略活跃无限资金影子批次必须全局唯一(uk_stock_virtual_batch_shadow_stock_strat_ver), "
                        + "第二个历史round必须复用而非新建, 不得抛唯一异常");
    }

    /**
     * 在独立事务中按指定业务键记录信号事件, 记录可能发生的异常。
     *
     * @param stocksId 股票ID
     * @param ready    就绪屏障
     * @param go       开跑屏障
     * @param failure  异常收集
     * @return 恒为null
     */
    private Void recordEventConcurrently(int stocksId, CountDownLatch ready, CountDownLatch go,
                                         AtomicReference<Throwable> failure) {
        ready.countDown();
        try {
            go.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failure.compareAndSet(null, e);
            return null;
        }
        try {
            StockShadowTrackRecorder recorder = new StockShadowTrackRecorder(signalEventDao, virtualBatchDao);
            transactionTemplate.executeWithoutResult(status ->
                    recorder.recordSignalEvent(buildContext(stocksId, ROUND_TIME_A)));
        } catch (Throwable e) {
            failure.compareAndSet(null, e);
        }
        return null;
    }

    /**
     * 统计指定股票事件行数。
     *
     * @param stocksId 股票ID
     * @return 事件行数
     */
    private int countEvents(int stocksId) {
        return namedJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM torn_stock_signal_event WHERE stocks_id = :stock AND deleted = 0",
                Map.of("stock", stocksId), Integer.class);
    }

    /**
     * 统计指定股票与账本类型的批次行数。
     *
     * @param stocksId   股票ID
     * @param ledgerType 账本类型
     * @return 批次行数
     */
    private int countBatches(int stocksId, String ledgerType) {
        return namedJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM torn_stock_virtual_batch "
                        + "WHERE stocks_id = :stock AND ledger_type = :ledger AND deleted = 0",
                Map.of("stock", stocksId, "ledger", ledgerType), Integer.class);
    }

    /**
     * 构造完整的信号事件上下文(满足事件表全部NOT NULL字段)。
     *
     * @param stocksId  股票ID
     * @param roundTime 轮次时间
     * @return 信号事件上下文
     */
    private StockSignalEventContext buildContext(int stocksId, LocalDateTime roundTime) {
        return new StockSignalEventContext(
                stocksId, "I" + stocksId, STRATEGY, new BigDecimal("100.00"),
                "RANGING", "M2_PROVISIONAL", "NONE", roundTime.toLocalDate(),
                StockRuleVersion.BUY, BigDecimal.ONE, "{}", "{}", "ALLOWED",
                List.of(), 1, "SHADOW", null, roundTime);
    }
}
