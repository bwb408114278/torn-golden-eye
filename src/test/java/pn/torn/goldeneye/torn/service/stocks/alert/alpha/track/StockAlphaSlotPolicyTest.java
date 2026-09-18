package pn.torn.goldeneye.torn.service.stocks.alert.alpha.track;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockSlotStatusEnum;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockPortfolioSlotDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockPortfolioSlotDO;
import pn.torn.goldeneye.torn.service.stocks.alert.portfolio.StockPortfolioService;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * α槽位选择策略测试。
 * <p>
 * 只覆盖槽位选择唯一宿主的匹配规则:按轨道组合编码与轨道槽位序号精确匹配,
 * 多槽组合下不再用"槽位数量恰好等于1"判错。
 *
 * @author Bai
 * @version 1.6.5
 * @since 2026.09.18
 */
@DisplayName("α槽位选择策略测试")
@ExtendWith(MockitoExtension.class)
class StockAlphaSlotPolicyTest {

    @Mock
    private TornStockPortfolioSlotDAO slotDAO;

    private StockAlphaSlotPolicy slotPolicy;

    @BeforeEach
    void setUp() {
        slotPolicy = new StockAlphaSlotPolicy(slotDAO);
    }

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

        assertThrows(IllegalStateException.class, () -> slotPolicy.requireAvailable(
                        StockAlphaTrackRegistry.VIP_ALPHA_SHADOW_SECOND, List.of(slots.getFirst())),
                "轨道槽位缺失必须fail-closed");
        assertThrows(IllegalStateException.class, () -> slotPolicy.requireAvailable(
                        StockAlphaTrackRegistry.VIP_ALPHA_SHADOW_FIRST,
                        List.of(slot(1L, StockPortfolioService.VIP_ALPHA_SHADOW_PORTFOLIO_CODE, 1,
                                StockSlotStatusEnum.OCCUPIED))),
                "槽位不可用时不得用于初始入场");
    }

    @Test
    @DisplayName("槽位锁定_换仓按轨道组合加行锁并校验占用状态")
    void lockOccupied_locksByTrackPortfolio() {
        when(slotDAO.selectAllByPortfolioCodeForUpdate(StockPortfolioService.VIP_ALPHA_PORTFOLIO_CODE))
                .thenReturn(List.of(slot(11L, StockPortfolioService.VIP_ALPHA_PORTFOLIO_CODE, 1,
                        StockSlotStatusEnum.OCCUPIED)));

        TornStockPortfolioSlotDO slot = slotPolicy.lockOccupied(StockAlphaTrackRegistry.productionTrack());

        assertEquals(11L, slot.getId());
        assertEquals(1, slot.getSlotNo());
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
