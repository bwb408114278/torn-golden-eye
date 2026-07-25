package pn.torn.goldeneye.repository.dao.torn.stocks.portfolio;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Repository;
import pn.torn.goldeneye.repository.mapper.torn.stocks.portfolio.TornStockMonthlyStateMapper;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMonthlyStateDO;

import java.time.LocalDate;
import java.util.List;

/**
 * Torn股票月度风格状态持久层类
 *
 * @author Bai
 * @version 1.2.12
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
}
