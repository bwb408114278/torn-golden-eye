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
 * @version 1.2.14
 * @since 2026.07.24
 */
@Mapper
public interface TornStockVirtualBatchMapper extends BaseMapper<TornStockVirtualBatchDO> {

    /**
     * 按来源事件和账本类型查询并锁定批次。
     *
     * @param signalEventId 来源信号事件ID
     * @param ledgerType    账本类型
     * @return 已存在的批次;不存在时返回null
     */
    TornStockVirtualBatchDO selectBySignalEventIdAndLedgerTypeForUpdate(@Param("signalEventId") Long signalEventId,
                                                                        @Param("ledgerType") String ledgerType);

    /**
     * 按股票+主策略+版本锁定同股同策略活跃无限资金影子批次。
     * <p>
     * 部分唯一索引 {@code uk_stock_virtual_batch_shadow_stock_strat_ver} 约束同股同策略
     * 同版本仅存在一条活跃无限资金影子批次; 积压/回放在同一墙钟分钟处理多个历史round时,
     * 同股同策略的第二个round必须复用已存在批次而非新建, 否则触发唯一约束异常。
     *
     * @param stocksId        股票ID
     * @param primaryStrategy 主策略编码
     * @param buyRuleVersion  买入规则版本
     * @return 已存在的同股同策略活跃无限资金影子批次;不存在时返回null
     */
    TornStockVirtualBatchDO selectActiveUnlimitedShadowByStockStrategyForUpdate(@Param("stocksId") Integer stocksId,
                                                                                @Param("primaryStrategy") String primaryStrategy,
                                                                                @Param("buyRuleVersion") String buyRuleVersion);

    /**
     * 按批次编号冲突安全插入批次。
     *
     * @param batch 待插入批次
     * @return 实际插入行数;冲突时返回0
     */
    int insertIgnoreConflict(@Param("batch") TornStockVirtualBatchDO batch);

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

    /**
     * 查询候选影子账本(SHADOW_FORMAL_CANDIDATE)的活跃批次,固定SQL替代Java散落OR条件。
     *
     * @return 候选影子活跃批次列表
     */
    List<TornStockVirtualBatchDO> selectActiveCandidateShadowBatches();

    /**
     * 查询候选影子账本(SHADOW_FORMAL_CANDIDATE)指定时间范围内有入场或出场动作的批次。
     *
     * @param startTime 时间范围起点(含)
     * @param endTime   时间范围终点(不含)
     * @return 候选影子动作批次列表
     */
    List<TornStockVirtualBatchDO> selectCandidateShadowActionBatches(@Param("startTime") LocalDateTime startTime,
                                                                     @Param("endTime") LocalDateTime endTime);

    /**
     * 按信号事件ID批量查询拒绝观察批次。
     *
     * @param signalEventIds 信号事件ID列表
     * @return 拒绝观察批次
     */
    List<TornStockVirtualBatchDO> selectRejectedObservationBatches(@Param("signalEventIds") List<Long> signalEventIds);

    /**
     * 判断是否存在正式或无限资金影子活跃批次。
     *
     * @return 存在活跃批次返回true;否则false
     */
    boolean existsActiveBatches();
}
