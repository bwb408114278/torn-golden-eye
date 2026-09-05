package pn.torn.goldeneye.repository.mapper.torn.stocks.portfolio;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * α策略快照与决策 Mapper 真实 PostgreSQL 测试。
 *
 * @author Bai
 * @version 1.6.1
 * @since 2026.09.05
 */
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
        assertEquals(0, snapshotDao.insertIgnoreConflict(snapshot));
        assertNotNull(snapshotDao.selectByBusinessKeyForUpdate(STOCKS_ID, BUSINESS_DATE,
                "ALPHA-35-V1", "ALPHA-0.04-V1"));

        TornStockAlphaDecisionDO decision = new TornStockAlphaDecisionDO();
        decision.setDecisionBusinessDate(BUSINESS_DATE);
        decision.setCommonDayIndex(60);
        decision.setPhase(0);
        decision.setDecisionType("SELECT_TOP1");
        decision.setSourceSnapshotDigest("digest-99700001");
        decision.setExecutionStatus("PENDING");

        assertEquals(1, decisionDao.insertIgnoreConflict(decision));
        assertEquals(0, decisionDao.insertIgnoreConflict(decision));
        assertNotNull(decisionDao.selectByBusinessKeyForUpdate(BUSINESS_DATE, 0));
    }
}
