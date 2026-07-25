package pn.torn.goldeneye.repository.model.torn.stocks.portfolio;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import pn.torn.goldeneye.repository.model.BaseDO;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Torn股票批次标记表
 * <p>
 * 在每轮决策中为虚拟批次生成的时间序列标记,记录该时刻的参考价、
 * 实时收益、峰谷价格、MFE/MAE/回撤以及正式与影子卖出决策,
 * 用于批次轨迹回放与卖出规则效果分析。
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.24
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName(value = "torn_stock_batch_mark", autoResultMap = true)
public class TornStockBatchMarkDO extends BaseDO {
    /**
     * 主键ID
     */
    private Long id;
    /**
     * 关联虚拟批次ID
     */
    private Long batchId;
    /**
     * 标记所属轮次时间
     */
    private LocalDateTime roundTime;
    /**
     * 本轮参考价(决策bar的收盘价)
     */
    private BigDecimal referencePrice;
    /**
     * 本轮实时净收益率
     */
    private BigDecimal currentNetReturn;
    /**
     * 截至本轮的持仓峰值价格
     */
    private BigDecimal peakPrice;
    /**
     * 截至本轮的持仓谷值价格
     */
    private BigDecimal troughPrice;
    /**
     * 截至本轮的最大有利偏移
     */
    private BigDecimal mfe;
    /**
     * 截至本轮的最大不利偏移
     */
    private BigDecimal mae;
    /**
     * 截至本轮的峰值回撤
     */
    private BigDecimal peakDrawdown;
    /**
     * 正式卖出决策(HOLD持有/SELL卖出,作用于正式批次)
     */
    private String formalDecision;
    /**
     * 正式决策原因(formalDecision的判定理由编码)
     */
    private String formalReason;
    /**
     * 影子动态卖出决策(影子规则给出的建议决策,用于对照评估)
     */
    private String dynamicShadowDecision;
    /**
     * 影子决策原因(dynamicShadowDecision的判定理由)
     */
    private String dynamicShadowReason;
    /**
     * 本轮决策特征快照(JSON文本,记录卖出规则引擎的全部输入)
     */
    private String featureSnapshot;
}
