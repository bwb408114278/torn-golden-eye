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
     * <p>
     * 决策生成与复用统一按本业务键加锁:已存在决策时调用方必须复用原执行桶并重新读取,
     * 不读取完整历史窗口、不重新排名、不改写执行桶;不存在时才允许生成新决策。
     * 禁止用本方法跨执行桶消费决策。
     *
     * @param decisionBusinessDate 决策自然日
     * @param phase                phase编号
     * @return 决策记录;不存在时返回null
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
