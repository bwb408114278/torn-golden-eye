package pn.torn.goldeneye.repository.model.torn.stocks.portfolio;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import pn.torn.goldeneye.repository.model.BaseDO;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 股票α正式仓年度结算台账
 *
 * <p>一行表示一个 {@code (portfolio_code, settle_year, rule_version)} 的年度结算事实,追加式台账,
 * 同时承载"累计提取利润"科目。该表只读既有资金与持仓事实,不产生任何资金划转:
 * 提取只是账面科目,槽内资金继续复利。
 *
 * @author Bai
 * @version 1.6.6
 * @since 2026.10.01
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("torn_stock_portfolio_annual_settlement")
public class TornStockPortfolioAnnualSettlementDO extends BaseDO {

    /**
     * 主键ID
     */
    private Long id;
    /**
     * 组合编码,本轮只允许VIP_ALPHA
     */
    private String portfolioCode;
    /**
     * 被结算的自然年
     */
    private Integer settleYear;
    /**
     * 年度边界时点=次年1月1日00:00(Asia/Shanghai)
     */
    private LocalDateTime boundaryTime;
    /**
     * 边界行情桶起点=被结算年12月31日23:45
     */
    private LocalDateTime boundaryBarStartTime;
    /**
     * 边界行情证明摘要(stocksId:barId:lastPrice按stocksId升序拼接后SHA-256)
     */
    private String boundaryBarDigest;
    /**
     * 初始资金快照(报表基准,正式仓10.00b)
     */
    private BigDecimal initialCash;
    /**
     * 本年度基准=初始资金+累计已提取
     */
    private BigDecimal openingEquity;
    /**
     * 边界可用现金快照(只读)
     */
    private BigDecimal closingCash;
    /**
     * 边界预留资金快照(只读)
     */
    private BigDecimal closingReserved;
    /**
     * 边界持仓可变现市值(已扣0.1%卖出费)
     */
    private BigDecimal closingMarketValue;
    /**
     * 年末边界权益
     */
    private BigDecimal closingEquity;
    /**
     * 本次结算前累计已提取
     */
    private BigDecimal cumulativeExtractedBefore;
    /**
     * 本年提取额(可为负,负值表示账面利润被年度亏损回撤)
     */
    private BigDecimal extractedAmount;
    /**
     * 截至本年末累计已提取
     */
    private BigDecimal cumulativeExtractedAfter;
    /**
     * 年度收益率=年末边界权益/本年度基准-1
     */
    private BigDecimal yearReturn;
    /**
     * 区间自然日数
     */
    private Integer coverageDays;
    /**
     * 是否不完整年度(试运行)
     */
    private Boolean partialYear;
    /**
     * 年化折算(ACT/365,仅展示;不适用时为null)
     */
    private BigDecimal annualizedReturn;
    /**
     * 边界开放持仓批次数
     */
    private Integer openPositionCount;
    /**
     * 结算状态,取值见StockAnnualSettlementStatusEnum
     */
    private String settlementStatus;
    /**
     * 降级或阻断原因(可解释)
     */
    private String degradeReason;
    /**
     * 关联的年度报告通知审计行ID
     */
    private Long noticeId;
    /**
     * 年度报告通知状态快照
     */
    private String noticeStatus;
    /**
     * 结算口径版本
     */
    private String ruleVersion;
    /**
     * 最近一次结算尝试时点
     */
    private LocalDateTime lastAttemptAt;
}
