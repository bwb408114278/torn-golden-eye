package pn.torn.goldeneye.torn.service.stocks.alert.alpha.track;

import org.springframework.stereotype.Service;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockSlotStatusEnum;
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
 * @version 1.9.0
 * @since 2026.09.18
 */
@Service
public class StockAlphaSlotPolicy {

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
     * 选取轨道对应的已占用槽位。
     * <p>
     * 换仓的槽位行锁由轮次事务在同一事务内按组合编码 {@code FOR UPDATE} 加载后随快照传入,
     * 本方法只做轨道匹配与占用状态校验,不再产生额外查询:二次查询会得到与轮次快照不同的Java实例,
     * 轮次末按快照写回时会把槽位资金与批次绑定覆盖回换仓前的值。
     *
     * @param track 目标轨道
     * @param slots 已加载并加锁的全部槽位
     * @return 轨道对应的OCCUPIED槽位
     * @throws IllegalStateException 轨道槽位不唯一或状态不可用于换仓时抛出
     */
    public TornStockPortfolioSlotDO requireOccupied(StockAlphaPhaseTrack track,
                                                    List<TornStockPortfolioSlotDO> slots) {
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
