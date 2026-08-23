package pn.torn.goldeneye.torn.service.stocks.replay;

import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockSlotStatusEnum;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockPortfolioSlotDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockSignalStateDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchDO;
import pn.torn.goldeneye.torn.service.stocks.alert.portfolio.StockPortfolioService;
import pn.torn.goldeneye.torn.service.stocks.alert.signal.StockSignalStateKey;
import pn.torn.goldeneye.torn.service.stocks.replay.model.StockReplayTrackEnum;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 回放轨道独立内存组合状态。
 *
 * <p>承载一条正式轨道的槽位、活跃批次与信号状态(边沿/冷却/复位)。全部状态仅存在于
 * 内存,不写任何业务表;不同轨道拥有完全独立的实例。批量DO与槽位DO复用正式领域类型,
 * 使入场/出场结算与路径服务可在内存直接处理。</p>
 *
 * @author Bai
 * @version 1.2.14
 * @since 2026.08.06
 */
public class StockReplayPortfolio {

    private final List<TornStockPortfolioSlotDO> slots;
    private final List<TornStockVirtualBatchDO> activeBatches;
    private final Map<StockSignalStateKey, TornStockSignalStateDO> signalStates;
    private long nextBatchId = 1L;

    /**
     * 构造轨道组合。
     *
     * @param track 正式轨道定义(槽位数与每槽初始资金来自轨道)
     */
    public StockReplayPortfolio(StockReplayTrackEnum track) {
        this.slots = new ArrayList<>();
        for (int i = 1; i <= track.getSlotCount(); i++) {
            TornStockPortfolioSlotDO slot = new TornStockPortfolioSlotDO();
            slot.setId((long) i);
            slot.setPortfolioCode(StockPortfolioService.PORTFOLIO_CODE);
            slot.setSlotNo(i);
            slot.setInitialCash(track.getInitialCashPerSlot());
            slot.setAvailableCash(track.getInitialCashPerSlot());
            slot.setReservedCash(BigDecimal.ZERO);
            slot.setCurrentBatchId(null);
            slot.setSlotStatus(StockSlotStatusEnum.AVAILABLE.getCode());
            slots.add(slot);
        }
        this.activeBatches = new ArrayList<>();
        this.signalStates = new HashMap<>();
    }

    /**
     * 全部槽位。
     *
     * @return 槽位列表
     */
    public List<TornStockPortfolioSlotDO> slots() {
        return slots;
    }

    /**
     * 全部活跃批次(正式+影子合并,被结算服务直接原地变更)。
     *
     * @return 活跃批次列表
     */
    public List<TornStockVirtualBatchDO> activeBatches() {
        return activeBatches;
    }

    /**
     * 信号状态索引(复合键→状态)。
     *
     * @return 信号状态映射
     */
    public Map<StockSignalStateKey, TornStockSignalStateDO> signalStates() {
        return signalStates;
    }

    /**
     * 首个可用槽位。
     *
     * @return 首个AVAILABLE槽位;无可用槽位返回null
     */
    public TornStockPortfolioSlotDO firstAvailableSlot() {
        return slots.stream()
                .filter(slot -> StockSlotStatusEnum.AVAILABLE.getCode().equals(slot.getSlotStatus()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 新增批次并分配内存批次ID。
     *
     * @param batch 批次DO(尚未设置ID)
     * @return 设置ID后的批次DO
     */
    public TornStockVirtualBatchDO addBatch(TornStockVirtualBatchDO batch) {
        batch.setId(nextBatchId++);
        activeBatches.add(batch);
        return batch;
    }
}
