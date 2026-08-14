package pn.torn.goldeneye.repository.mapper.torn.stocks;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import pn.torn.goldeneye.repository.model.torn.stocks.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Torn股票历史数据库访问层
 *
 * @author Bai
 * @version 1.2.18
 * @since 2026.01.26
 */
@Mapper
public interface TornStocksHistoryMapper extends BaseMapper<TornStocksHistoryDO> {
    /**
     * 获取最近两次记录时间
     *
     * @return 最后两次变动时间
     */
    List<LocalDateTime> getLatestTwoRecordTimes();

    /**
     * 获取显著交易变化的股票
     *
     * @param previousTime 上次时间
     * @param latestTime   最后一次时间
     * @param threshold    查询阈值
     * @return 符合条件的股市变化列表
     */
    List<StocksChangeDO> getGreatTradeChangeList(@Param("latestTime") LocalDateTime latestTime,
                                                 @Param("previousTime") LocalDateTime previousTime,
                                                 @Param("threshold") long threshold);

    /**
     * 获取指定股票的历史交易统计
     *
     * @param stocksIds       股票ID列表
     * @param minSampleVolume 加权最低成交额
     * @param startTime24h    24小时内的开始时间
     * @param startTime7d     7天内的开始时间
     * @return 股票交易状态列表
     */
    List<StocksTradeStatsDO> getTradeStats(@Param("stocksIds") List<Integer> stocksIds,
                                           @Param("minSampleVolume") long minSampleVolume,
                                           @Param("startTime24h") LocalDateTime startTime24h,
                                           @Param("startTime7d") LocalDateTime startTime7d);

    /**
     * 查询所有股票指定时间之后的历史价格点（用于窗口冷启动批量预热）
     *
     * @param since   从何时开始
     * @param endTime 从何时结束
     */
    List<StockPricePoint> selectHistoryPointsRange(@Param("since") LocalDateTime since,
                                                   @Param("endTime") LocalDateTime endTime);

    /**
     * 批量读取指定股票集合在时间范围内已占用的自然分钟槽位
     *
     * @param stocksIds 股票ID列表
     * @param start     起始时间（含）
     * @param end       结束时间（不含）
     * @return 已存在的自然分钟槽位列表
     */
    List<StockHistoryMinuteSlot> selectExistingMinuteSlots(@Param("stocksIds") List<Integer> stocksIds,
                                                           @Param("start") LocalDateTime start,
                                                           @Param("end") LocalDateTime end);

    /**
     * 实时采集自然分钟冲突安全批量写入（显式来源 TORNS_API,与自然分钟唯一索引精确匹配）
     *
     * @param historyList 待写入历史记录列表
     * @return 实际插入行数（自然分钟冲突跳过不计入）
     */
    int insertRealtimeIgnoreConflict(@Param("historyList") List<TornStocksHistoryDO> historyList);

    /**
     * 冲突安全批量写入历史事实并返回实际插入的自然分钟槽位集合
     *
     * @param historyList 待写入历史记录列表
     * @return 实际插入的自然分钟槽位列表
     */
    List<StockHistoryMinuteSlot> insertBackfillReturningSlots(@Param("historyList") List<TornStocksHistoryDO> historyList);

    /**
     * 查询最新历史记录时间
     *
     * @return 最新记录时间，表为空时返回 null
     */
    LocalDateTime selectLatestHistoryTime();
}