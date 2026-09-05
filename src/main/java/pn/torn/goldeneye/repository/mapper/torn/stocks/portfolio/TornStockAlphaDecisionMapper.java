package pn.torn.goldeneye.repository.mapper.torn.stocks.portfolio;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockAlphaDecisionDO;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * α策略决策数据库访问层。
 *
 * @author Bai
 * @version 1.6.1
 * @since 2026.09.05
 */
@Mapper
public interface TornStockAlphaDecisionMapper extends BaseMapper<TornStockAlphaDecisionDO> {
    /**
     * 按决策自然日和phase锁定决策记录。
     *
     * @param decisionBusinessDate 决策自然日
     * @param phase                phase编号
     * @return 决策记录
     */
    TornStockAlphaDecisionDO selectByBusinessKeyForUpdate(@Param("decisionBusinessDate") LocalDate decisionBusinessDate,
                                                          @Param("phase") Integer phase);

    /**
     * 按决策日期、phase和执行桶锁定待消费的初始入场决策。
     *
     * @param decisionBusinessDate  决策自然日
     * @param phase                 phase编号
     * @param executionBarStartTime 执行bar起点
     * @return 待消费初始决策
     */
    TornStockAlphaDecisionDO selectPendingInitialEntryForUpdate(
            @Param("decisionBusinessDate") LocalDate decisionBusinessDate,
            @Param("phase") Integer phase,
            @Param("executionBarStartTime") LocalDateTime executionBarStartTime);

    /**
     * 按决策业务键冲突安全插入记录。
     *
     * @param decision 待插入决策
     * @return 实际插入行数
     */
    int insertIgnoreConflict(@Param("decision") TornStockAlphaDecisionDO decision);
}
