package pn.torn.goldeneye.repository.mapper.torn.stocks.portfolio;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchDO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Torn股票虚拟交易批次数据库访问层
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.24
 */
@Mapper
public interface TornStockVirtualBatchMapper extends BaseMapper<TornStockVirtualBatchDO> {

    /**
     * 查询全部正式活跃批次
     *
     * @return 正式活跃批次列表
     */
    List<TornStockVirtualBatchDO> selectActiveFormalBatches();

    /**
     * 查询全部正式活跃批次并加事务行锁。
     *
     * @return 已锁定的正式活跃批次列表
     */
    List<TornStockVirtualBatchDO> selectActiveFormalBatchesForUpdate();

    /**
     * 查询全部活跃影子批次(UNLIMITED_SHADOW)
     *
     * @return 影子活跃批次列表
     */
    List<TornStockVirtualBatchDO> selectActiveShadowBatches();

    /**
     * 查询全部无限资金影子活跃批次并加事务行锁。
     *
     * @return 已锁定的影子活跃批次列表
     */
    List<TornStockVirtualBatchDO> selectActiveShadowBatchesForUpdate();


    /**
     * 查询正式账本指定时间范围内有入场或出场动作的批次。
     *
     * @param startTime 时间范围起点(含)
     * @param endTime   时间范围终点(不含)
     * @return 正式批次
     */
    List<TornStockVirtualBatchDO> selectFormalActionBatches(@Param("startTime") LocalDateTime startTime,
                                                            @Param("endTime") LocalDateTime endTime);

    /**
     * 查询影子账本指定时间范围内有信号或出场动作的批次。
     *
     * @param startTime 时间范围起点(含)
     * @param endTime   时间范围终点(不含)
     * @return 影子批次
     */
    List<TornStockVirtualBatchDO> selectShadowActionBatches(@Param("startTime") LocalDateTime startTime,
                                                            @Param("endTime") LocalDateTime endTime);
}
