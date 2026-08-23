package pn.torn.goldeneye.torn.service.stocks.alert.signal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockCloseTypeEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockObservationResultEnum;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockSignalEventDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockStrategyFeature15mDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockSignalEventDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockStrategyFeature15mDO;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import pn.torn.goldeneye.torn.service.stocks.alert.market.Stock15mFeatureBuildService;

/**
 * 拒绝观察理论退出生命周期真实PostgreSQL集成测试。
 * <p>
 * 使用隔离股票ID(2099xx)与远端未来时间,通过{@code @Transactional}回滚保证零残留。
 * 验证:
 * <ul>
 *   <li>信号事件新增理论退出列可通过DAO完整写入与读回(迁移已应用);</li>
 *   <li>{@code updateObservationResultsByIds} 正确回写理论生命周期字段且只更新未结算事件;</li>
 *   <li>特征按股票+时间范围批量查询可用。</li>
 * </ul>
 *
 * @author Bai
 * @version 1.2.14
 * @since 2026.08.06
 */
@SpringBootTest
@Tag("shared-db")
@Transactional
@DisplayName("拒绝观察理论退出生命周期真实PostgreSQL集成测试")
class TornStockSignalEventTheoreticalExitDataTest {

    @Autowired
    private TornStockSignalEventDAO signalEventDao;
    @Autowired
    private TornStockStrategyFeature15mDAO featureDao;

    /**
     * 隔离测试股票ID(远离生产35支股票)
     */
    private static final int STOCK = 2099003;

    @Test
    @DisplayName("真实PG_信号事件理论退出列完整写入读回且结算条件生效")
    void updateObservationResults_theoreticalColumnsRoundtrip() {
        TornStockSignalEventDO event = buildEvent();
        signalEventDao.save(event);
        Long eventId = event.getId();
        assertNotNull(eventId, "保存后应回填主键");

        TornStockSignalEventDO toUpdate = buildEvent();
        toUpdate.setId(eventId);
        toUpdate.setLaterMfe(new BigDecimal("0.05"));
        toUpdate.setLaterMae(new BigDecimal("-0.02"));
        toUpdate.setResolvedAt(LocalDateTime.of(2099, 9, 2, 10, 15));
        toUpdate.setObservationResult(StockObservationResultEnum.OBSERVATION_COMPLETED.getCode());
        toUpdate.setObservationDataIncomplete(false);
        toUpdate.setTheoreticalEntryTime(LocalDateTime.of(2099, 9, 1, 10, 15));
        toUpdate.setTheoreticalEntryPrice(new BigDecimal("100.00"));
        toUpdate.setTheoreticalExitSignalTime(LocalDateTime.of(2099, 9, 2, 10, 0));
        toUpdate.setTheoreticalExitTime(LocalDateTime.of(2099, 9, 2, 10, 15));
        toUpdate.setTheoreticalExitPrice(new BigDecimal("100.90"));
        toUpdate.setTheoreticalCloseType(StockCloseTypeEnum.CLOSED_TARGET.getCode());
        toUpdate.setTheoreticalNetReturn(new BigDecimal("0.00799"));

        int updated = signalEventDao.updateObservationResultsByIds(List.of(toUpdate));
        assertEquals(1, updated, "未结算事件应回写理论生命周期字段");

        TornStockSignalEventDO reloaded = signalEventDao.getById(eventId);
        assertNotNull(reloaded);
        assertEquals(toUpdate.getTheoreticalEntryTime(), reloaded.getTheoreticalEntryTime());
        assertEquals(0, toUpdate.getTheoreticalEntryPrice().compareTo(reloaded.getTheoreticalEntryPrice()));
        assertEquals(toUpdate.getTheoreticalExitSignalTime(), reloaded.getTheoreticalExitSignalTime());
        assertEquals(toUpdate.getTheoreticalExitTime(), reloaded.getTheoreticalExitTime());
        assertEquals(0, toUpdate.getTheoreticalExitPrice().compareTo(reloaded.getTheoreticalExitPrice()));
        assertEquals(StockCloseTypeEnum.CLOSED_TARGET.getCode(), reloaded.getTheoreticalCloseType());
        assertEquals(0, toUpdate.getTheoreticalNetReturn().compareTo(reloaded.getTheoreticalNetReturn()));
    }

