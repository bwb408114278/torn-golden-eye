package pn.torn.goldeneye.torn.service.stocks.alert.buy;

import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockBuyStrategyEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockStrategyFitEnum;

import java.math.BigDecimal;

/**
 * 股票正式买入策略接口，定义策略类型识别、风格适配校验、买入条件匹配与质量分计算。
 * <p>
 * 每个实现类对应 {@link StockBuyStrategyEnum} 中的一个策略编码，负责该策略的全部
 * 触发条件判断与质量分计算逻辑。策略实现应为无状态纯函数式组件，仅依赖传入的
 * {@link BuyContext} 进行判断，不持有可变状态。
 * <p>
 * 调用方负责按以下顺序使用策略：
 * <ol>
 *   <li>{@link #isApplicableStyle(StockStrategyFitEnum)} 校验风格是否适配</li>
 *   <li>{@link #matches(BuyContext)} 判断买入条件是否满足</li>
 *   <li>{@link #calculateQualityScore(BuyContext)} 计算质量分用于候选排序</li>
 * </ol>
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.24
 */
public interface StockBuyStrategy {

    /**
     * 获取该策略实现对应的策略编码。
     *
     * @return 策略编码枚举
     */
    StockBuyStrategyEnum getStrategyType();

    /**
     * 判断给定上下文是否满足该策略的全部买入触发条件。
     * <p>
     * 调用前应先通过 {@link #isApplicableStyle(StockStrategyFitEnum)} 确认风格适配，
     * 风格不适配时本方法应返回false。
     *
     * @param context 买入评估上下文，包含特征与月度状态
     * @return 满足全部买入条件时返回true，否则返回false
     */
    boolean matches(BuyContext context);

    /**
     * 计算该策略在给定上下文下的质量分，用于多策略命中时选取主策略与跨股票候选排序。
     * <p>
     * 质量分越高表示信号质量越好。计算应使用BigDecimal，精度18位，RoundingMode.HALF_UP。
     *
     * @param context 买入评估上下文
     * @return 质量分，值域由具体策略定义
     */
    BigDecimal calculateQualityScore(BuyContext context);

    /**
     * 判断该策略是否适用于给定的策略适配风格。
     * <p>
     * 风格不适配时策略不应产生买入信号。
     *
     * @param style 股票的月度策略适配风格
     * @return 风格适配时返回true，否则返回false
     */
    boolean isApplicableStyle(StockStrategyFitEnum style);
}
