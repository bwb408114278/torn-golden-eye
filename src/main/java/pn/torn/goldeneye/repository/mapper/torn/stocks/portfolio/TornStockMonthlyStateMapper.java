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
 * @version 1.2.14
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

    /**
     * 查询指定月份存在任意有效状态(stocks_id)集合,不按state_status过滤。
     * <p>
     * 用于月度初始化幂等过滤:同月每股票至多一行有效状态,只要已存在
     * DRAFT/CONFIRMED/RETIRED等任意状态,都不得再INSERT该股票。
     *
     * @param effectiveMonth 生效月份
     * @return 当月已有任意有效状态的股票ID列表;无记录时返回空列表
     */
    List<Integer> selectExistingStockIdsByMonth(@Param("effectiveMonth") LocalDate effectiveMonth);

    /**
     * 批量、冲突安全地插入月度状态草稿。
     * <p>
     * 明确列出全部INSERT列,不使用SELECT *;PostgreSQL按部分唯一索引
     * {@code (stocks_id, effective_month) WHERE deleted = 0} 使用
     * {@code ON CONFLICT ... DO NOTHING},任一冲突行被忽略不影响其余行插入。
     * 返回实际插入行数;不更新已存在DRAFT/CONFIRMED/RETIRED。
     *
     * @param states 待插入草稿列表
     * @return 实际插入行数
     */
    int insertDraftStatesIgnoreConflict(@Param("states") List<TornStockMonthlyStateDO> states);
}
