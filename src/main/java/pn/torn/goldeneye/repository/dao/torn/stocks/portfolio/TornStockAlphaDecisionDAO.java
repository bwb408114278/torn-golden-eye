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
     * 锁定最近一次已持久化决策。
     * <p>
     * 只用于决策服务在读取完整历史窗口前做幂等短路判断,
     * 执行消费仍必须按"决策业务键+执行桶"锁定,禁止用本方法跨桶消费。
     *
     * @return 最近一次决策记录;不存在时返回null
     */
    public TornStockAlphaDecisionDO selectLatestForUpdate() {
        return baseMapper.selectLatestForUpdate();
    }

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
     * 按决策日期、phase和执行桶锁定决策记录。
     * <p>
     * 执行阶段以"决策业务键+持久化执行桶"锁定,执行桶不一致时返回null,由调用方fail-closed跳过。
     *
     * @param decisionBusinessDate  决策自然日
     * @param phase                 phase编号
     * @param executionBarStartTime 持久化执行bar起点
     * @return 决策记录;执行桶不一致时返回null
     */
    public TornStockAlphaDecisionDO selectByExecutionKeyForUpdate(LocalDate decisionBusinessDate, Integer phase,
                                                                  LocalDateTime executionBarStartTime) {
        return baseMapper.selectByExecutionKeyForUpdate(decisionBusinessDate, phase, executionBarStartTime);
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
