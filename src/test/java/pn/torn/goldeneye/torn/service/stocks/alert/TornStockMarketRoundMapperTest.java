package pn.torn.goldeneye.torn.service.stocks.alert;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockRoundStatusEnum;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockMarketRoundDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketRoundDO;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 轮次生产者真实PostgreSQL集成测试。
 * <p>
 * 使用远离生产数据的未来桶时间作为隔离测试轮次,通过{@code @Transactional}回滚
 * 保证开发库零残留。验证:
 * <ul>
 *   <li>首次插入成功返回实际行数1;</li>
 *   <li>同round_time重复执行返回0,不抛重复键异常,库中仍仅一行;</li>
 *   <li>部分唯一索引 {@code uk_stock_market_round_time} 与 {@code ON CONFLICT DO NOTHING}
 *       同语义,启动补偿与定时入口竞争同桶只落一行;</li>
 *   <li>插入后PENDING轮次可被未完成轮次查询命中。</li>
 * </ul>
 *
 * @author Bai
 * @version 1.2.14
 * @since 2026.08.08
 */
@SpringBootTest
@Transactional
@DisplayName("轮次生产者真实PostgreSQL集成测试")
class TornStockMarketRoundMapperTest {

    @Autowired
    private TornStockMarketRoundDAO roundDao;

    /**
     * 隔离轮次时间(远离生产数据)
     */
    private static final LocalDateTime TEST_ROUND_TIME = LocalDateTime.of(2099, 9, 1, 10, 0);

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
    @DisplayName("真实PG_连续三个不同结束桶_每桶仅一行且按round_time可命中")
    void insertPendingRoundIgnoreConflict_threeBuckets_eachOneRow() {
        LocalDateTime bucket1 = LocalDateTime.of(2099, 9, 1, 10, 0);
        LocalDateTime bucket2 = bucket1.plusMinutes(15);
        LocalDateTime bucket3 = bucket1.plusMinutes(30);

        assertEquals(1, roundDao.insertPendingRoundIgnoreConflict(pendingRound(bucket1)));
        assertEquals(1, roundDao.insertPendingRoundIgnoreConflict(pendingRound(bucket2)));
        assertEquals(1, roundDao.insertPendingRoundIgnoreConflict(pendingRound(bucket3)));
        assertEquals(0, roundDao.insertPendingRoundIgnoreConflict(pendingRound(bucket2)));

        assertEquals(3, countPending(), "三个不同结束桶应各一行");
    }

    @Test
    @DisplayName("真实PG_部分唯一索引与ON CONFLICT同语义_双入口竞争同桶只落一行")
    void insertPendingRoundIgnoreConflict_concurrentSameBucket_singleRow() {
        roundDao.insertPendingRoundIgnoreConflict(pendingRound(TEST_ROUND_TIME));

        TornStockMarketRoundDO existing = roundDao.selectByRoundTimeForUpdate(TEST_ROUND_TIME);

        assertNotNull(existing, "插入后应可通过round_time查回");
        assertEquals(StockRoundStatusEnum.PENDING.getCode(), existing.getRoundStatus(),
                "插入轮次状态必须为PENDING");
        assertEquals(0, existing.getAttemptCount(), "新建轮次attemptCount应为0");
        assertEquals(1, countPending());
    }

    /**
     * 查询测试轮次时间的PENDING轮次行数。
     *
     * @return 行数
     */
    private int countPending() {
        List<TornStockMarketRoundDO> rounds = roundDao.selectPendingRoundsBefore(TEST_ROUND_TIME.plusMinutes(60));
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
