package pn.torn.goldeneye.repository.mapper.torn.stocks.portfolio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockAlphaDailySnapshotDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockAlphaDecisionDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockAlphaDailySnapshotDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockAlphaDecisionDO;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * α策略快照与决策 Mapper 真实 PostgreSQL 测试。
 *
 * @author Bai
 * @version 1.6.1
 * @since 2026.09.05
 */
@Tag("shared-db")
@SpringBootTest
@Transactional
@Rollback
class TornStockAlphaPersistenceMapperTest {
    private static final LocalDate BUSINESS_DATE = LocalDate.of(2099, 10, 1);
    private static final Integer STOCKS_ID = 99700001;

    @Autowired
    private TornStockAlphaDailySnapshotDAO snapshotDao;
    @Autowired
    private TornStockAlphaDecisionDAO decisionDao;

    @Test
    @DisplayName("日线快照_批量UPSERT一次写入多条且重复写入收敛为更新")
    void batchInsertIgnoreConflict_shouldUpsertWholeBatchIdempotently() {
        TornStockAlphaDailySnapshotDO first = snapshot(STOCKS_ID, new BigDecimal("10.00"));
        TornStockAlphaDailySnapshotDO second = snapshot(STOCKS_ID + 1, new BigDecimal("20.00"));

        assertEquals(2, snapshotDao.batchInsertIgnoreConflict(List.of(first, second)));
        first.setClosePrice(new BigDecimal("11.00"));
        second.setClosePrice(new BigDecimal("21.00"));
        assertEquals(2, snapshotDao.batchInsertIgnoreConflict(List.of(first, second)));

        assertEquals(0, new BigDecimal("11.00").compareTo(
                snapshotDao.selectByBusinessKeyForUpdate(STOCKS_ID, BUSINESS_DATE,
                        "ALPHA-35-V1", "ALPHA-0.04-V1").getClosePrice()));
        assertEquals(0, new BigDecimal("21.00").compareTo(
                snapshotDao.selectByBusinessKeyForUpdate(STOCKS_ID + 1, BUSINESS_DATE,
                        "ALPHA-35-V1", "ALPHA-0.04-V1").getClosePrice()));
    }

    @Test
    @DisplayName("日线快照_收盘价按2位小数真实读回且不保留更高精度")
    void insertIgnoreConflict_shouldPersistClosePriceWithTwoDecimalScale() {
        TornStockAlphaDailySnapshotDO snapshot = snapshot(STOCKS_ID, new BigDecimal("123.456789"));

        assertEquals(1, snapshotDao.insertIgnoreConflict(snapshot));

        BigDecimal savedPrice = snapshotDao.selectByBusinessKeyForUpdate(STOCKS_ID, BUSINESS_DATE,
                "ALPHA-35-V1", "ALPHA-0.04-V1").getClosePrice();
        assertEquals(0, new BigDecimal("123.46").compareTo(savedPrice), "股票价格必须按2位小数持久化");
        assertEquals(2, savedPrice.scale(), "股票价格不得保留超过2位的小数");
    }

    /**
     * 构造指定股票和收盘价的日线快照。
     *
     * @param stocksId   股票ID
     * @param closePrice 收盘价
     * @return 日线快照
     */
    private TornStockAlphaDailySnapshotDO snapshot(Integer stocksId, BigDecimal closePrice) {
        TornStockAlphaDailySnapshotDO snapshot = new TornStockAlphaDailySnapshotDO();
        snapshot.setStocksId(stocksId);
        snapshot.setBusinessDate(BUSINESS_DATE);
        snapshot.setClosePrice(closePrice);
        snapshot.setSourceBarId(99700001L + stocksId);
        snapshot.setSourceBarStartTime(LocalDateTime.of(2099, 10, 1, 23, 45));
        snapshot.setStockUniverseVersion("ALPHA-35-V1");
        snapshot.setAlphaRuleVersion("ALPHA-0.04-V1");
        snapshot.setCommonValid(true);
        return snapshot;
    }

