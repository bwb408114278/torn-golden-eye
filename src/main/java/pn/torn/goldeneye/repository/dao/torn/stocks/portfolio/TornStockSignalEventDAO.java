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
 * @version 1.6.5
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
