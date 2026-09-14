package pn.torn.goldeneye.torn.service.stocks.alert.alpha.config;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * α策略固定规则定义。
 *
 * @author Bai
 * @version 1.6.1
 * @since 2026.09.05
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class StockAlphaRuleDefinition {
    /**
     * α主策略编码: 批次primaryStrategy与通知解析识别α身份的唯一取值,不得加入旧版StockBuyStrategyEnum。
     */
    public static final String PRIMARY_STRATEGY = "ALPHA";
    /**
     * α卖出规则版本: α批次只按目标变化换仓退出,不受旧版固定SELL接管。
     */
    public static final String SELL_RULE_VERSION = "ALPHA_REBALANCE_ONLY";
    /**
     * α仓位分配规则版本: α组合单槽100%投入,不参与旧版五槽分配。
     */
    public static final String ALLOCATION_RULE_VERSION = "ALPHA_100_PERCENT";
    /**
     * α消息规则版本: α批次及其通知审计共用的消息规则版本。
     */
    public static final String MESSAGE_RULE_VERSION = "ALPHA_V1";
    /**
     * α换仓退出原因编码: 既是Alpha换仓关联类型,也是策略退出原因。
     */
    public static final String EXIT_REASON_REBALANCE = "ALPHA_REBALANCE";
    /**
     * α规则版本。
     */
    public static final String RULE_VERSION = "ALPHA_0.04_V1";
    /**
     * 股票池版本。
     */
    public static final String STOCK_UNIVERSE_VERSION = "STOCKS_35_V1";
    /**
     * α批次不参与旧版风格评估时写入的风格规则版本。
     */
    public static final String STYLE_RULE_VERSION = "ALPHA_STYLE_NOT_EVALUATED";
    /**
     * α批次不参与旧版风险评级时写入的风险规则版本。
     */
    public static final String RISK_RULE_VERSION = "ALPHA_RISK_NOT_EVALUATED";
    /**
     * 股票池成员数量。
     */
    public static final int MEMBER_COUNT = 35;
    /**
     * 预热所需共同有效日数量。
     */
    public static final int WARMUP_COMMON_DAYS = 60;
    /**
     * 决策间隔天数。
     */
    public static final int DECISION_INTERVAL_DAYS = 5;
    /**
     * 计算α排名所需的最少共同有效日数量:20日收益需要前20个交易日加当前日。
     */
    public static final int RANKING_MIN_COMMON_DAYS = 21;
    /**
     * 持仓保持的最高排名范围。
     */
    public static final int HYSTERESIS_TOP = 3;
    /**
     * 20日收益权重。
     */
    public static final BigDecimal R20_WEIGHT = new BigDecimal("0.96");
    /**
     * 1日收益权重。
     */
    public static final BigDecimal R1_WEIGHT = new BigDecimal("0.04");
    /**
     * 收益和排名计算精度。
     */
    public static final int CALC_SCALE = 18;
    /**
     * 股票池成员摘要。
     */
    public static final String STOCK_UNIVERSE_DIGEST =
            "0620dc9876a92272bf8358b21731a40ad31b3556aeb41a7e6f798c8a2f4c041a";
    /**
     * 股票池生效时间。
     */
    public static final LocalDateTime STOCK_UNIVERSE_EFFECTIVE_AT =
            LocalDateTime.of(2026, 9, 5, 0, 0);
    private static final List<Integer> STOCK_UNIVERSE = List.of(
            1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18,
            19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35);


    /**
     * 返回固定升序股票成员。
     *
     * @return 35支股票ID
     */
    public static List<Integer> stockUniverse() {
        return STOCK_UNIVERSE;
    }
}
