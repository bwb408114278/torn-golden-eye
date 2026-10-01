package pn.torn.goldeneye.repository.mapper.torn.stocks.portfolio;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockPortfolioAnnualSettlementDO;

import java.time.LocalDateTime;

/**
 * Torn股票α正式仓年度结算台账数据库访问层
 *
 * @author Bai
 * @version 1.6.6
 * @since 2026.10.01
 */
@Mapper
public interface TornStockPortfolioAnnualSettlementMapper extends BaseMapper<TornStockPortfolioAnnualSettlementDO> {

    /**
     * 冲突安全地插入年度结算台账行。
     * <p>
     * 执行 {@code INSERT ... ON CONFLICT (portfolio_code, settle_year, rule_version) WHERE deleted = 0 DO NOTHING},
     * 与部分唯一索引 {@code uk_stock_annual_settlement_business} 语义一致:并发或重复触发时只有一行,
     * 影响行数为0表示该年度已存在台账行,调用方据此放弃重复提取。
     *
     * @param settlement 待插入的年度结算台账行
     * @return 实际插入行数(0表示与已存在台账行冲突被忽略)
     */
    int insertIgnoreConflict(@Param("settlement") TornStockPortfolioAnnualSettlementDO settlement);

    /**
     * 按业务唯一键查询年度结算台账行
     *
     * @param portfolioCode 组合编码
     * @param settleYear    被结算的自然年
     * @param ruleVersion   结算口径版本
     * @return 台账行;不存在时返回null
     */
    TornStockPortfolioAnnualSettlementDO selectByBusinessKey(@Param("portfolioCode") String portfolioCode,
                                                             @Param("settleYear") Integer settleYear,
                                                             @Param("ruleVersion") String ruleVersion);

    /**
     * 查询指定年度之前最近一个已结算的台账行
     *
     * @param portfolioCode 组合编码
     * @param ruleVersion   结算口径版本
     * @param settleYear    被结算的自然年(严格早于该年的已结算行)
     * @return 最近的已结算台账行;不存在时返回null
     */
    TornStockPortfolioAnnualSettlementDO selectLatestSettledBefore(@Param("portfolioCode") String portfolioCode,
                                                                   @Param("ruleVersion") String ruleVersion,
                                                                   @Param("settleYear") Integer settleYear);

    /**
     * 已结算台账行落库:首次插入,或把既有降级行补齐为已结算。
     * <p>
     * 执行 {@code INSERT ... ON CONFLICT (portfolio_code, settle_year, rule_version) WHERE deleted = 0 DO UPDATE},
     * 全部金额列只在本语句出现;冲突时补齐既有降级行而不是放弃本次提取,并清空历史降级原因。
     *
     * @param settlement  已结算台账行(金额与派生字段必须齐全)
     * @param businessNow 业务时间
     * @return 受影响行数(正常为1)
     */
    int upsertSettled(@Param("settlement") TornStockPortfolioAnnualSettlementDO settlement,
                      @Param("businessNow") LocalDateTime businessNow);

    /**
     * 回写降级或阻断状态,不写任何金额字段;已结算行不会被降级覆盖。
     *
     * @param settlement  降级台账行(必须携带主键、状态与原因)
     * @param businessNow 业务时间
     * @return 实际更新行数(0表示台账行已结算或被清除)
     */
    int updateDegradedById(@Param("settlement") TornStockPortfolioAnnualSettlementDO settlement,
                           @Param("businessNow") LocalDateTime businessNow);

    /**
     * 回填年度报告通知审计行ID与通知状态快照
     *
     * @param id           台账行主键
     * @param noticeId     通知审计行ID
     * @param noticeStatus 通知状态快照
     * @param businessNow  业务时间
     * @return 实际更新行数
     */
    int updateNoticeById(@Param("id") Long id,
                         @Param("noticeId") Long noticeId,
                         @Param("noticeStatus") String noticeStatus,
                         @Param("businessNow") LocalDateTime businessNow);
}