    @Test
    @DisplayName("真实PG_已结算事件不重复回写理论字段")
    void updateObservationResults_alreadyResolved_notUpdated() {
        TornStockSignalEventDO event = buildEvent();
        event.setResolvedAt(LocalDateTime.of(2099, 9, 1, 12, 0));
        signalEventDao.save(event);
        Long eventId = event.getId();

        TornStockSignalEventDO toUpdate = buildEvent();
        toUpdate.setId(eventId);
        toUpdate.setResolvedAt(LocalDateTime.of(2099, 9, 2, 12, 0));
        toUpdate.setObservationResult(StockObservationResultEnum.OBSERVATION_COMPLETED.getCode());
        toUpdate.setObservationDataIncomplete(false);
        toUpdate.setTheoreticalEntryTime(LocalDateTime.of(2099, 9, 1, 10, 15));
        toUpdate.setTheoreticalEntryPrice(new BigDecimal("100.00"));

        int updated = signalEventDao.updateObservationResultsByIds(List.of(toUpdate));
        assertEquals(0, updated, "已结算事件不得重复回写");
    }

    @Test
    @DisplayName("真实PG_特征按股票与时间范围批量查询可用")
    void selectFeaturesByStocksAndTimeRange_returnsWindowFeatures() {
        LocalDateTime inWindow = LocalDateTime.of(2099, 9, 1, 10, 15);
        LocalDateTime before = LocalDateTime.of(2099, 9, 1, 10, 0);
        LocalDateTime after = LocalDateTime.of(2099, 9, 1, 10, 30);
        featureDao.upsertFeature(buildFeature(inWindow, new BigDecimal("0.65")));
        featureDao.upsertFeature(buildFeature(before, new BigDecimal("0.40")));
        featureDao.upsertFeature(buildFeature(after, new BigDecimal("0.80")));

        List<TornStockStrategyFeature15mDO> features = featureDao.selectByStocksAndTimeRange(
                List.of(STOCK), LocalDateTime.of(2099, 9, 1, 10, 10),
                LocalDateTime.of(2099, 9, 1, 10, 25),
                Stock15mFeatureBuildService.FEATURE_VERSION);

        assertEquals(1, features.size(), "只应返回窗口内特征");
        assertEquals(0, new BigDecimal("0.65").compareTo(features.getFirst().getPosition30()));
    }

    private TornStockSignalEventDO buildEvent() {
        TornStockSignalEventDO event = new TornStockSignalEventDO();
        event.setEventNo("IT" + STOCK + System.nanoTime());
        event.setRoundTime(LocalDateTime.of(2099, 9, 1, 10, 0));
        event.setStocksId(STOCK);
        event.setStocksShortname("IT" + (STOCK % 10000));
        event.setStrategyType("DEEP_REVERSION");
        event.setSignalReferencePrice(new BigDecimal("100.00"));
        event.setBuyRuleVersion("1.0.0");
        event.setQualityScore(new BigDecimal("60.00000000"));
        event.setFeatureSnapshot("{}");
        event.setStyleSnapshot("{}");
        event.setEligibilityResult("FAIL");
        event.setEligibilityReasons("[]");
        event.setPortfolioDecision("REJECTED");
        event.setRejectReason("COOLDOWN_ACTIVE");
        event.setObservationDataIncomplete(false);
        return event;
    }

    private TornStockStrategyFeature15mDO buildFeature(LocalDateTime barStartTime, BigDecimal position30) {
        TornStockStrategyFeature15mDO feature = new TornStockStrategyFeature15mDO();
        feature.setStocksId(STOCK);
        feature.setStocksShortname("IT" + (STOCK % 10000));
        feature.setBarStartTime(barStartTime);
        feature.setReferencePrice(new BigDecimal("100.00"));
        feature.setMa1d(new BigDecimal("100.00"));
        feature.setMa7d(new BigDecimal("100.00"));
        feature.setMa30d(new BigDecimal("100.00"));
        feature.setZscore1d(new BigDecimal("0.00"));
        feature.setZscore7d(new BigDecimal("0.00"));
        feature.setZscore30d(new BigDecimal("0.00"));
        feature.setReturn6h(new BigDecimal("0.00"));
        feature.setReturn1d(new BigDecimal("0.00"));
        feature.setReturn7d(new BigDecimal("0.00"));
        feature.setReturn14d(new BigDecimal("0.00"));
        feature.setLow30d(new BigDecimal("95.00"));
        feature.setHigh30d(new BigDecimal("105.00"));
        feature.setWidth30d(new BigDecimal("10.00"));
        feature.setPosition30(position30);
        feature.setPctAbove30dLow(new BigDecimal("0.50"));
        feature.setPctBelow30dHigh(new BigDecimal("-0.0476"));
        feature.setStrategyReady(true);
        feature.setFeatureVersion(Stock15mFeatureBuildService.FEATURE_VERSION);
        return feature;
    }
}
