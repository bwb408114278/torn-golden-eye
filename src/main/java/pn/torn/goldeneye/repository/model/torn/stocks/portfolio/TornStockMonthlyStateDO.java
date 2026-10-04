package pn.torn.goldeneye.repository.model.torn.stocks.portfolio;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import pn.torn.goldeneye.configuration.db.JsonbTypeHandler;
import pn.torn.goldeneye.repository.model.BaseDO;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Torn股票月度风格状态表
 * <p>
 * 每月按指标快照为股票评定策略契合度、成熟度、风险等级与建议人格,
 * 作为组合选股与仓位分配的风格依据。
 *
 * @author Bai
 * @version 1.8.0
 * @since 2026.10.04
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName(value = "torn_stock_monthly_state", autoResultMap = true)
public class TornStockMonthlyStateDO extends BaseDO {
    /**
     * 主键ID
     */
    private Long id;
    /**
     * 股票ID
     */
    private Integer stocksId;
    /**
     * 股票简称快照
     */
    private String stocksShortname;
    /**
     * 生效月份(当月1日,标识本条状态归属的自然月)
     */
    private LocalDate effectiveMonth;
    /**
     * 策略契合度分类(六类编码: DECLINER/WEAK/NARROW/RANGING/STEADY/STRONG;
     * 证据不完整或迟滞未就绪时为空,禁止默认STEADY)
     */
    private String strategyFitPrior;
    /**
     * 成熟度等级(M0_UNMATURE/M1_EARLY/M2_PROVISIONAL/M3_SEASONED/M4_MATURE,
     * 按证据自然日60/120/240/365分级)
     */
    private String maturity;
    /**
     * 风险等级(编码: NONE/MEDIUM/HIGH;证据不完整或迟滞未就绪时为空)
     */
    private String riskLevel;
    /**
     * 建议人格(系统根据指标推荐的操作风格标签)
     */
    private String suggestedPersonality;
    /**
     * 上月人格(用于对比风格切换,首月为空)
     */
    private String previousPersonality;
    /**
     * 是否人工覆盖(为true时以overrideReason为准,忽略系统建议)
     */
    private Boolean manualOverride;
    /**
     * 人工覆盖原因(manualOverride=true时必填)
     */
    private String overrideReason;
    /**
     * 分类时完整指标快照(JSON文本)
     * <p>
     * 包含真实计算字段而非空对象: rawPersonality/rawRiskLevel、suggestedPersonality、
     * annualizedDisplay、trend30/trend30Low/trend30High、secondHalfReturn、lastQuarterReturn、
     * fullBand、maxDrawdown、negativeMonthRatio/negativeMonthStreak、HIGH/MEDIUM投票明细、
     * usableBarCoverage、maxMissingBucketGap、rawUsableBarCoverage/rawMaxMissingBucketGap、
     * excludedBucketCount/excludedMinutes/appliedExclusionIds、evidenceDays、completeMonthCount、
     * quarterWindowTruncated、confirmable、hysteresisReason、incompleteReason。
     */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String metricSnapshot;
    /**
     * 人格分类规则版本
     */
    private String personalityRuleVersion;
    /**
     * 风险分级规则版本
     */
    private String riskRuleVersion;
    /**
     * 评定证据区间起始时间(指标采样的最早时点)
     */
    private LocalDateTime evidenceStartTime;
    /**
     * 评定证据区间结束时间(指标采样的最晚时点)
     */
    private LocalDateTime evidenceEndTime;
    /**
     * 状态(DRAFT草稿/CONFIRMED已确认/RETIRED已退役)
     */
    private String stateStatus;
    /**
     * 计算完成时间(规则引擎生成本条状态的时刻)
     */
    private LocalDateTime calculatedAt;
    /**
     * 确认时间(状态流转为CONFIRMED的时刻,草稿态为空)
     */
    private LocalDateTime confirmedAt;
    /**
     * 确认人(本期仅SYSTEM自动确认,人工确认入口不取回)
     */
    private String confirmedBy;
}
