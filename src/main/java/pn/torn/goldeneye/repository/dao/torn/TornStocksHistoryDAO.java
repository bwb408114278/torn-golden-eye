package pn.torn.goldeneye.repository.dao.torn;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;
import pn.torn.goldeneye.repository.mapper.torn.TornStocksHistoryMapper;
import pn.torn.goldeneye.repository.model.torn.StocksChangeDO;
import pn.torn.goldeneye.repository.model.torn.TornStocksHistoryDO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Torn股票历史持久层类
 *
 * @author Bai
 * @version 0.5.0
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
}