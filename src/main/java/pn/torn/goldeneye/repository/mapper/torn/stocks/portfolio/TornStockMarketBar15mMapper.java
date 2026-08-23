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
 * @version 1.4.2
 * @since 2026.07.24
 */
@Mapper
public interface TornStockMarketBar15mMapper extends BaseMapper<TornStockMarketBar15mDO> {
    /**
     * 按bar开始时间与构建版本批量查询全部股票bar。
     *
     * @param barStartTime bar开始时间
     * @param buildVersion bar构建版本
     * @return 指定时间点和版本的股票bar列表
     */
    List<TornStockMarketBar15mDO> selectByBarStartTime(@Param("barStartTime") LocalDateTime barStartTime,
                                                       @Param("buildVersion") String buildVersion);

    /**
     * 按时间范围与构建版本批量查询全部股票bar。
     *
     * @param startTime    起始时间(含)
     * @param endTime      结束时间(含)
     * @param buildVersion bar构建版本
     * @return 按股票ID和bar时间升序排列的bar列表
     */
    List<TornStockMarketBar15mDO> selectByTimeRange(@Param("startTime") LocalDateTime startTime,
                                                    @Param("endTime") LocalDateTime endTime,
                                                    @Param("buildVersion") String buildVersion);

    /**
     * 按股票集合和时间范围批量查询bar。
     *
     * @param stocksIds    股票ID列表
     * @param startTime    起始时间(含)
     * @param endTime      结束时间(含)
     * @param buildVersion 构建版本
     * @return bar列表
     */
    List<TornStockMarketBar15mDO> selectByStocksAndTimeRange(@Param("stocksIds") List<Integer> stocksIds,
                                                             @Param("startTime") LocalDateTime startTime,
                                                             @Param("endTime") LocalDateTime endTime,
                                                             @Param("buildVersion") String buildVersion);

    /**
     * 查询指定时点前每支股票最新可用bar。
     *
     * @param stocksIds     股票ID列表
     * @param cutoffTime    摘要允许参与估值的最新bar开始时间
     * @param minBarEndTime 摘要允许参与估值的最早bar结束时间(含)
     * @param buildVersion  构建版本
     * @return 每支股票至多一条最新可用bar
     */
    List<TornStockMarketBar15mDO> selectLatestUsableByStocks(@Param("stocksIds") List<Integer> stocksIds,
                                                             @Param("cutoffTime") LocalDateTime cutoffTime,
                                                             @Param("minBarEndTime") LocalDateTime minBarEndTime,
                                                             @Param("buildVersion") String buildVersion);

    /**
     * 批量查询指定截止时间前每支股票可用bar的首尾时间。
     * <p>
     * 仅统计{@code usable=true}且价格为正的bar,供月度状态计算定位证据窗口。
     *
     * @param stocksIds    股票ID列表
     * @param cutoffTime   证据截止时间(不含)
     * @param buildVersion bar构建版本
     * @return 每支股票一条证据首尾时间记录(复用first_sample_time/last_sample_time承载首尾bar时间)
     */
    List<TornStockMarketBar15mDO> selectUsableEvidenceEdges(@Param("stocksIds") List<Integer> stocksIds,
                                                            @Param("cutoffTime") LocalDateTime cutoffTime,
                                                            @Param("buildVersion") String buildVersion);

    /**
     * 按股票集合和时间范围批量查询可用bar(仅usable且价格为正)。
     *
     * @param stocksIds    股票ID列表
     * @param startTime    起始时间(含)
     * @param endTime      结束时间(含)
     * @param buildVersion bar构建版本
     * @return 可用bar列表,按股票ID和bar时间升序
     */
    List<TornStockMarketBar15mDO> selectUsableByStocksAndTimeRange(@Param("stocksIds") List<Integer> stocksIds,
                                                                   @Param("startTime") LocalDateTime startTime,
                                                                   @Param("endTime") LocalDateTime endTime,
                                                                   @Param("buildVersion") String buildVersion);

    /**
     * 批量按逻辑唯一键插入或更新股票bar。
     *
     * @param bars 待插入或更新的bar列表
     * @return 受影响行数
     */
    int upsertBars(@Param("bars") List<TornStockMarketBar15mDO> bars);

    /**
     * 按单支股票、时间范围与构建版本批量查询bar，供 feature 顺序批处理使用。
     *
     * @param stocksId        股票ID
     * @param startInclusive  起始时间（含）
     * @param endExclusive    结束时间（不含）
     * @param buildVersion    bar构建版本
     * @return 按bar时间升序排列的bar列表
     */
    List<TornStockMarketBar15mDO> selectByStockAndTimeRange(
            @Param("stocksId") Integer stocksId,
            @Param("startInclusive") LocalDateTime startInclusive,
            @Param("endExclusive") LocalDateTime endExclusive,
            @Param("buildVersion") String buildVersion);
}
