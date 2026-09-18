package pn.torn.goldeneye.repository.dao.torn.stocks.portfolio;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;
import pn.torn.goldeneye.repository.mapper.torn.stocks.portfolio.TornStockVirtualBatchMapper;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchDO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Torn股票虚拟交易批次持久层类
 *
 * @author Bai
 * @version 1.6.5
 * @since 2026.07.24
 */
@Repository
public class TornStockVirtualBatchDAO extends ServiceImpl<TornStockVirtualBatchMapper, TornStockVirtualBatchDO> {

    /**
     * 按批次编号锁定批次。
     *
     * @param batchNo 批次编号
     * @return 已存在批次；不存在时返回null
     */
    public TornStockVirtualBatchDO selectByBatchNoForUpdate(String batchNo) {
        return baseMapper.selectByBatchNoForUpdate(batchNo);
    }

    /**
     * 按批次编号冲突安全插入批次。
     *
     * @param batch 待插入批次
     * @return 实际插入行数;冲突时返回0
     */
    public int insertIgnoreConflict(TornStockVirtualBatchDO batch) {
        return baseMapper.insertIgnoreConflict(batch);
    }

    /**
     * 查询全部正式活跃批次,批量获取避免N+1
     *
     * @return 正式活跃批次列表
     */
    public List<TornStockVirtualBatchDO> selectActiveFormalBatches() {
        return baseMapper.selectActiveFormalBatches();
    }

    /**
     * 按组合编码查询α轨道活跃批次。
     *
     * @param portfolioCode α轨道组合编码
     * @return 该轨道的活跃批次列表
     */
    public List<TornStockVirtualBatchDO> selectActiveAlphaBatches(String portfolioCode) {
        return baseMapper.selectActiveAlphaBatches(portfolioCode);
    }

    /**
     * 按组合编码查询α轨道活跃批次并加事务行锁。
     *
     * @param portfolioCode α轨道组合编码
     * @return 已锁定的该轨道活跃批次列表
     */
    public List<TornStockVirtualBatchDO> selectActiveAlphaBatchesForUpdate(String portfolioCode) {
        return baseMapper.selectActiveAlphaBatchesForUpdate(portfolioCode);
    }

    /**
     * 查询全部正式活跃批次并加事务行锁。
     *
     * @return 已锁定的正式活跃批次列表
     */
    public List<TornStockVirtualBatchDO> selectActiveFormalBatchesForUpdate() {
        return baseMapper.selectActiveFormalBatchesForUpdate();
    }

    /**
     * 查询正式账本指定时间范围内有入场或出场动作的批次。
     *
     * @param startTime 时间范围起点(含)
     * @param endTime   时间范围终点(不含)
     * @return 正式批次
     */
    public List<TornStockVirtualBatchDO> selectFormalActionBatches(
            LocalDateTime startTime, LocalDateTime endTime) {
        return baseMapper.selectFormalActionBatches(startTime, endTime);
    }

    /**
     * 查询影子账本指定时间范围内有信号或出场动作的批次(历史研究数据只读)。
     *
     * @param startTime 时间范围起点(含)
     * @param endTime   时间范围终点(不含)
     * @return 影子批次
     */
    public List<TornStockVirtualBatchDO> selectShadowActionBatches(
            LocalDateTime startTime, LocalDateTime endTime) {
        return baseMapper.selectShadowActionBatches(startTime, endTime);
    }

    /**
     * 查询候选影子账本(SHADOW_FORMAL_CANDIDATE)的活跃批次(历史数据只读)。
     *
     * @return 候选影子活跃批次列表
     */
    public List<TornStockVirtualBatchDO> selectActiveCandidateShadowBatches() {
        return baseMapper.selectActiveCandidateShadowBatches();
    }

    /**
     * 查询候选影子账本(SHADOW_FORMAL_CANDIDATE)指定时间范围内有入场或出场动作的批次(历史数据只读)。
     *
     * @param startTime 时间范围起点(含)
     * @param endTime   时间范围终点(不含)
     * @return 候选影子动作批次列表
     */
    public List<TornStockVirtualBatchDO> selectCandidateShadowActionBatches(
            LocalDateTime startTime, LocalDateTime endTime) {
        return baseMapper.selectCandidateShadowActionBatches(startTime, endTime);
    }

    /**
     * 判断是否存在正式或α影子活跃批次。
     * <p>
     * 用于运行时门禁判断存量持仓是否需要继续管理,使用SELECT EXISTS避免加载全量列表。
     * 影子α纳入义务判定,避免ALERT关闭后遗弃该账本。
     *
     * @return 存在活跃批次返回true;否则false
     */
    public boolean existsActiveBatches() {
        return baseMapper.existsActiveBatches();
    }
}
