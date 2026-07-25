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
 * @version 1.2.12
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
     * 上次平仓类型(如TAKE_PROFIT/STOP_LOSS/TIMEOUT,用于冷却策略判断)
     */
    private String lastCloseType;
}
