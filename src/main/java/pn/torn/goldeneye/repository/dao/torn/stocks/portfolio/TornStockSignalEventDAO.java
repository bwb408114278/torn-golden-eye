package pn.torn.goldeneye.repository.dao.torn.stocks.portfolio;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;
import pn.torn.goldeneye.repository.mapper.torn.stocks.portfolio.TornStockSignalEventMapper;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockSignalEventDO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Torn股票信号事件持久层类
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.24
 */
@Repository
public class TornStockSignalEventDAO extends ServiceImpl<TornStockSignalEventMapper, TornStockSignalEventDO> {

    /**
     * 按轮次时间批量查询全部信号事件,避免逐股查询产生N+1问题
     *
     * @param roundTime 轮次时间
     * @return 该轮次的全部信号事件列表
     */
    public List<TornStockSignalEventDO> selectByRoundTime(LocalDateTime roundTime) {
        return baseMapper.selectByRoundTime(roundTime);
    }

    /**
     * 批量查询未结算拒绝观察事件。
     *
     * @param startTime 轮次起点(含)
     * @param endTime 轮次终点(不含)
     * @return 未结算拒绝观察事件
     */
    public List<TornStockSignalEventDO> selectPendingRejectedObservationEvents(
            LocalDateTime startTime, LocalDateTime endTime) {
        return baseMapper.selectPendingRejectedObservationEvents(startTime, endTime);
    }

    /**
     * 批量查询全部未结算拒绝观察事件,用于停机补偿。
     *
     * @return 未结算拒绝观察事件
     */
    public List<TornStockSignalEventDO> selectAllPendingRejectedObservationEvents() {
        return baseMapper.selectAllPendingRejectedObservationEvents();
    }

    /**
     * 批量回写尚未结算的拒绝观察结果。
     *
     * @param events 拒绝观察结果列表
     * @return 实际更新行数
     */
    public int updateObservationResultsByIds(List<TornStockSignalEventDO> events) {
        if (events == null || events.isEmpty()) {
            return 0;
        }
        return baseMapper.updateObservationResultsByIds(events);
    }
}
