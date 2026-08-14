package pn.torn.goldeneye.repository.dao.torn.stocks;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;
import pn.torn.goldeneye.repository.mapper.torn.stocks.TornStocksHistoryMapper;
import pn.torn.goldeneye.repository.model.torn.stocks.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Torn股票历史持久层类
 *
 * @author Bai
 * @version 1.2.18
 * @since 2026.01.26
 */
@Repository
public class TornStocksHistoryDAO extends ServiceImpl<TornStocksHistoryMapper, TornStocksHistoryDO> {

    /**
     * 获取最近两次记录时间
     *
     * @return 最后两次变动时间
     */
    public List<LocalDateTime> getLatestTwoRecordTimes() {
        return baseMapper.getLatestTwoRecordTimes();
    }

    /**
     * 获取显著交易变化的股票
     *
     * @param previousTime 上次时间
     * @param latestTime   最后一次时间
     * @param threshold    查询阈值
     * @return 符合条件的股市变化列表
     */
    public List<StocksChangeDO> getGreatTradeChangeList(LocalDateTime latestTime, LocalDateTime previousTime,
                                                        long threshold) {
        return baseMapper.getGreatTradeChangeList(latestTime, previousTime, threshold);
    }

    /**
     * 获取指定股票的历史交易统计
     *
     * @param stocksIds       股票ID列表
     * @param minSampleVolume 加权最低成交额
     * @param startTime24h    24小时内的开始时间
     * @param startTime7d     7天内的开始时间
     * @return 股票交易状态列表
     */
    public List<StocksTradeStatsDO> getTradeStats(List<Integer> stocksIds, long minSampleVolume,
                                                  LocalDateTime startTime24h, LocalDateTime startTime7d) {
        return baseMapper.getTradeStats(stocksIds, minSampleVolume, startTime24h, startTime7d);
    }

    /**
     * 查询指定时间范围内的历史价格点（不含 endTime）
     *
     * @param since   起始时间（含）
     * @param endTime 结束时间（不含）
     * @return 历史价格点列表
     */
    public List<StockPricePoint> selectHistoryPointsRange(LocalDateTime since, LocalDateTime endTime) {
        return baseMapper.selectHistoryPointsRange(since, endTime);
    }

    /**
     * 批量读取指定股票集合在时间范围内已占用的自然分钟槽位，减少回填无效写入
     *
     * @param stocksIds 股票ID列表
     * @param start     起始时间（含）
     * @param end       结束时间（不含）
     * @return 已存在的自然分钟槽位列表
     */
    public List<StockHistoryMinuteSlot> selectExistingMinuteSlots(List<Integer> stocksIds,
                                                                  LocalDateTime start, LocalDateTime end) {
        return baseMapper.selectExistingMinuteSlots(stocksIds, start, end);
    }

    /**
     * 实时采集自然分钟冲突安全批量写入（显式来源 TORNS_API,与自然分钟唯一索引精确匹配）。
     * <p>
     * 供实时采集短路径使用,{@code reg_date_time} 为计划自然分钟采样键;
     * 禁止复用普通 {@code saveBatch()} 作为最终幂等方案。
     *
     * @param historyList 待写入历史记录列表
     * @return 实际插入行数（自然分钟冲突跳过不计入）
     */
    public int insertRealtimeIgnoreConflict(List<TornStocksHistoryDO> historyList) {
        return baseMapper.insertRealtimeIgnoreConflict(historyList);
    }

    /**
     * 冲突安全批量写入历史事实并返回实际插入的自然分钟槽位集合。
     * <p>
     * 使用 {@code INSERT ... ON CONFLICT DO NOTHING RETURNING} 返回真正写入的
     * {@code (stocksId, minuteTime)},冲突行不产生派生数据重建义务。
     *
     * @param historyList 待写入历史记录列表
     * @return 实际插入的自然分钟槽位列表（可能为空）
     */
    public List<StockHistoryMinuteSlot> insertBackfillReturningSlots(List<TornStocksHistoryDO> historyList) {
        return baseMapper.insertBackfillReturningSlots(historyList);
    }

    /**
     * 查询最新历史记录时间（用于启动/日志的历史进度观察）
     *
     * @return 最新记录时间，表为空时返回 null
     */
    public LocalDateTime selectLatestHistoryTime() {
        return baseMapper.selectLatestHistoryTime();
    }
}