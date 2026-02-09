package pn.torn.goldeneye.repository.mapper.torn;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import pn.torn.goldeneye.repository.model.torn.StocksChangeDO;
import pn.torn.goldeneye.repository.model.torn.TornStocksHistoryDO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Torn股票历史数据库访问层
 *
 * @author Bai
 * @version 0.5.0
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
}