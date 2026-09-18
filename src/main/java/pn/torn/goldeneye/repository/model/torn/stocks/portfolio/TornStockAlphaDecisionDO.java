package pn.torn.goldeneye.repository.model.torn.stocks.portfolio;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import pn.torn.goldeneye.repository.model.BaseDO;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * α策略日线决策与phase消费记录。
 *
 * @author Bai
 * @version 1.6.5
 * @since 2026.09.05
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName(value = "torn_stock_alpha_decision", autoResultMap = true)
public class TornStockAlphaDecisionDO extends BaseDO {
    /**
     * 主键。
     */
    private Long id;
    /**
     * α相位轨道编码。
     * <p>
     * 多槽相位分散下"同一业务日同一phase"可同时存在于不同轨道,本字段是决策唯一键
     * {@code (phase_track_code, decision_business_date, phase)} 的第一分量;
     * 历史行由数据库默认值补齐为正式轨道{@code VIP_ALPHA#1}。
     */
    private String phaseTrackCode;
    /**
     * 决策业务日期。
     */
    private LocalDate decisionBusinessDate;
    /**
     * 共同有效日序号。
     */
    private Integer commonDayIndex;
    /**
     * 消费阶段。
     */
    private Integer phase;
    /**
     * 决策类型。
     */
    private String decisionType;
    /**
     * 当前持仓批次ID。
     */
    private Long currentBatchId;
    /**
     * 目标股票ID。
     */
    private Integer selectedStocksId;
    /**
     * 来源快照摘要。
     */
    private String sourceSnapshotDigest;
    /**
     * 决策时点参考价。
     * <p>
     * 取决策桶(执行桶前一根15分钟bar)的最后价,是α批次信号参考价的唯一来源:
     * 执行阶段不得再用执行bar价格充当信号参考价,否则入场价格偏离保护恒为零。
     */
    private BigDecimal signalReferencePrice;
    /**
     * 执行bar开始时间。
     * <p>
     * 由{@code 决策时点向下对齐15分钟 + 15分钟}计算后持久化,执行阶段只消费本字段,
     * 不得再由轮次时间反推,也不跨桶追补。
     */
    private LocalDateTime executionBarStartTime;
    /**
     * 目标变化决策时点所在的15分钟决策桶起点。
     * <p>
     * 与执行bar(executionBarStartTime)分离保存,用于审计区分决策事实与执行事实:
     * 批次来源bar取本字段,批次执行bar取executionBarStartTime,两者不得互相冒充。
     * 与执行桶一样仅在首次落决策时冻结,冲突路径不改写。
     */
    private LocalDateTime decisionBarStartTime;
    /**
     * 执行状态。
     */
    private String executionStatus;
    /**
     * 失败原因。
     */
    private String failureReason;
    /**
     * 换仓批次ID。
     */
    private Long rebalanceBatchId;
    /**
     * 观察口径参考价(决策桶现价)。
     * <p>
     * 只写不读:与{@code signalReferencePrice}同源但来自{@code LATEST_PRICE}观察口径,
     * 仅用于业务研究,生产目标、下单、通知与结算一律不消费本字段。
     */
    private BigDecimal altSignalReferencePrice;
    /**
     * 观察口径目标股票ID。
     * <p>
     * 只写不读:生产目标始终取{@code selectedStocksId},禁止任何生产代码读取本字段。
     */
    private Integer altSelectedStocksId;
    /**
     * 观察口径来源摘要。
     * <p>
     * 可按键序与观察口径排名重算复核,用于区分观察事实与生产事实。
     */
    private String altSourceSnapshotDigest;
}
