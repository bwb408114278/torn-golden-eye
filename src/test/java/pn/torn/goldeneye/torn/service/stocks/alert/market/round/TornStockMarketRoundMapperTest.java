package pn.torn.goldeneye.torn.service.stocks.alert.market.round;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockRoundStatusEnum;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockMarketRoundDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketRoundDO;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import pn.torn.goldeneye.torn.service.stocks.alert.market.StockMarketRoundFactory;

/**
 * 轮次批量 UPSERT 真实 PostgreSQL 测试。
 *
 * @author Bai
 * @version 1.4.2
 * @since 2026.08.23
 */
@SpringBootTest
@Tag("shared-db")
@Transactional
@Rollback
@DisplayName("轮次批量UPSERT真实PostgreSQL测试")
class TornStockMarketRoundMapperTest {

    @Autowired
    private TornStockMarketRoundDAO roundDao;
    @Autowired
    private StockMarketRoundFactory roundFactory;

    private static final LocalDateTime WIN_START = LocalDateTime.of(2099, 10, 1, 0, 0);

    @Test
    @DisplayName("真实PG_批量UPSERT_新/READY变为REPAIRED_DATA_ONLY且终态完全不变")
    void batchUpsert_repairsNonFinalAndKeepsFinal() {
        LocalDateTime newTime = WIN_START;
        LocalDateTime readyTime = WIN_START.plusMinutes(15);
        LocalDateTime completedTime = WIN_START.plusMinutes(30);
        LocalDateTime failedFinalTime = WIN_START.plusMinutes(45);

        TornStockMarketRoundDO ready = roundFactory.createRound(readyTime, StockRoundStatusEnum.READY.getCode());
        ready.setStartedAt(WIN_START.minusMinutes(1));
        ready.setCompletedAt(null);
        roundDao.save(ready);

        TornStockMarketRoundDO completed = roundFactory.createRound(completedTime, StockRoundStatusEnum.COMPLETED.getCode());
        completed.setExpectedStockCount(35);
        completed.setUsableStockCount(30);
        completed.setStartedAt(WIN_START.minusMinutes(2));
        completed.setCompletedAt(WIN_START.minusMinutes(1));
        roundDao.save(completed);

        TornStockMarketRoundDO failedFinal = roundFactory.createRound(failedFinalTime, StockRoundStatusEnum.FAILED_FINAL.getCode());
        failedFinal.setExpectedStockCount(35);
        failedFinal.setUsableStockCount(0);
        failedFinal.setStartedAt(WIN_START.minusMinutes(3));
        failedFinal.setCompletedAt(WIN_START.minusMinutes(1));
        failedFinal.setErrorMessage("final failure");
        roundDao.save(failedFinal);

        TornStockMarketRoundDO newRound = roundFactory.createRound(newTime, StockRoundStatusEnum.REPAIRED_DATA_ONLY.getCode());
        newRound.setExpectedStockCount(35);
        newRound.setUsableStockCount(28);
        newRound.setStartedAt(WIN_START);
        newRound.setCompletedAt(WIN_START.plusMinutes(1));

        TornStockMarketRoundDO readyRepair = roundFactory.createRound(readyTime, StockRoundStatusEnum.REPAIRED_DATA_ONLY.getCode());
        readyRepair.setExpectedStockCount(35);
        readyRepair.setUsableStockCount(29);
        readyRepair.setStartedAt(WIN_START);
        readyRepair.setCompletedAt(WIN_START.plusMinutes(1));

        TornStockMarketRoundDO completedRepair = roundFactory.createRound(completedTime, StockRoundStatusEnum.REPAIRED_DATA_ONLY.getCode());
        completedRepair.setExpectedStockCount(1);
        completedRepair.setUsableStockCount(1);
        completedRepair.setStartedAt(WIN_START);
        completedRepair.setCompletedAt(WIN_START.plusMinutes(1));

        TornStockMarketRoundDO failedFinalRepair = roundFactory.createRound(failedFinalTime, StockRoundStatusEnum.REPAIRED_DATA_ONLY.getCode());
        failedFinalRepair.setExpectedStockCount(1);
        failedFinalRepair.setUsableStockCount(1);
        failedFinalRepair.setStartedAt(WIN_START);
        failedFinalRepair.setCompletedAt(WIN_START.plusMinutes(1));

        int affected = roundDao.upsertRepairedDataOnlyRounds(List.of(
                newRound, readyRepair, completedRepair, failedFinalRepair));

        assertEquals(2, affected, "新记录和READY应受影响；COMPLETED/FAILED_FINAL不应被更新");

        TornStockMarketRoundDO persistedNew = roundDao.selectByRoundTime(newTime);
        assertNotNull(persistedNew);
        assertEquals(StockRoundStatusEnum.REPAIRED_DATA_ONLY.getCode(), persistedNew.getRoundStatus());
        assertEquals(35, persistedNew.getExpectedStockCount());
        assertEquals(28, persistedNew.getUsableStockCount());

        TornStockMarketRoundDO persistedReady = roundDao.selectByRoundTime(readyTime);
        assertEquals(StockRoundStatusEnum.REPAIRED_DATA_ONLY.getCode(), persistedReady.getRoundStatus());
        assertEquals(35, persistedReady.getExpectedStockCount());
        assertEquals(29, persistedReady.getUsableStockCount());

        TornStockMarketRoundDO persistedCompleted = roundDao.selectByRoundTime(completedTime);
        assertEquals(StockRoundStatusEnum.COMPLETED.getCode(), persistedCompleted.getRoundStatus());
        assertEquals(35, persistedCompleted.getExpectedStockCount());
        assertEquals(30, persistedCompleted.getUsableStockCount());
        assertEquals(WIN_START.minusMinutes(2), persistedCompleted.getStartedAt());
        assertEquals(WIN_START.minusMinutes(1), persistedCompleted.getCompletedAt());

        TornStockMarketRoundDO persistedFailedFinal = roundDao.selectByRoundTime(failedFinalTime);
        assertEquals(StockRoundStatusEnum.FAILED_FINAL.getCode(), persistedFailedFinal.getRoundStatus());
        assertEquals(35, persistedFailedFinal.getExpectedStockCount());
        assertEquals(0, persistedFailedFinal.getUsableStockCount());
        assertEquals("final failure", persistedFailedFinal.getErrorMessage());
    }
}
