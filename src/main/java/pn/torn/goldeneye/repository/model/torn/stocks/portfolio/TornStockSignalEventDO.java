package pn.torn.goldeneye.repository.model.torn.stocks.portfolio;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import pn.torn.goldeneye.repository.model.BaseDO;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Torn股票信号事件表
 * <p>
 * 记录每一次买入信号事件的完整生命周期,包括触发时的特征与风格快照、
 * 资格审查结果、组合决策、后续最大有利/不利偏移(MFE/MAE)等,
 * 作为信号回测与策略迭代的核心数据。
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.24
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName(value = "torn_stock_signal_event", autoResultMap = true)
public class TornStockSignalEventDO extends BaseDO {
    /**
     * 主键ID
     */
    private Long id;
    /**
     * 事件编号(业务唯一编号,便于跨表引用与展示)
     */
    private String eventNo;
    /**
     * 信号产生的轮次时间
     */
    private LocalDateTime roundTime;
    /**
     * 股票ID
     */
    private Integer stocksId;
    /**
     * 股票简称快照
     */
    private String stocksShortname;
    /**
     * 策略类型
     */
    private String strategyType;
    /**
     * 买入规则版本
     */
    private String buyRuleVersion;
    /**
     * 信号质量评分(综合特征健康度与信号强度,用于候选排序)
     */
    private BigDecimal qualityScore;
    /**
     * 信号触发时的特征快照(JSON文本,包含全部输入特征值)
     */
    private String featureSnapshot;
    /**
     * 信号触发时的风格快照(JSON文本,包含人格/成熟度/风险等级等)
     */
    private String styleSnapshot;
    /**
     * 资格审查结果(PASS/FAIL)
     */
    private String eligibilityResult;
    /**
     * 资格审查原因明细(JSON文本数组,记录每项检查的通过与失败理由)
     */
    private String eligibilityReasons;
    /**
     * 候选排名(通过资格审查后的质量分排名,未通过时为空)
     */
    private Integer candidateRank;
    /**
     * 组合决策(如SELECTED入选/REJECTED淘汰/WAITLIST候补)
     */
    private String portfolioDecision;
    /**
     * 淘汰原因(portfolioDecision为REJECTED时记录的淘汰理由)
     */
    private String rejectReason;
    /**
     * 转正式批次ID(入选后关联的正式虚拟批次ID)
     */
    private Long formalBatchId;
    /**
     * 影子批次ID(未入选但进入影子跟踪的虚拟批次ID)
     */
    private Long shadowBatchId;
    /**
     * 后续观察期内的最大有利偏移(MFE,入场参考价到最高价涨幅)
     */
    private BigDecimal laterMfe;
    /**
     * 后续观察期内的最大不利偏移(MAE,入场参考价到最低价跌幅)
     */
    private BigDecimal laterMae;
    /**
     * 事件结算时间(MFE/MAE观察窗口结束或批次平仓的时刻)
     */
    private LocalDateTime resolvedAt;
}
