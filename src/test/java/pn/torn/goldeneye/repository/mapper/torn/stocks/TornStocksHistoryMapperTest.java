package pn.torn.goldeneye.repository.mapper.torn.stocks;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;
import pn.torn.goldeneye.repository.dao.torn.stocks.TornStocksHistoryDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.StockHistoryMinuteCount;
import pn.torn.goldeneye.repository.model.torn.stocks.TornStocksHistoryDO;
import pn.torn.goldeneye.torn.service.stocks.backfill.StockHistoryDataSourceEnum;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 股票历史分钟连续性聚合 Mapper 真实 PostgreSQL 测试
 * <p>
 * 验证 {@code selectMinuteCountsByStocksAndRange} 的聚合口径：distinct 自然分钟计数、
 * 左闭右开窗口边界、逻辑删除行不计入、同一自然分钟的 deleted=1 重复原始行不虚增计数、
 * 缺失行股票不返回 SQL 行（调用方解释为 0）。
 * <p>
 * 写入仅通过 DAO 方法完成（MyBatis-Plus 标准批量插入自动生成主键 + save 单条插入
 * deleted=1 历史异常行），数据使用 2099 严格未来时间命名空间与测试专用股票ID隔离，
 * 绝不进入实时/生产时间范围；类级事务以 {@code @Rollback} 回滚，测试库零残留。
 * <p>
 * 注：生产回填链路的 {@code insertBackfillReturningSlots} 不提供主键,依赖表默认主键生成；
 * 当前开发库 {@code torn_stocks_history.id} 无默认值,故测试数据以标准插入方法写入,
 * 聚合口径验证不受影响。
 *
 * @author Bai
 * @version 1.2.18
 * @since 2026.08.15
 */
@SpringBootTest
@DisplayName("股票历史分钟连续性聚合Mapper真实PostgreSQL测试")
@Transactional
@Rollback
class TornStocksHistoryMapperTest {

    @Autowired
    private TornStocksHistoryDAO stocksHistoryDao;

    /**
     * 隔离测试窗口起点（2099严格未来时间命名空间，远离生产数据）
     */
    private static final LocalDateTime WIN_START = LocalDateTime.of(2099, 10, 1, 0, 0);
    /**
     * 隔离测试窗口终点（完整自然日1440分钟）
     */
    private static final LocalDateTime WIN_END = WIN_START.plusDays(1);

    @Test
    @DisplayName("真实PG_同一股票1440个不同自然分钟 -> 返回1440")
    void selectMinuteCounts_fullDayDistinctMinutes_returns1440() {
        int stocksId = 997001;
        insertBackfillMinutes(stocksId, WIN_START, 1440);

        assertEquals(1440L, minuteCount(stocksId), "1440个不同自然分钟必须返回1440");
    }

    @Test
    @DisplayName("真实PG_缺失分钟 -> 返回小于1440, 无数据股票缺SQL行")
    void selectMinuteCounts_missingMinutes_returnsBelow1440AndAbsentRowForEmptyStock() {
        int partialStocksId = 997011;
        int emptyStocksId = 997012;
        insertBackfillMinutes(partialStocksId, WIN_START, 1439);

        List<StockHistoryMinuteCount> counts = stocksHistoryDao.selectMinuteCountsByStocksAndRange(
                List.of(partialStocksId, emptyStocksId), WIN_START, WIN_END);

        assertEquals(1439L, findCount(counts, partialStocksId), "缺1个自然分钟必须返回1439");
        assertNull(findCount(counts, emptyStocksId), "无数据股票不得返回SQL行, 由调用方解释为0");
    }

    @Test
    @DisplayName("真实PG_只统计[start,end) -> 起点含/终点前含/终点本身排除")
    void selectMinuteCounts_halfOpenRange_excludesEndBoundary() {
        int stocksId = 997021;
        // 窗口内: 起点(含)与终点前1分钟; 窗口外: 终点本身(排除)
        insertBackfillMinutes(stocksId, WIN_START, 1);
        insertBackfillMinutes(stocksId, WIN_END.minusMinutes(1), 1);
        insertBackfillMinutes(stocksId, WIN_END, 1);

        assertEquals(2L, minuteCount(stocksId), "只统计[start,end), 不得越过右边界");
    }

    @Test
    @DisplayName("真实PG_逻辑删除行不计入, 同分钟deleted重复原始行不虚增计数")
    void selectMinuteCounts_deletedDuplicateRow_notCountedAndNotInflated() {
        int stocksId = 997031;
        insertBackfillMinutes(stocksId, WIN_START, 1);

        // 自然分钟唯一索引仅覆盖deleted=0: 以deleted=1保留一条同分钟历史异常原始行,
        // 验证COUNT(DISTINCT minute)不被重复原始行误判为连续/虚增
        TornStocksHistoryDO deletedDuplicate = history(stocksId, WIN_START);
        deletedDuplicate.setDeleted(1);
        assertTrue(stocksHistoryDao.save(deletedDuplicate), "deleted=1同分钟原始行必须可插入, 不触碰唯一索引");

        assertEquals(1L, minuteCount(stocksId), "逻辑删除行不计入, 同分钟重复原始行不得虚增计数");
    }

    /**
     * 通过DAO标准批量插入写入从指定分钟起连续count个自然分钟事实
     * <p>
     * 使用 MyBatis-Plus 标准插入（自动生成雪花主键）, 不依赖表默认主键生成。
     *
     * @param stocksId 股票ID
     * @param from     起始分钟（含）
     * @param count    连续分钟数
     */
    private void insertBackfillMinutes(int stocksId, LocalDateTime from, int count) {
        List<TornStocksHistoryDO> historyList = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            historyList.add(history(stocksId, from.plusMinutes(i)));
        }
        assertTrue(stocksHistoryDao.saveBatch(historyList), "隔离时间命名空间内插入必须全部成功");
    }

    /**
     * 查询指定股票在隔离窗口内的自然分钟计数
     *
     * @param stocksId 股票ID
     * @return 自然分钟计数
     */
    private long minuteCount(int stocksId) {
        return findCount(stocksHistoryDao.selectMinuteCountsByStocksAndRange(
                List.of(stocksId), WIN_START, WIN_END), stocksId);
    }

    /**
     * 从聚合结果中提取指定股票的分钟计数
     *
     * @param counts   聚合结果
     * @param stocksId 股票ID
     * @return 分钟计数, 无SQL行时返回null
     */
    private Long findCount(List<StockHistoryMinuteCount> counts, int stocksId) {
        return counts.stream()
                .filter(count -> count.stocksId() == stocksId)
                .findFirst()
                .map(StockHistoryMinuteCount::minuteCount)
                .orElse(null);
    }

    /**
     * 构建测试历史事实DO（来源TORNSY_BACKFILL, 市值/投资人未提供保持NULL）
     *
     * @param stocksId 股票ID
     * @param minute   自然分钟
     * @return 历史事实DO
     */
    private TornStocksHistoryDO history(int stocksId, LocalDateTime minute) {
        TornStocksHistoryDO history = new TornStocksHistoryDO();
        history.setStocksId(stocksId);
        history.setStocksName("测试股票" + stocksId);
        history.setStocksShortname("T" + stocksId % 100);
        history.setCurrentPrice(new BigDecimal("10.00"));
        history.setMarketCap(null);
        history.setTotalShares(1000000L);
        history.setInvestors(null);
        history.setRegDateTime(minute);
        history.setDataSource(StockHistoryDataSourceEnum.TORNSY_BACKFILL.getCode());
        return history;
    }
}
