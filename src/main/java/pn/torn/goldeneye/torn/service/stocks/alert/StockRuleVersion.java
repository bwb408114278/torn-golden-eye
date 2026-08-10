package pn.torn.goldeneye.torn.service.stocks.alert;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 股票策略规则版本常量 - 轮次、批次与组合决策使用的统一冻结规则版本
 * <p>
 * 全部规则版本在此集中定义,禁止在多个服务中散落字符串常量。
 * 动态SELL规则真正冻结前,{@link #BUY}/{@link #SELL} 等版本仅表示当前冻结规则,
 * 不表示未冻结动态规则的启用。
 *
 * @author Bai
 * @version 1.2.14
 * @since 2026.08.09
 */
@NoArgsConstructor(access = AccessLevel.NONE)
public final class StockRuleVersion {

    /**
     * 买入规则版本(RANGE绝对趋势保护自1.1.0起生效,历史批次保留原版本)
     */
    public static final String BUY = "1.1.0";
    /**
     * 卖出规则版本
     */
    public static final String SELL = "1.0.0";
    /**
     * 仓位分配规则版本
     */
    public static final String ALLOCATION = "1.0.0";
    /**
     * 消息通知规则版本
     */
    public static final String MESSAGE = "1.0.0";
    /**
     * 风格分类规则版本
     */
    public static final String STYLE = "1.0.0";
    /**
     * 风险分级规则版本
     */
    public static final String RISK = "1.0.0";
}
