package pn.torn.goldeneye.repository.dao.torn.stocks.portfolio;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;
import pn.torn.goldeneye.repository.mapper.torn.stocks.portfolio.TornStockVirtualBatchMapper;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchDO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Torn股票虚拟交易批次持久层类
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.24
 */
@Repository
public class TornStockVirtualBatchDAO extends ServiceImpl<TornStockVirtualBatchMapper, TornStockVirtualBatchDO> {

    /**
     * 查询全部正式活跃批次,批量获取避免N+1
     *
     * @return 正式活跃批次列表
     */
    public List<TornStockVirtualBatchDO> selectActiveFormalBatches() {
        return baseMapper.selectActiveFormalBatches();
    }

    /**
     * 查询全部活跃影子批次(UNLIMITED_SHADOW),批量获取避免N+1
     *
     * @return 影子活跃批次列表
     */
    public List<TornStockVirtualBatchDO> selectActiveShadowBatches() {
        return baseMapper.selectActiveShadowBatches();
    }

    /**
     * 查询待买入批次(预期入场bar时间已到期),批量获取避免N+1
     *
     * @param currentTime 当前时间
     * @return 待买入批次列表
     */
    public List<TornStockVirtualBatchDO> selectPendingEntryBatches(LocalDateTime currentTime) {
        return baseMapper.selectPendingEntryBatches(currentTime);
    }

    /**
     * 查询待卖出批次(预期平仓bar时间已到期),批量获取避免N+1
     *
     * @param currentTime 当前时间
     * @return 待卖出批次列表
     */
    public List<TornStockVirtualBatchDO> selectPendingExitBatches(LocalDateTime currentTime) {
        return baseMapper.selectPendingExitBatches(currentTime);
    }
}
