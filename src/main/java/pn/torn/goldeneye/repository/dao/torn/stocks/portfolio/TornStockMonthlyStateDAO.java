package pn.torn.goldeneye.repository.dao.torn.stocks.portfolio;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.repository.mapper.torn.stocks.portfolio.TornStockMonthlyStateMapper;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMonthlyStateDO;

import java.time.LocalDate;
import java.util.List;

/**
 * Torn股票月度风格状态持久层类
 *
 * @author Bai
 * @version 1.2.14
 * @since 2026.07.24
 */
@Repository
public class TornStockMonthlyStateDAO extends ServiceImpl<TornStockMonthlyStateMapper, TornStockMonthlyStateDO> {

    /**
     * 查询指定月份已确认的月度状态,批量获取避免N+1
     *
     * @param effectiveMonth 生效月份
     * @return 已确认的月度状态列表
     */
    public List<TornStockMonthlyStateDO> selectConfirmedByMonth(LocalDate effectiveMonth) {
        return baseMapper.selectConfirmedByMonth(effectiveMonth);
    }

    /**
     * 范围批量查询已确认的月度状态(含起止月份),避免按月循环发SQL。
     *
     * @param startMonth 起始生效月份(含)
     * @param endMonth   结束生效月份(含)
     * @return 起止月份之间已确认的月度状态列表
     */
    public List<TornStockMonthlyStateDO> selectConfirmedByMonthRange(LocalDate startMonth, LocalDate endMonth) {
        return baseMapper.selectConfirmedByMonthRange(startMonth, endMonth);
    }

    /**
     * 查询指定月份存在任意有效状态的股票ID集合,不按state_status过滤。
     *
     * @param effectiveMonth 生效月份
     * @return 当月已有任意有效状态的股票ID列表;无记录时返回空列表
     */
    public List<Integer> selectExistingStockIdsByMonth(LocalDate effectiveMonth) {
        return baseMapper.selectExistingStockIdsByMonth(effectiveMonth);
    }

    /**
     * 批量、冲突安全地插入月度状态草稿,返回实际插入行数。
     * <p>
     * 同月同股票已存在任意有效状态时,该行被数据库{@code ON CONFLICT DO NOTHING}
     * 忽略,不抛重复键异常,也不覆盖既有DRAFT/CONFIRMED/RETIRED。
     *
     * @param states 待插入草稿列表
     * @return 实际插入行数;入参为空时返回0
     */
    public int insertDraftStatesIgnoreConflict(List<TornStockMonthlyStateDO> states) {
        if (CollectionUtils.isEmpty(states)) {
            return 0;
        }
        return baseMapper.insertDraftStatesIgnoreConflict(states);
    }

    /**
     * 查询上一确认月度状态(含metricSnapshot供读取raw字段)。
     *
     * @param stockIds    股票ID列表
     * @param targetMonth 目标生效月份(不含)
     * @return 每支股票至多一条更早CONFIRMED月度状态
     */
    public List<TornStockMonthlyStateDO> selectPreviousConfirmedByStocks(List<Integer> stockIds,
                                                                         LocalDate targetMonth) {
        if (stockIds == null || stockIds.isEmpty()) {
            return List.of();
        }
        return baseMapper.selectPreviousConfirmedByStocks(stockIds, targetMonth);
    }

    /**
     * 条件批量重算当月未确认DRAFT月度状态。
     * <p>
     * 仅更新 {@code state_status='DRAFT' AND manual_override=false} 的记录,UPDATE自带状态谓词,
     * 防止并发重算或人工修改在SELECT与UPDATE之间改变状态后被误写。
     *
     * @param states 重算后的DRAFT状态列表(须携带主键id)
     * @return 实际更新行数;空列表返回0
     */
    public int recalculateDraftStates(List<TornStockMonthlyStateDO> states) {
        if (states == null || states.isEmpty()) {
            return 0;
        }
        return baseMapper.recalculateDraftStates(states);
    }

    /**
     * 条件批量自动确认当月可确认DRAFT月度状态。
     * <p>
     * 仅更新 {@code state_status='DRAFT' AND manual_override=false AND deleted=0} 的记录,
     * UPDATE自带状态谓词,防止人工确认或并发状态变更在SELECT与UPDATE之间发生后,
     * 被过期的Java对象(读-写竞态)误写为SYSTEM确认。
     *
     * @param states 待自动确认的DRAFT状态列表(须携带主键id)
     * @return 实际受影响行数;空列表返回0
     */
    public int autoConfirmDraftStates(List<TornStockMonthlyStateDO> states) {
        if (states == null || states.isEmpty()) {
            return 0;
        }
        return baseMapper.autoConfirmDraftStates(states);
    }
}