    @Test
    @DisplayName("日线快照与决策_同一业务键重复写入收敛为更新且不产生重复行")
    void insertIgnoreConflict_shouldKeepDailySnapshotAndDecisionIdempotent() {
        TornStockAlphaDailySnapshotDO snapshot = new TornStockAlphaDailySnapshotDO();
        snapshot.setStocksId(STOCKS_ID);
        snapshot.setBusinessDate(BUSINESS_DATE);
        snapshot.setClosePrice(new BigDecimal("123.456789"));
        snapshot.setSourceBarId(99700001L);
        snapshot.setSourceBarStartTime(LocalDateTime.of(2099, 10, 1, 23, 45));
        snapshot.setStockUniverseVersion("ALPHA-35-V1");
        snapshot.setAlphaRuleVersion("ALPHA-0.04-V1");
        snapshot.setAlphaScore(new BigDecimal("0.9600000000"));
        snapshot.setRankPosition(1);
        snapshot.setCommonValid(true);

        assertEquals(1, snapshotDao.insertIgnoreConflict(snapshot));
        snapshot.setAlphaScore(new BigDecimal("0.9700000000"));
        snapshot.setR20(new BigDecimal("0.1200000000"));
        assertEquals(1, snapshotDao.insertIgnoreConflict(snapshot));
        TornStockAlphaDailySnapshotDO savedSnapshot = snapshotDao.selectByBusinessKeyForUpdate(STOCKS_ID, BUSINESS_DATE,
                "ALPHA-35-V1", "ALPHA-0.04-V1");
        assertNotNull(savedSnapshot);
        assertEquals(new BigDecimal("0.9700000000"), savedSnapshot.getAlphaScore());
        assertEquals(new BigDecimal("0.1200000000"), savedSnapshot.getR20());

        TornStockAlphaDecisionDO decision = new TornStockAlphaDecisionDO();
        decision.setDecisionBusinessDate(BUSINESS_DATE);
        decision.setCommonDayIndex(60);
        decision.setPhase(0);
        decision.setDecisionType("SELECT_TOP1");
        decision.setSourceSnapshotDigest("digest-99700001");
        decision.setExecutionStatus("PENDING");

        assertEquals(1, decisionDao.insertIgnoreConflict(decision));
        decision.setSelectedStocksId(STOCKS_ID);
        decision.setSourceSnapshotDigest("digest-updated");
        assertEquals(1, decisionDao.insertIgnoreConflict(decision));
        TornStockAlphaDecisionDO savedDecision = decisionDao.selectByBusinessKeyForUpdate(BUSINESS_DATE, 0);
        assertNotNull(savedDecision);
        assertEquals(STOCKS_ID, savedDecision.getSelectedStocksId());
        assertEquals("digest-updated", savedDecision.getSourceSnapshotDigest());
    }

    @Test
    @DisplayName("决策桶_decision_bar_start_time真实落库并按业务键读回一致且冲突路径不改写")
    void insertIgnoreConflict_shouldPersistAndKeepDecisionBarStartTime() {
        LocalDateTime decisionBar = LocalDateTime.of(2099, 10, 1, 10, 0);
        LocalDateTime executionBar = decisionBar.plusMinutes(15);

        TornStockAlphaDecisionDO decision = new TornStockAlphaDecisionDO();
        decision.setDecisionBusinessDate(BUSINESS_DATE);
        decision.setCommonDayIndex(60);
        decision.setPhase(3);
        decision.setDecisionType("ALPHA_TARGET_CHANGED");
        decision.setSourceSnapshotDigest("digest-decision-bar");
        decision.setExecutionStatus("PENDING");
        decision.setDecisionBarStartTime(decisionBar);
        decision.setExecutionBarStartTime(executionBar);

        assertEquals(1, decisionDao.insertIgnoreConflict(decision));

        TornStockAlphaDecisionDO saved = decisionDao.selectByBusinessKeyForUpdate(BUSINESS_DATE, 3);
        assertNotNull(saved, "新列必须真实落库并按业务键读回");
        assertEquals(decisionBar, saved.getDecisionBarStartTime(), "决策桶必须与执行桶分离保存");
        assertEquals(executionBar, saved.getExecutionBarStartTime());
        assertEquals(15L, java.time.Duration.between(
                saved.getDecisionBarStartTime(), saved.getExecutionBarStartTime()).toMinutes());

        // 冲突路径只更新决策内容,决策桶与执行桶一样仅在首次落决策时冻结
        decision.setDecisionBarStartTime(decisionBar.plusMinutes(15));
        decision.setSourceSnapshotDigest("digest-decision-bar-updated");
        assertEquals(1, decisionDao.insertIgnoreConflict(decision));

        TornStockAlphaDecisionDO unchanged = decisionDao.selectByBusinessKeyForUpdate(BUSINESS_DATE, 3);
        assertNotNull(unchanged);
        assertEquals("digest-decision-bar-updated", unchanged.getSourceSnapshotDigest());
        assertEquals(decisionBar, unchanged.getDecisionBarStartTime(), "冲突路径不得改写已冻结的决策桶");
    }
}
