package pn.torn.goldeneye.repository.mapper.torn.stocks.portfolio;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockBatchMarkDO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Torn股票批次标记数据库访问层
 *
 * @author Bai
 * @version 1.2.14
 * @since 2026.07.24
 */
@Mapper
public interface TornStockBatchMarkMapper extends BaseMapper<TornStockBatchMarkDO> {

    /**
     * 按批次ID批量查询全部mark
     *
     * @param batchId 批次ID
     * @return 该批次的全部标记列表(按轮次时间升序)
     */
    List<TornStockBatchMarkDO> selectByBatchId(@Param("batchId") Long batchId);

    /**
     * 查询摘要日内、关联活跃或当日动作的正式/候选影子批次的动态SELL研究mark。
     * <p>
     * 以{@code torn_stock_batch_mark}为唯一数据源统计动态SELL研究状态:
     * 分母为{@code dynamic_shadow_decision}或{@code dynamic_shadow_reason}非空的研究mark数,
     * 完整数由上层按{@code decision=NOT_EVALUATED AND reason=DYNAMIC_RULE_NOT_FROZEN}判定,
     * 缺失数=分母-完整数,覆盖率=完整数/分母。
     * 关联批次限定正式或候选影子账本(禁止统计无限资金/拒绝观察),
     * 且批次为活跃状态或当日有入场/出场动作,避免把长期已关闭批次的陈旧mark计入研究输入。
     *
     * @param startTime 摘要日期起点(含)
     * @param endTime   摘要日期终点(不含)
     * @return 研究mark列表(按轮次时间、ID升序)
     */
    List<TornStockBatchMarkDO> selectDynamicShadowResearchMarks(@Param("startTime") LocalDateTime startTime,
                                                                @Param("endTime") LocalDateTime endTime);
}
