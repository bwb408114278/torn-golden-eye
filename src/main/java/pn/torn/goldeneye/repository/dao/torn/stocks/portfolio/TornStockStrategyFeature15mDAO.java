package pn.torn.goldeneye.repository.dao.torn.stocks.portfolio;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;
import pn.torn.goldeneye.repository.mapper.torn.stocks.portfolio.TornStockStrategyFeature15mMapper;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockStrategyFeature15mDO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Torn股票15分钟bar策略特征持久层类
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.24
 */
@Repository
public class TornStockStrategyFeature15mDAO
        extends ServiceImpl<TornStockStrategyFeature15mMapper, TornStockStrategyFeature15mDO> {

    /**
     * 按bar开始时间批量查询全部股票特征,避免逐股查询产生N+1问题
     *
     * @param barStartTime bar开始时间
     * @return 该时间点的全部股票特征列表
     */
    public List<TornStockStrategyFeature15mDO> selectByBarStartTime(LocalDateTime barStartTime) {
        return baseMapper.selectByBarStartTime(barStartTime);
    }

    /**
     * 查询指定股票在指定时间及之前的最新特征,避免逐股查询产生N+1问题
     *
     * @param stocksIds       股票ID列表
     * @param maxBarStartTime 最大bar开始时间(含)
     * @return 按股票ID与bar时间倒序排列的特征列表
     */
    public List<TornStockStrategyFeature15mDO> selectLatestByStocksIds(List<Integer> stocksIds,
                                                                       LocalDateTime maxBarStartTime) {
        return baseMapper.selectLatestByStocksIds(stocksIds, maxBarStartTime);
    }

    /**
     * 按唯一键执行UPSERT,支持幂等重试
     *
     * @param feature 待插入或更新的特征
     */
    public void upsertFeature(TornStockStrategyFeature15mDO feature) {
        baseMapper.upsertFeature(feature);
    }
}
