package pn.torn.goldeneye.repository.model.torn.stocks.portfolio;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import pn.torn.goldeneye.repository.model.BaseDO;

import java.time.LocalDateTime;

/**
 * Torn股票信号状态表
 * <p>
 * 跟踪每只股票在特定策略下的买入信号状态机,包括条件是否激活、
 * 上次评估与触发时间、冷却期与复位标记,驱动信号去重与节奏控制。
 *
 * @author Bai
 * @version 1.2.14
 * @since 2026.07.24
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName(value = "torn_stock_signal_state", autoResultMap = true)
public class TornStockSignalStateDO extends BaseDO {
    /**
     * 主键ID
     */
    private Long id;
    /**
     * 股票ID
     */
    private Integer stocksId;
    /**
     * 策略类型(对应具体买入策略标识)
     */
    private String strategyType;
    /**
     * 买入规则版本
     */
    private String buyRuleVersion;
    /**
     * 买入条件当前是否激活(满足触发门槛)
     */
    private Boolean conditionActive;
    /**
     * 最近一次条件评估的轮次时间
     */
    private LocalDateTime lastEvaluatedRoundTime;
    /**
     * 最近一次实际产生信号的时间
     */
    private LocalDateTime lastSignalTime;
    /**
     * 冷却截止时间(此时间之前不允许再次产生同类信号)
     */
    private LocalDateTime cooldownUntil;
    /**
     * 是否观察到条件复位(信号触发后条件先失效再重新满足)
     */
    private Boolean resetObserved;
    /**
     * 最近关闭类型(如CLOSED_RISK/CLOSED_TARGET/ADMIN_CLOSED,用于冷却口径判断)
     */
    private String lastCloseType;

    /**
     * 应用一次策略评估结果,维护条件激活与边沿状态机。
     * <p>
     * 统一生产状态更新器与回放信号状态镜像的赋值口径: 命中由失效转激活时记录最近信号时间;
     * 由激活转失效时标记复位观察;无复位标记时补默认值。
     *
     * @param stocksId       股票ID
     * @param strategyType   策略类型
     * @param buyRuleVersion 买入规则版本
     * @param currentActive  本轮策略是否命中
     * @param roundTime      本轮评估时间
     */
    public void applyEvaluation(Integer stocksId, String strategyType, String buyRuleVersion,
                                boolean currentActive, LocalDateTime roundTime) {
        boolean previousActive = Boolean.TRUE.equals(this.conditionActive);
        this.stocksId = stocksId;
        this.strategyType = strategyType;
        this.buyRuleVersion = buyRuleVersion;
        this.conditionActive = currentActive;
        this.lastEvaluatedRoundTime = roundTime;
        if (currentActive && !previousActive) {
            this.lastSignalTime = roundTime;
        }
        if (!currentActive && previousActive) {
            this.resetObserved = true;
        }
        if (this.resetObserved == null) {
            this.resetObserved = false;
        }
    }
}
