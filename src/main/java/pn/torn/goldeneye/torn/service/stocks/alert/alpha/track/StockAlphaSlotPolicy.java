package pn.torn.goldeneye.torn.service.stocks.alert.alpha.track;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockSlotStatusEnum;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockPortfolioSlotDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockPortfolioSlotDO;

import java.util.List;
import java.util.Objects;

/**
 * α槽位选择策略 - 初始入场与原子换仓共用的唯一槽位选择宿主。
 * <p>
 * 选择语义固定为"按轨道组合编码 + 轨道槽位序号精确匹配",不再断言"组合内槽位数量恰好等于1":
 * 多槽组合下同组合存在多个槽位行,按数量断言会误判。任一条件不满足即fail-closed抛出,
 * 由调用方的事务整体回滚,不会只锁部分槽位、不会只扣部分资金。
 *
 * @author Bai
 * @version 1.6.5
 * @since 2026.09.18
 */
@Service
@RequiredArgsConstructor
public class StockAlphaSlotPolicy {

    private final TornStockPortfolioSlotDAO slotDAO;

    /**
     * 选取轨道对应的可用槽位。
     * <p>
     * 使用调用方已加载并加锁的槽位列表,不产生额外查询。
     *
     * @param track 目标轨道
     * @param slots 已加载的全部槽位
     * @return 轨道对应的AVAILABLE槽位
     * @throws IllegalStateException 轨道槽位不唯一或状态不可用时抛出
     */
    public TornStockPortfolioSlotDO requireAvailable(StockAlphaPhaseTrack track,
                                                     List<TornStockPortfolioSlotDO> slots) {
        TornStockPortfolioSlotDO slot = requireUnique(track, slots);
        if (!StockSlotStatusEnum.AVAILABLE.getCode().equals(slot.getSlotStatus())) {
            throw new IllegalStateException("α轨道槽位不可用于初始入场: track=" + track.trackCode()
                    + ", slotStatus=" + slot.getSlotStatus());
        }
        return slot;
    }

    /**
     * 锁定轨道对应的已占用槽位。
     * <p>
     * 换仓必须持有槽位行锁:内部按轨道组合编码查询并加 {@code FOR UPDATE},
     * 再按轨道槽位序号精确匹配,禁止调用方自行拼装第二套槽位查询。
     *
     * @param track 目标轨道
     * @return 轨道对应的OCCUPIED槽位
     * @throws IllegalStateException 轨道槽位不唯一或状态不可用于换仓时抛出
     */
    public TornStockPortfolioSlotDO lockOccupied(StockAlphaPhaseTrack track) {
        List<TornStockPortfolioSlotDO> slots = slotDAO.selectAllByPortfolioCodeForUpdate(track.portfolioCode());
        TornStockPortfolioSlotDO slot = requireUnique(track, slots);
        if (!StockSlotStatusEnum.OCCUPIED.getCode().equals(slot.getSlotStatus())) {
            throw new IllegalStateException("α轨道槽位不可用于原子换仓: track=" + track.trackCode()
                    + ", slotStatus=" + slot.getSlotStatus());
        }
        return slot;
    }

    /**
     * 按轨道组合编码与槽位序号精确匹配唯一槽位。
     *
     * @param track 目标轨道
     * @param slots 候选槽位
     * @return 唯一匹配槽位
     * @throws IllegalStateException 未匹配到或匹配到多个槽位时抛出
     */
    private TornStockPortfolioSlotDO requireUnique(StockAlphaPhaseTrack track,
                                                   List<TornStockPortfolioSlotDO> slots) {
        List<TornStockPortfolioSlotDO> matched = slots == null ? List.of() : slots.stream()
                .filter(Objects::nonNull)
                .filter(slot -> track.portfolioCode().equals(slot.getPortfolioCode()))
                .filter(slot -> Integer.valueOf(track.slotNo()).equals(slot.getSlotNo()))
                .toList();
        if (matched.size() != 1) {
            throw new IllegalStateException("α轨道槽位不唯一或缺失: track=" + track.trackCode()
                    + ", matched=" + matched.size());
        }
        return matched.getFirst();
    }
}
