package pn.torn.goldeneye.torn.service.stocks.alert.alpha.track;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockLedgerTypeEnum;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchDO;
import pn.torn.goldeneye.torn.service.stocks.alert.portfolio.StockPortfolioService;

import static org.junit.jupiter.api.Assertions.*;

/**
 * α相位轨道测试。
 * <p>
 * 只覆盖相位语义唯一宿主的判定规则:预热门槛、偏移命中、phase计算,以及同一共同有效日至多只有一条轨道命中。
 *
 * @author Bai
 * @version 1.6.5
 * @since 2026.09.18
 */
@DisplayName("α相位轨道测试")
class StockAlphaPhaseTrackTest {

    @Test
    @DisplayName("相位轨道_偏移0与2各自的决策日与phase_同日至多一条轨道命中")
    void decisionDayAndPhase_offsetZeroAndTwo() {
        StockAlphaPhaseTrack offsetZero = StockAlphaTrackRegistry.VIP_ALPHA_SHADOW_FIRST;
        StockAlphaPhaseTrack offsetTwo = StockAlphaTrackRegistry.VIP_ALPHA_SHADOW_SECOND;
        assertEquals(0, offsetZero.phaseOffset());
        assertEquals(2, offsetTwo.phaseOffset());

        assertFalse(offsetZero.isDecisionDay(59), "未达预热共同有效日不得判为决策日");
        assertFalse(offsetTwo.isDecisionDay(59), "未达预热共同有效日不得判为决策日");

        assertTrue(offsetZero.isDecisionDay(60));
        assertEquals(0, offsetZero.phaseOf(60));
        assertFalse(offsetTwo.isDecisionDay(60), "偏移2在共同有效日60不命中");

        assertTrue(offsetTwo.isDecisionDay(62));
        assertEquals(0, offsetTwo.phaseOf(62));
        assertFalse(offsetZero.isDecisionDay(62), "偏移0在共同有效日62不命中");

        assertTrue(offsetZero.isDecisionDay(65));
        assertEquals(1, offsetZero.phaseOf(65));
        assertTrue(offsetTwo.isDecisionDay(67));
        assertEquals(1, offsetTwo.phaseOf(67));

        // 30个连续共同有效日: 任一天至多只有一条轨道命中, 保证同一(业务日, phase)不会被两条轨道同时消费
        for (int commonDayCount = 60; commonDayCount < 90; commonDayCount++) {
            int hits = 0;
            if (offsetZero.isDecisionDay(commonDayCount)) {
                hits++;
                assertEquals(Math.floorDiv(commonDayCount - 60, 5), offsetZero.phaseOf(commonDayCount));
            }
            if (offsetTwo.isDecisionDay(commonDayCount)) {
                hits++;
                assertEquals(Math.floorDiv(commonDayCount - 60 - 2, 5), offsetTwo.phaseOf(commonDayCount));
            }
            assertTrue(hits <= 1, "同一共同有效日至多只有一条轨道决策: count=" + commonDayCount);
        }

        // 轨道注册表编码查找与账本类型: 正式轨道复用FORMAL账本,影子轨道使用ALPHA_SHADOW账本
        assertEquals(StockAlphaTrackRegistry.productionTrack(),
                StockAlphaTrackRegistry.of(StockAlphaTrackRegistry.productionTrack().trackCode()));
        assertEquals(StockAlphaTrackRegistry.VIP_ALPHA_SHADOW_SECOND,
                StockAlphaTrackRegistry.of(StockAlphaTrackRegistry.VIP_ALPHA_SHADOW_SECOND.trackCode()));
        assertThrows(IllegalArgumentException.class, () -> StockAlphaTrackRegistry.of("UNKNOWN#1"));

        assertEquals(StockLedgerTypeEnum.FORMAL.getCode(),
                StockAlphaTrackRegistry.ledgerTypeOf(StockAlphaTrackRegistry.productionTrack()),
                "正式轨道必须复用FORMAL账本,正式仓账本语义零改动");
        assertEquals(StockLedgerTypeEnum.ALPHA_SHADOW.getCode(),
                StockAlphaTrackRegistry.ledgerTypeOf(StockAlphaTrackRegistry.VIP_ALPHA_SHADOW_FIRST),
                "影子轨道必须使用ALPHA_SHADOW账本");

        assertBatchOwnershipMatchesTrackPortfolioAndSlot();
    }

    /**
     * 验证批次归属唯一宿主{@link StockAlphaPhaseTrack#owns}必须同时匹配组合编码与槽位序号:
     * 空批次、同组合其它槽位与其它组合的批次一律不属于本轨道。
     * <p>
     * 与决策日/phase断言拆分为独立方法,避免单个测试方法的断言数量超过规范门禁。
     */
    private void assertBatchOwnershipMatchesTrackPortfolioAndSlot() {
        StockAlphaPhaseTrack offsetZero = StockAlphaTrackRegistry.VIP_ALPHA_SHADOW_FIRST;
        StockAlphaPhaseTrack offsetTwo = StockAlphaTrackRegistry.VIP_ALPHA_SHADOW_SECOND;

        assertFalse(offsetZero.owns(null), "空批次不属于任何轨道");
        assertTrue(offsetZero.owns(batchAt(StockPortfolioService.VIP_ALPHA_SHADOW_PORTFOLIO_CODE, 1)),
                "同组合同槽位批次必须判定为本轨道批次");
        assertFalse(offsetZero.owns(batchAt(StockPortfolioService.VIP_ALPHA_SHADOW_PORTFOLIO_CODE, 2)),
                "同组合其它槽位的批次不得判为本轨道批次");
        assertFalse(offsetZero.owns(batchAt(StockPortfolioService.VIP_ALPHA_PORTFOLIO_CODE, 1)),
                "其它组合的批次不属于本轨道");
        assertTrue(offsetTwo.owns(batchAt(StockPortfolioService.VIP_ALPHA_SHADOW_PORTFOLIO_CODE, 2)),
                "偏移2的轨道必须认领2号槽位批次");
    }

    /**
     * 构造指定组合与槽位的批次,用于验证轨道归属判定。
     *
     * @param portfolioCode 组合编码
     * @param slotNo        槽位序号
     * @return 批次DO
     */
    private static TornStockVirtualBatchDO batchAt(String portfolioCode, int slotNo) {
        TornStockVirtualBatchDO batch = new TornStockVirtualBatchDO();
        batch.setPortfolioCode(portfolioCode);
        batch.setSlotNo(slotNo);
        return batch;
    }
}
