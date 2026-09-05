package pn.torn.goldeneye.repository.mapper.torn.stocks.portfolio;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockAlphaDecisionDO;

import java.time.LocalDate;

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
     * 按决策业务键冲突安全插入记录。
     *
     * @param decision 待插入决策
     * @return 实际插入行数
     */
    int insertIgnoreConflict(@Param("decision") TornStockAlphaDecisionDO decision);
}
