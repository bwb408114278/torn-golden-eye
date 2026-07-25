package pn.torn.goldeneye.repository.mapper.torn.stocks.portfolio;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMonthlyStateDO;

import java.time.LocalDate;
import java.util.List;

/**
 * Torn股票月度风格状态数据库访问层
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.24
 */
@Mapper
public interface TornStockMonthlyStateMapper extends BaseMapper<TornStockMonthlyStateDO> {

    /**
     * 查询指定月份已确认的月度状态
     *
     * @param effectiveMonth 生效月份
     * @return 已确认的月度状态列表
     */
    List<TornStockMonthlyStateDO> selectConfirmedByMonth(@Param("effectiveMonth") LocalDate effectiveMonth);
}
