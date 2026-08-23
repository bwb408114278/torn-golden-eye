package pn.torn.goldeneye.torn.service.stocks.alert.summary;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 动态SELL研究遥测常量 - 生产写路径与日报读路径共用的冻结值
 * <p>
 * 动态SELL规则真正冻结前,所有符合研究范围的正式/候选影子批次mark在落库时必须显式写入
 * {@link #DECISION_NOT_EVALUATED} 与 {@link #REASON_RULE_NOT_FROZEN},作为"规则未冻结、
 * 建议未启用"的研究遥测;该值仅用于日报覆盖统计,不得据此触发卖出、资金、槽位、通知或
 * 状态迁移。本类集中定义,禁止在多个服务中散落字符串。
 *
 * @author Bai
 * @version 1.2.14
 * @since 2026.08.09
 */
@NoArgsConstructor(access = AccessLevel.NONE)
public final class StockDynamicSellResearchConstants {

    /**
     * 动态SELL研究决策冻结值: 未评估(公式冻结前固定)
     */
    public static final String DECISION_NOT_EVALUATED = "NOT_EVALUATED";
    /**
     * 动态SELL研究原因冻结值: 动态规则未冻结
     */
    public static final String REASON_RULE_NOT_FROZEN = "DYNAMIC_RULE_NOT_FROZEN";
}
