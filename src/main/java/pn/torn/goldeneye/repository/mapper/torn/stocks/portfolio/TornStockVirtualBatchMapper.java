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
 * @version 1.6.5
 * @since 2026.07.24
 */
@Mapper
public interface TornStockVirtualBatchMapper extends BaseMapper<TornStockVirtualBatchDO> {

    /**
     * 按批次编号锁定批次。
     *
     * @param batchNo 批次编号
     * @return 已存在批次；不存在时返回null
     */
    TornStockVirtualBatchDO selectByBatchNoForUpdate(@Param("batchNo") String batchNo);

    /**
     * 按批次编号冲突安全插入批次。
     *
     * @param batch 待插入批次
     * @return 实际插入行数；冲突时返回0
     */
    int insertIgnoreConflict(@Param("batch") TornStockVirtualBatchDO batch);

    /**
     * 按组合编码查询α轨道活跃批次(FORMAL与ALPHA_SHADOW账本)。
     *
     * @param portfolioCode α轨道组合编码
     * @return 该轨道的活跃批次列表
     */
    List<TornStockVirtualBatchDO> selectActiveAlphaBatches(@Param("portfolioCode") String portfolioCode);

    /**
     * 按组合编码查询α轨道活跃批次并加事务行锁(FORMAL与ALPHA_SHADOW账本)。
     *
     * @param portfolioCode α轨道组合编码
     * @return 已锁定的该轨道活跃批次列表
     */
    List<TornStockVirtualBatchDO> selectActiveAlphaBatchesForUpdate(@Param("portfolioCode") String portfolioCode);

    /**
     * 查询全部正式活跃批次。
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
     * 查询正式账本指定时间范围内有入场或出场动作的批次。
     *
     * @param startTime 时间范围起点(含)
     * @param endTime   时间范围终点(不含)
     * @return 正式批次
     */
    List<TornStockVirtualBatchDO> selectFormalActionBatches(@Param("startTime") LocalDateTime startTime,
                                                            @Param("endTime") LocalDateTime endTime);

    /**
     * 查询指定组合编码的α轨道(正式α或α影子)指定时间范围内有入场或出场动作的批次。
     *
     * @param portfolioCode 组合编码(正式α或α影子)
     * @param startTime     时间范围起点(含)
     * @param endTime       时间范围终点(不含)
     * @return 该组合的动作批次列表
     */
    List<TornStockVirtualBatchDO> selectAlphaActionBatches(@Param("portfolioCode") String portfolioCode,
                                                           @Param("startTime") LocalDateTime startTime,
                                                           @Param("endTime") LocalDateTime endTime);

    /**
     * 判断是否存在正式或α影子活跃批次。
     *
     * @return 存在活跃批次返回true;否则false
     */
    boolean existsActiveBatches();
}
