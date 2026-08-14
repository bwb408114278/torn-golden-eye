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
 * @version 1.2.17
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
    public List<TornStockStrategyFeature15mDO> selectByBarStartTime(LocalDateTime barStartTime,
                                                                    String featureVersion) {
        return baseMapper.selectByBarStartTime(barStartTime, featureVersion);
    }

    /**
     * 按时间范围、股票集合和特征版本批量查询特征,避免逐股查询产生N+1问题。
     *
     * @param stocksIds      股票ID列表
     * @param startTime      起始时间(含)
     * @param endTime        结束时间(含)
     * @param featureVersion 特征版本
     * @return 按股票ID与bar时间升序排列的特征列表
     */
    public List<TornStockStrategyFeature15mDO> selectByStocksAndTimeRange(List<Integer> stocksIds,
                                                                          LocalDateTime startTime,
                                                                          LocalDateTime endTime,
                                                                          String featureVersion) {
        return baseMapper.selectByStocksAndTimeRange(stocksIds, startTime, endTime, featureVersion);
    }

    /**
     * 按时间范围和特征版本批量查询全部股票特征。
     *
     * @param startTime      起始时间(含)
     * @param endTime        结束时间(含)
     * @param featureVersion 特征计算版本
     * @return 按股票ID与bar时间升序排列的特征列表
     */
    public List<TornStockStrategyFeature15mDO> selectByTimeRange(LocalDateTime startTime,
                                                                 LocalDateTime endTime,
                                                                 String featureVersion) {
        return baseMapper.selectByTimeRange(startTime, endTime, featureVersion);
    }

    /**
     * 按唯一键执行UPSERT,支持幂等重试
     * <p>
     * 窗口指标允许为空并原样持久化: 对应时间窗口不足或指标不可计算时为{@code null},
     * 由{@code strategyReady=false}与{@code dataQualityReason}解释该空值,不得填充伪造值。
     *
     * @param feature 待插入或更新的特征
     */
    public void upsertFeature(TornStockStrategyFeature15mDO feature) {
        baseMapper.upsertFeature(feature);
    }
}
