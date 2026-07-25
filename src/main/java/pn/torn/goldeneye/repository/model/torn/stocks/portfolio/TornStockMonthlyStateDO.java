package pn.torn.goldeneye.repository.model.torn.stocks.portfolio;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
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
 * @version 1.2.12
 * @since 2026.07.24
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
     * 策略契合度分类(如趋势型/震荡型/红利型)
     */
    private String strategyFitPrior;
    /**
     * 成熟度等级(反映股票数据与行为的稳定程度)
     */
    private String maturity;
    /**
     * 风险等级(如LOW/MEDIUM/HIGH)
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
     * 分类时完整指标快照(JSON文本,包含用于评定的全部输入特征)
     */
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
     * 状态(DRAFT草稿/CONFIRMED已确认/ARCHIVED已归档)
     */
    private String stateStatus;
    /**
     * 计算完成时间(规则引擎生成本条状态的时刻)
     */
    private LocalDateTime calculatedAt;
    /**
     * 确认时间(人工或流程确认状态的时刻,草稿态为空)
     */
    private LocalDateTime confirmedAt;
    /**
     * 确认人(执行确认操作的账号标识)
     */
    private String confirmedBy;
}
