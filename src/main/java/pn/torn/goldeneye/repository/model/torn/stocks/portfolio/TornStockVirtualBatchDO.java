package pn.torn.goldeneye.repository.model.torn.stocks.portfolio;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import pn.torn.goldeneye.configuration.db.JsonbTypeHandler;
import pn.torn.goldeneye.repository.model.BaseDO;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Torn股票虚拟交易批次表
 * <p>
 * 一次完整的虚拟交易生命周期记录:从信号入场、持仓跟踪、动态卖出决策
 * 到最终平仓结算。涵盖入场/出场参考价、持仓数量、投入与剩余资金、
 * 峰谷价格、MFE/MAE、动态卖出状态机以及平仓收益等全量字段。
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.24
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName(value = "torn_stock_virtual_batch", autoResultMap = true)
public class TornStockVirtualBatchDO extends BaseDO {
    /**
     * 主键ID
     */
    private Long id;
    /**
     * 批次编号(业务唯一编号,便于展示与跨表引用)
     */
    private String batchNo;
    /**
     * 账本类型(FORMAL正式/SHADOW影子,影子批次仅跟踪不实际分配资金)
     */
    private String ledgerType;
    /**
     * 股票ID
     */
    private Integer stocksId;
    /**
     * 股票简称快照
     */
    private String stocksShortname;
    /**
     * 主策略类型(批次采用的核心买入策略)
     */
    private String primaryStrategy;
    /**
     * 匹配的策略列表(JSON文本,记录同时满足条件的全部策略标识)
     */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String matchedStrategies;
    /**
     * 信号质量评分(来自关联信号事件)
     */
    private BigDecimal qualityScore;
    /**
     * 批次状态(SIGNAL_PENDING/ENTRY_PENDING/HOLDING/EXIT_PENDING/CLOSED/CANCELLED)
     */
    private String batchStatus;
    /**
     * 关联信号事件ID
     */
    private Long signalEventId;
    /**
     * 占用仓位ID(正式批次分配的仓位ID,影子批次为空)
     */
    private Long slotId;
    /**
     * 占用仓位序号
     */
    private Integer slotNo;
    /**
     * 信号触发时间(关联信号事件的轮次时间)
     */
    private LocalDateTime signalTime;
    /**
     * 信号参考价(信号触发时bar的收盘价,作为入场基准)
     */
    private BigDecimal signalReferencePrice;
    /**
     * 预期入场bar时间(计划执行的bar时间窗口)
     */
    private LocalDateTime expectedEntryBarTime;
    /**
     * 入场超时时间(超过此时间未成功入场则取消批次)
     */
    private LocalDateTime entryStaleAt;
    /**
     * 实际入场时间
     */
    private LocalDateTime entryTime;
    /**
     * 实际入场参考价(入场bar的收盘价)
     */
    private BigDecimal entryReferencePrice;
    /**
     * 持仓数量(买入的股数)
     */
    private Long quantity;
    /**
     * 投入资金(入场参考价×数量)
     */
    private BigDecimal investedCash;
    /**
     * 剩余资金(仓位扣除本次投入后的余额快照)
     */
    private BigDecimal remainingCash;
    /**
     * 风格-策略契合度(来自月度状态)
     */
    private String stylePrior;
    /**
     * 风格-成熟度等级
     */
    private String styleMaturity;
    /**
     * 风格-风险等级
     */
    private String riskLevel;
    /**
     * 风格生效月份(批次入场时采用的月度状态生效月)
     */
    private LocalDate styleEffectiveMonth;
    /**
     * 买入规则版本
     */
    private String buyRuleVersion;
    /**
     * 卖出规则版本
     */
    private String sellRuleVersion;
    /**
     * 风格分类规则版本
     */
    private String styleRuleVersion;
    /**
     * 风险分级规则版本
     */
    private String riskRuleVersion;
    /**
     * 仓位分配规则版本
     */
    private String allocationRuleVersion;
    /**
     * 消息通知规则版本
     */
    private String messageRuleVersion;
    /**
     * 跟踪截止时间(超过此时间强制触发平仓评估)
     */
    private LocalDateTime followUntil;
    /**
     * 跟踪期最高限价(动态卖出规则设定的价格上限警戒)
     */
    private BigDecimal followMaxPrice;
    /**
     * 持仓期间峰值价格(持仓期内的最高价)
     */
    private BigDecimal peakPrice;
    /**
     * 持仓期间谷值价格(持仓期内的最低价)
     */
    private BigDecimal troughPrice;
    /**
     * 当前净收益率(基于最新参考价的实时收益率)
     */
    private BigDecimal currentNetReturn;
    /**
     * 最大有利偏移(持仓期内最大正向收益)
     */
    private BigDecimal mfe;
    /**
     * 最大不利偏移(持仓期内最大负向亏损)
     */
    private BigDecimal mae;
    /**
     * 峰值回撤(从peakPrice到后续最低价的跌幅)
     */
    private BigDecimal peakDrawdown;
    /**
     * 动态卖出状态机当前状态(如TRAILING/HOLDING/TIGHTENED)
     */
    private String dynamicSellState;
    /**
     * 最大持仓截止时间(硬性平仓时间上限)
     */
    private LocalDateTime maxHoldUntil;
    /**
     * 平仓信号触发时间
     */
    private LocalDateTime exitSignalTime;
    /**
     * 预期平仓bar时间
     */
    private LocalDateTime expectedExitBarTime;
    /**
     * 实际平仓时间
     */
    private LocalDateTime exitTime;
    /**
     * 平仓参考价(平仓bar的收盘价)
     */
    private BigDecimal exitReferencePrice;
    /**
     * 平仓原因(如TAKE_PROFIT/STOP_LOSS/TIMEOUT/SIGNAL_RESET)
     */
    private String exitReason;
    /**
     * 最终净收益率(平仓参考价相对入场参考价的收益率)
     */
    private BigDecimal netReturn;
    /**
     * 卖出回笼资金(平仓参考价×数量)
     */
    private BigDecimal sellProceeds;
    /**
     * 平仓后冷却截止时间(此期间不再对该股产生同类信号)
     */
    private LocalDateTime cooldownUntil;
    /**
     * 是否观察到信号复位(持仓期间买入条件先失效再满足)
     */
    private Boolean resetObserved;
    /**
     * 取消原因(批次未入场即取消时记录的原因)
     */
    private String cancelReason;
}
