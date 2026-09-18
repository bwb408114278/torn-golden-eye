package pn.torn.goldeneye.torn.service.stocks.alert.alpha.track;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

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
    }

    @Test
    @DisplayName("轨道注册表_编码查找与正式轨道恒启用语义")
    void registry_lookupAndLedgerType() {
        assertEquals(StockAlphaTrackRegistry.productionTrack(),
                StockAlphaTrackRegistry.of(StockAlphaTrackRegistry.productionTrack().trackCode()));
        assertEquals(StockAlphaTrackRegistry.VIP_ALPHA_SHADOW_SECOND,
                StockAlphaTrackRegistry.of(StockAlphaTrackRegistry.VIP_ALPHA_SHADOW_SECOND.trackCode()));
        assertThrows(IllegalArgumentException.class, () -> StockAlphaTrackRegistry.of("UNKNOWN#1"));

        assertEquals(pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockLedgerTypeEnum.FORMAL.getCode(),
                StockAlphaTrackRegistry.ledgerTypeOf(StockAlphaTrackRegistry.productionTrack()),
                "正式轨道必须复用FORMAL账本,正式仓账本语义零改动");
        assertEquals(pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockLedgerTypeEnum.ALPHA_SHADOW.getCode(),
                StockAlphaTrackRegistry.ledgerTypeOf(StockAlphaTrackRegistry.VIP_ALPHA_SHADOW_FIRST),
                "影子轨道必须使用ALPHA_SHADOW账本");
        assertEquals(2, List.of(StockAlphaTrackRegistry.VIP_ALPHA_SHADOW_FIRST,
                StockAlphaTrackRegistry.VIP_ALPHA_SHADOW_SECOND).size(), "影子组合固定2条轨道");
    }
}
