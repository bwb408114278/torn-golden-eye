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
     * 批量查询每支股票最近一个更早生效月份且已确认的月度状态。
     *
     * @param stocksIds   股票ID列表
     * @param targetMonth 目标生效月份(不含)
     * @return 每支股票至多一条更早CONFIRMED月度状态;无记录时返回空列表
     */
    public List<TornStockMonthlyStateDO> selectPreviousConfirmedByStocks(List<Integer> stocksIds,
                                                                         LocalDate targetMonth) {
        if (CollectionUtils.isEmpty(stocksIds)) {
            return List.of();
        }
        return baseMapper.selectPreviousConfirmedByStocks(stocksIds, targetMonth);
    }
}
