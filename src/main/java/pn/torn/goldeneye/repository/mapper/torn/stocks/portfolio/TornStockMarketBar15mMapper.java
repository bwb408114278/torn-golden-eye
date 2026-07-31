package pn.torn.goldeneye.repository.mapper.torn.stocks.portfolio;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Torn股票15分钟K线(bar)数据库访问层
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.24
 */
@Mapper
public interface TornStockMarketBar15mMapper extends BaseMapper<TornStockMarketBar15mDO> {

    List<TornStockMarketBar15mDO> selectByBarStartTime(@Param("barStartTime") LocalDateTime barStartTime,
                                                        @Param("buildVersion") String buildVersion);

    List<TornStockMarketBar15mDO> selectByTimeRange(@Param("startTime") LocalDateTime startTime,
                                                    @Param("endTime") LocalDateTime endTime,
                                                    @Param("buildVersion") String buildVersion);

    List<TornStockMarketBar15mDO> selectEvidenceRanges(@Param("endTime") LocalDateTime endTime,
                                                       @Param("buildVersion") String buildVersion);

    /**
     * 按股票集合和时间范围批量查询bar。
     *
     * @param stocksIds 股票ID列表
     * @param startTime 起始时间(含)
     * @param endTime 结束时间(含)
     * @param buildVersion 构建版本
     * @return bar列表
     */
    List<TornStockMarketBar15mDO> selectByStocksAndTimeRange(
            @Param("stocksIds") List<Integer> stocksIds,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime,
            @Param("buildVersion") String buildVersion);

    /**
     * 查询指定时点前每支股票最新可用bar。
     *
     * @param stocksIds 股票ID列表
     * @param cutoffTime 摘要时点对应的已结束bar时间
     * @param buildVersion 构建版本
     * @return 每支股票至多一条最新可用bar
     */
    List<TornStockMarketBar15mDO> selectLatestUsableByStocks(
            @Param("stocksIds") List<Integer> stocksIds,
            @Param("cutoffTime") LocalDateTime cutoffTime,
            @Param("buildVersion") String buildVersion);

    int upsertBar(@Param("bar") TornStockMarketBar15mDO bar);
}
