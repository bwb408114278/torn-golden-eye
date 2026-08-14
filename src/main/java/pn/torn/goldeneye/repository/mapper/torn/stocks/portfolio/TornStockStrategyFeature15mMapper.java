package pn.torn.goldeneye.repository.mapper.torn.stocks.portfolio;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockStrategyFeature15mDO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Torn股票15分钟bar策略特征数据库访问层
 *
 * @author Bai
 * @version 1.2.17
 * @since 2026.07.24
 */
@Mapper
public interface TornStockStrategyFeature15mMapper extends BaseMapper<TornStockStrategyFeature15mDO> {

    /**
     * 按bar开始时间和特征版本批量查询全部股票特征
     *
     * @param barStartTime   bar开始时间
     * @param featureVersion 特征版本
     * @return 该时间点指定版本的全部股票特征列表
     */
    List<TornStockStrategyFeature15mDO> selectByBarStartTime(@Param("barStartTime") LocalDateTime barStartTime,
                                                             @Param("featureVersion") String featureVersion);

    /**
     * 按时间范围、股票集合和特征版本批量查询特征。
     *
     * @param stocksIds      股票ID列表
     * @param startTime      起始时间(含)
     * @param endTime        结束时间(含)
     * @param featureVersion 特征版本
     * @return 按股票ID与bar时间升序排列的特征列表
     */
    List<TornStockStrategyFeature15mDO> selectByStocksAndTimeRange(@Param("stocksIds") List<Integer> stocksIds,
                                                                   @Param("startTime") LocalDateTime startTime,
                                                                   @Param("endTime") LocalDateTime endTime,
                                                                   @Param("featureVersion") String featureVersion);

    /**
     * 按时间范围和特征版本批量查询全部股票特征。
     *
     * @param startTime      起始时间(含)
     * @param endTime        结束时间(含)
     * @param featureVersion 特征计算版本
     * @return 按股票ID与bar时间升序排列的特征列表
     */
    List<TornStockStrategyFeature15mDO> selectByTimeRange(@Param("startTime") LocalDateTime startTime,
                                                          @Param("endTime") LocalDateTime endTime,
                                                          @Param("featureVersion") String featureVersion);

    /**
     * 按唯一键(stocks_id, bar_start_time, feature_version)执行UPSERT
     * <p>
     * 窗口指标(ma、zscore、return 等以及 low30d/high30d、width30d、pct_above/below)
     * 允许为空并原样持久化: 对应时间窗口不足或指标不可计算时为空,由{@code strategy_ready=false}
     * 与{@code data_quality_reason}解释该空值。
     *
     * @param feature 待插入或更新的特征
     * @return 影响行数
     */
    int upsertFeature(@Param("feature") TornStockStrategyFeature15mDO feature);
}
