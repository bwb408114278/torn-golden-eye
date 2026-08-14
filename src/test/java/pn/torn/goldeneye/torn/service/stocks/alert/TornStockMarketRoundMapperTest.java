package pn.torn.goldeneye.torn.service.stocks.alert;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockRoundStatusEnum;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockMarketRoundDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketRoundDO;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicIntegerArray;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 轮次生产者真实PostgreSQL集成测试。
 * <p>
 * 使用远离生产数据的未来桶时间作为隔离测试轮次,{@code @AfterEach}在每次测试后按
 * 专用round_time窗口精确物理DELETE,开发库零残留(不以{@code @Rollback}代替提交态验证)。
 * 验证:
 * <ul>
 *   <li>首次插入成功返回实际行数1;</li>
 *   <li>同round_time顺序重复执行返回0,不抛重复键异常,库中仍仅一行;</li>
 *   <li>两个独立事务并发竞争同round_time,部分唯一索引 {@code uk_stock_market_round_time}
 *       与 {@code ON CONFLICT DO NOTHING} 同语义,双入口竞争同桶只落一行;</li>
 *   <li>插入后PENDING轮次可被未完成轮次查询命中。</li>
 * </ul>
 *
 * @author Bai
 * @version 1.2.18
 * @since 2026.08.09
 */
@SpringBootTest
@DisplayName("轮次生产者真实PostgreSQL集成测试")
class TornStockMarketRoundMapperTest {

    @Autowired
    private TornStockMarketRoundDAO roundDao;
    @Autowired
    private NamedParameterJdbcTemplate namedJdbcTemplate;
    @Autowired
    private TransactionTemplate transactionTemplate;

    /**
     * 隔离轮次时间(远离生产数据)
     */
    private static final LocalDateTime TEST_ROUND_TIME = LocalDateTime.of(2099, 9, 1, 10, 0);
    /**
     * 隔离轮次ID(远离生产数据与本地同步库identity序列,显式赋值避免序列滞后冲突)
     */
    private static final Long ISOLATED_TEST_ROUND_ID = 900_000_000L;

    @AfterEach
    void cleanupIsolatedRounds() {
        namedJdbcTemplate.update(
                "DELETE FROM torn_stock_market_round "
                        + "WHERE round_time >= :start AND round_time < :end",
                Map.of("start", TEST_ROUND_TIME, "end", TEST_ROUND_TIME.plusMinutes(31)));
    }

    @Test
    @DisplayName("真实PG_首次插入PENDING轮次成功,重复执行0行不抛异常")
    void insertPendingRoundIgnoreConflict_firstInsertSucceedsAndRepeatReturnsZero() {
        int first = roundDao.insertPendingRoundIgnoreConflict(pendingRound(TEST_ROUND_TIME));

        assertEquals(1, first, "首次插入应返回实际插入行数1");

        int second = roundDao.insertPendingRoundIgnoreConflict(pendingRound(TEST_ROUND_TIME));

        assertEquals(0, second, "重复插入同round_time应被DO NOTHING吸收返回0");
        assertEquals(1, countPending(), "库中该桶应仅一行");
    }

    @Test
    @DisplayName("真实PG_两个独立事务并发竞争同round_time_提交后仅落一行")
    void insertPendingRoundIgnoreConflict_twoIndependentTransactions_singleRowCommitted() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicIntegerArray insertedCounts = new AtomicIntegerArray(2);
        Future<?>[] futures = new Future<?>[2];
        for (int i = 0; i < 2; i++) {
            final int idx = i;
            futures[idx] = pool.submit(() -> {
                ready.countDown();
                go.await();
                int[] inserted = {0};
                transactionTemplate.executeWithoutResult(status ->
                        inserted[0] = roundDao.insertPendingRoundIgnoreConflict(pendingRound(TEST_ROUND_TIME)));
                insertedCounts.set(idx, inserted[0]);
                return null;
            });
        }
        ready.await();
        go.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        Set<Integer> insertedSet = new HashSet<>();
        insertedSet.add(insertedCounts.get(0));
        insertedSet.add(insertedCounts.get(1));
        assertEquals(Set.of(1, 0), insertedSet,
                "两个独立事务竞争同round_time,插入数必须恰好为{1,0}");

