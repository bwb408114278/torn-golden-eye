package pn.torn.goldeneye.repository.dao.torn.stocks.portfolio;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;
import pn.torn.goldeneye.repository.mapper.torn.stocks.portfolio.TornStockAlphaDecisionMapper;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockAlphaDecisionDO;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * α策略决策持久层。
 *
 * @author Bai
 * @version 1.6.1
 * @since 2026.09.05
 */
@Repository
public class TornStockAlphaDecisionDAO extends ServiceImpl<TornStockAlphaDecisionMapper, TornStockAlphaDecisionDO> {
    /**
     * 按决策自然日和phase锁定决策记录。
     *
     * @param decisionBusinessDate 决策自然日
     * @param phase                phase编号
     * @return 决策记录
     */
    public TornStockAlphaDecisionDO selectByBusinessKeyForUpdate(LocalDate decisionBusinessDate, Integer phase) {
        return baseMapper.selectByBusinessKeyForUpdate(decisionBusinessDate, phase);
    }

    /**
     * 按决策日期、phase和执行桶锁定待消费的初始入场决策。
     *
     * @param decisionBusinessDate  决策自然日
     * @param phase                 phase编号
     * @param executionBarStartTime 执行bar起点
     * @return 待消费初始决策
     */
    public TornStockAlphaDecisionDO selectPendingInitialEntryForUpdate(LocalDate decisionBusinessDate,
                                                                       Integer phase,
                                                                       LocalDateTime executionBarStartTime) {
        return baseMapper.selectPendingInitialEntryForUpdate(decisionBusinessDate, phase, executionBarStartTime);
    }

    /**
     * 按决策业务键冲突安全插入记录。
     *
     * @param decision 待插入决策
     * @return 实际插入行数
     */
    public int insertIgnoreConflict(TornStockAlphaDecisionDO decision) {
        return baseMapper.insertIgnoreConflict(decision);
    }
}
