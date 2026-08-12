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
     * 范围批量查询已确认的月度状态(含起止月份)。
     * <p>
     * 供隔离回放一次性加载整个窗口的月度状态,禁止按月循环发SQL。
     *
     * @param startMonth 起始生效月份(含)
     * @param endMonth   结束生效月份(含)
     * @return 起止月份之间已确认的月度状态列表
     */
    List<TornStockMonthlyStateDO> selectConfirmedByMonthRange(@Param("startMonth") LocalDate startMonth,
                                                              @Param("endMonth") LocalDate endMonth);

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

    /**
     * 批量查询每支股票最近一个更早生效月份且已确认的月度状态。
     * <p>
     * 用于月度迟滞计算:同一股票取{@code effective_month < targetMonth}且
     * {@code state_status = CONFIRMED}的最近一条;DRAFT/RETIRED不参与。
     *
     * @param stocksIds   股票ID列表
     * @param targetMonth 目标生效月份(不含)
     * @return 每支股票至多一条更早CONFIRMED月度状态(含metricSnapshot供读取raw字段)
     */
    List<TornStockMonthlyStateDO> selectPreviousConfirmedByStocks(@Param("stocksIds") List<Integer> stocksIds,
                                                                  @Param("targetMonth") LocalDate targetMonth);

    /**
     * 条件批量重算当月未确认DRAFT月度状态。
     * <p>
     * 仅更新 {@code state_status='DRAFT' AND manual_override=false} 的记录:
     * CONFIRMED、RETIRED、任何人工覆盖(manual_override=true)记录均不会被覆盖、
     * 降级或改写confirmedBy/confirmedAt。UPDATE自带状态谓词,防止并发重算或人工修改
     * 在SELECT与UPDATE之间改变状态后被误写。
     *
     * @param states 重算后的DRAFT状态列表(须携带主键id)
     * @return 实际更新行数
     */
    int recalculateDraftStates(@Param("states") List<TornStockMonthlyStateDO> states);

    /**
     * 条件批量自动确认当月可确认DRAFT月度状态。
     * <p>
     * 按主键批量UPDATE,但仅当行满足 {@code state_status='DRAFT' AND manual_override=false AND deleted=0}
     * 时才实际写入。返回实际受影响行数:已CONFIRMED/RETIRED或人工覆盖(manual_override=true)的行
     * 不满足谓词,不会被覆盖、降级或改写confirmedBy/confirmedAt。UPDATE自带状态谓词,防止人工确认或
     * 并发状态变更在SELECT与UPDATE之间发生后,被过期的Java对象(读-写竞态)误写为SYSTEM确认。
     *
     * @param states 待自动确认的DRAFT状态列表(须携带主键id,且已置CONFIRMED/confirmedAt/confirmedBy)
     * @return 实际受影响行数;已确认/已退役/人工覆盖行不满足谓词,不计入返回值
     */
    int autoConfirmDraftStates(@Param("states") List<TornStockMonthlyStateDO> states);
}
