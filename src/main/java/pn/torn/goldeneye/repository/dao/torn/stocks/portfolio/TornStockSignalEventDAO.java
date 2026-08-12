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
 * @version 1.2.14
 * @since 2026.07.24
 */
@Repository
public class TornStockSignalEventDAO extends ServiceImpl<TornStockSignalEventMapper, TornStockSignalEventDO> {

    /**
     * 按业务唯一键查询并锁定信号事件。
     *
     * @param stocksId       股票ID
     * @param strategyType   策略类型
     * @param roundTime      信号轮次
     * @param buyRuleVersion 买入规则版本
     * @return 已存在的业务事件;不存在时返回null
     */
    public TornStockSignalEventDO selectByBusinessKeyForUpdate(Integer stocksId, String strategyType,
                                                               LocalDateTime roundTime, String buyRuleVersion) {
        return baseMapper.selectByBusinessKeyForUpdate(stocksId, strategyType, roundTime, buyRuleVersion);
    }

    /**
     * 按业务唯一键冲突安全插入信号事件。
     *
     * @param event 待插入事件
     * @return 实际插入行数;冲突时返回0
     */
    public int insertIgnoreConflict(TornStockSignalEventDO event) {
        return baseMapper.insertIgnoreConflict(event);
    }

    /**
     * 批量查询未结算拒绝观察事件。
     *
     * @param startTime 轮次起点(含)
     * @param endTime   轮次终点(不含)
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

    /**
     * 判断是否存在未结算的拒绝观察事件。
     * <p>
     * 拒绝观察批次本身是CANCELLED,不能依赖活跃批次查询发现。用于运行时门禁:
     * 即使新买入关闭且无活跃持仓,只要存在未结算拒绝观察,仍应继续构建观察窗口bar并结算研究义务。
     *
     * @return 存在未结算拒绝观察事件返回true;否则false
     */
    public boolean existsPendingRejectedObservationEvents() {
        return baseMapper.existsPendingRejectedObservationEvents();
    }
}
