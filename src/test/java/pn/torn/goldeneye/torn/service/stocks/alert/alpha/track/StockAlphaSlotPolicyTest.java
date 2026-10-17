package pn.torn.goldeneye.torn.service.stocks.alert.alpha.track;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockSlotStatusEnum;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockPortfolioSlotDO;
import pn.torn.goldeneye.torn.service.stocks.alert.portfolio.StockPortfolioService;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * α槽位选择策略测试。
 * <p>
 * 只覆盖槽位选择唯一宿主的匹配规则:按轨道组合编码与轨道槽位序号精确匹配,
 * 多槽组合下不再用"槽位数量恰好等于1"判错。
 *
 * @author Bai
 * @version 1.9.0
 * @since 2026.09.18
 */
@DisplayName("α槽位选择策略测试")
class StockAlphaSlotPolicyTest {

    private final StockAlphaSlotPolicy slotPolicy = new StockAlphaSlotPolicy();

    @Test
    @DisplayName("槽位选择_按轨道组合与槽位序号精确匹配_多槽组合下数量不为1不再误判")
    void requireAvailable_matchesByTrackPortfolioAndSlotNo() {
        List<TornStockPortfolioSlotDO> slots = List.of(
                slot(1L, StockPortfolioService.VIP_ALPHA_SHADOW_PORTFOLIO_CODE, 1,
                        StockSlotStatusEnum.AVAILABLE),
                slot(2L, StockPortfolioService.VIP_ALPHA_SHADOW_PORTFOLIO_CODE, 2,
                        StockSlotStatusEnum.AVAILABLE));

        TornStockPortfolioSlotDO second = slotPolicy.requireAvailable(
                StockAlphaTrackRegistry.VIP_ALPHA_SHADOW_SECOND, slots);
        assertEquals(2, second.getSlotNo(), "偏移2的轨道必须精确选中2号槽位");
        assertSame(slots.get(1), second, "必须返回列表中同一槽位对象");

        TornStockPortfolioSlotDO first = slotPolicy.requireAvailable(
                StockAlphaTrackRegistry.VIP_ALPHA_SHADOW_FIRST, slots);
        assertEquals(1, first.getSlotNo(), "偏移0的轨道必须精确选中1号槽位");

        List<TornStockPortfolioSlotDO> missingSecondSlot = List.of(slots.getFirst());
        List<TornStockPortfolioSlotDO> occupiedFirstSlot = List.of(
                slot(1L, StockPortfolioService.VIP_ALPHA_SHADOW_PORTFOLIO_CODE, 1, StockSlotStatusEnum.OCCUPIED));

        assertThrows(IllegalStateException.class, () -> slotPolicy.requireAvailable(
                        StockAlphaTrackRegistry.VIP_ALPHA_SHADOW_SECOND, missingSecondSlot),
                "轨道槽位缺失必须fail-closed");
        assertThrows(IllegalStateException.class, () -> slotPolicy.requireAvailable(
                        StockAlphaTrackRegistry.VIP_ALPHA_SHADOW_FIRST, occupiedFirstSlot),
                "槽位不可用时不得用于初始入场");

        // 换仓只校验调用方已加锁的槽位列表并返回同一实例,不再二次查询
        TornStockPortfolioSlotDO occupied = slot(11L, StockPortfolioService.VIP_ALPHA_PORTFOLIO_CODE, 1,
                StockSlotStatusEnum.OCCUPIED);
        TornStockPortfolioSlotDO locked = slotPolicy.requireOccupied(
                StockAlphaTrackRegistry.productionTrack(), List.of(occupied));

        assertEquals(11L, locked.getId());
        assertEquals(1, locked.getSlotNo());
        assertSame(occupied, locked, "必须返回调用方已加锁列表中的同一槽位对象");
        List<TornStockPortfolioSlotDO> availableSlot = List.of(
                slot(11L, StockPortfolioService.VIP_ALPHA_PORTFOLIO_CODE, 1, StockSlotStatusEnum.AVAILABLE));
        assertThrows(IllegalStateException.class, () -> slotPolicy.requireOccupied(
                        StockAlphaTrackRegistry.productionTrack(), availableSlot),
                "槽位未占用时不得用于原子换仓");
        assertThrows(IllegalStateException.class, () -> slotPolicy.requireOccupied(
                        StockAlphaTrackRegistry.productionTrack(), List.of()),
                "槽位缺失必须fail-closed");
    }

    /**
     * 构造槽位DO。
     *
     * @param id            槽位ID
     * @param portfolioCode 组合编码
     * @param slotNo        槽位序号
     * @param status        槽位状态
     * @return 槽位DO
     */
    private TornStockPortfolioSlotDO slot(Long id, String portfolioCode, int slotNo,
                                          StockSlotStatusEnum status) {
        TornStockPortfolioSlotDO slot = new TornStockPortfolioSlotDO();
        slot.setId(id);
        slot.setPortfolioCode(portfolioCode);
        slot.setSlotNo(slotNo);
        slot.setSlotStatus(status.getCode());
        slot.setInitialCash(new BigDecimal("5000000000.00"));
        slot.setAvailableCash(new BigDecimal("5000000000.00"));
        slot.setReservedCash(BigDecimal.ZERO);
        return slot;
    }
}