        Integer committed = namedJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM torn_stock_market_round "
                        + "WHERE round_time = :roundTime AND deleted = 0",
                Map.of("roundTime", TEST_ROUND_TIME), Integer.class);
        assertEquals(1, committed, "两个事务均提交后该round_time有效轮次必须仅一行");
    }

    @Test
    @DisplayName("真实PG_包含上界查询返回新建立的同桶PENDING轮次")
    void selectPendingRoundsUpTo_inclusiveBoundary_returnsBucket() {
        // 生产链: 先INSERT PENDING(currentEndedBucket),再以同一时间作为包含上界查询,
        // 该新桶必须被本次查询读取,不得被严格上界排除。
        roundDao.insertPendingRoundIgnoreConflict(pendingRound(TEST_ROUND_TIME));

        List<TornStockMarketRoundDO> rounds = roundDao.selectPendingRoundsUpTo(TEST_ROUND_TIME);

        assertEquals(1, rounds.stream()
                        .filter(round -> TEST_ROUND_TIME.equals(round.getRoundTime()))
                        .count(),
                "round_time <= 上界的待处理查询必须包含新建立的同桶轮次");
        assertEquals(1, countPending(), "库中该桶应仅一行");
    }

    @Test
    @DisplayName("真实PG_普通按round_time读取_不加行锁返回轮次")
    void selectByRoundTime_plainRead_returnsRound() {
        roundDao.insertPendingRoundIgnoreConflict(pendingRound(TEST_ROUND_TIME));

        TornStockMarketRoundDO round = roundDao.selectByRoundTime(TEST_ROUND_TIME);

        assertEquals(TEST_ROUND_TIME, round.getRoundTime(), "普通读取应返回同桶轮次");
    }

    @Test
    @Transactional
    @Rollback
    @DisplayName("真实PG_生产白名单查询排除REPAIRED_DATA_ONLY数据修复终态轮次")
    void selectPendingRoundsUpTo_whitelist_excludesDataRepairOnlyRound() {
        // 全程通过DAO方法操作并在测试事务内回滚,开发库零残留:
        // Tornsy回填数据修复终态REPAIRED_DATA_ONLY绝不进入生产策略消费队列。
        // 本地同步库的id identity序列落后于生产同步数据,故显式指定隔离id避免序列冲突。
        TornStockMarketRoundDO repaired = pendingRound(TEST_ROUND_TIME);
        repaired.setId(ISOLATED_TEST_ROUND_ID);
        roundDao.save(repaired);

        repaired.setRoundStatus(StockRoundStatusEnum.REPAIRED_DATA_ONLY.getCode());
        roundDao.updateById(repaired);

        assertFalse(roundDao.selectPendingRoundsUpTo(TEST_ROUND_TIME.plusMinutes(60)).stream()
                        .anyMatch(round -> TEST_ROUND_TIME.equals(round.getRoundTime())),
                "REPAIRED_DATA_ONLY轮次不得被生产待处理查询命中");

        // READY仍属生产白名单,必须可被查询命中(防止白名单修复误伤生产主链)
        repaired.setRoundStatus(StockRoundStatusEnum.READY.getCode());
        roundDao.updateById(repaired);

        assertTrue(roundDao.selectPendingRoundsUpTo(TEST_ROUND_TIME.plusMinutes(60)).stream()
                        .anyMatch(round -> TEST_ROUND_TIME.equals(round.getRoundTime())),
                "READY轮次必须被生产待处理查询命中");
    }

    /**
     * 查询测试轮次时间的PENDING轮次行数。
     *
     * @return 行数
     */
    private int countPending() {
        List<TornStockMarketRoundDO> rounds = roundDao.selectPendingRoundsUpTo(TEST_ROUND_TIME.plusMinutes(60));
        return (int) rounds.stream()
                .filter(round -> round.getRoundTime().isAfter(TEST_ROUND_TIME.minusMinutes(1)))
                .filter(round -> round.getRoundTime().isBefore(TEST_ROUND_TIME.plusMinutes(31)))
                .count();
    }

    /**
     * 构建PENDING轮次DO(填充全部NOT NULL字段)。
     *
     * @param roundTime 轮次时间
     * @return 待插入的PENDING轮次DO
     */
    private TornStockMarketRoundDO pendingRound(LocalDateTime roundTime) {
        TornStockMarketRoundDO round = new TornStockMarketRoundDO();
        round.setRoundTime(roundTime);
        round.setRoundStatus(StockRoundStatusEnum.PENDING.getCode());
        round.setBarBuildVersion(Stock15mBarBuildService.BUILD_VERSION);
        round.setFeatureVersion(Stock15mFeatureBuildService.FEATURE_VERSION);
        round.setBuyRuleVersion("1.1.0");
        round.setSellRuleVersion("1.0.0");
        round.setAllocationRuleVersion("1.0.0");
        round.setMessageRuleVersion("1.0.0");
        round.setExpectedStockCount(0);
        round.setUsableStockCount(0);
        round.setAttemptCount(0);
        return round;
    }
}
