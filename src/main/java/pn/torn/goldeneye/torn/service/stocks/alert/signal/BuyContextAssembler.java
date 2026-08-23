package pn.torn.goldeneye.torn.service.stocks.alert.signal;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockMaturityEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockRiskLevelEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockStrategyFitEnum;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMonthlyStateDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockStrategyFeature15mDO;

/**
 * 买入上下文组装器 - 从策略特征与月度状态组装 {@link BuyContext}。
 * <p>
 * 纯规则组件,无DAO、无事务、无写操作。风格缺失或解析失败时返回 null,
 * 由调用方据此跳过该股票的信号评估。
 *
 * @author Bai
 * @version 1.2.14
 * @since 2026.08.09
 */
@Slf4j
@Component
public class BuyContextAssembler {

    /**
     * 从特征组装 {@link BuyContext}。
     *
     * @param feature      策略特征
     * @param monthlyState 月度状态
     * @return BuyContext;风格缺失时返回 null
     */
    public BuyContext assemble(TornStockStrategyFeature15mDO feature,
                               TornStockMonthlyStateDO monthlyState) {
        StockStrategyFitEnumWrapper styleWrapper = parseStyle(monthlyState);
        if (styleWrapper == null) {
            return null;
        }
        return new BuyContext(
                feature.getStocksId(),
                feature.getStocksShortname(),
                feature.getReferencePrice(),
                feature.getMa1d(),
                feature.getMa7d(),
                feature.getMa30d(),
                feature.getZscore1d(),
                feature.getZscore7d(),
                feature.getZscore30d(),
                feature.getReturn6h(),
                feature.getReturn1d(),
                feature.getReturn7d(),
                feature.getReturn14d(),
                feature.getLow30d(),
                feature.getHigh30d(),
                feature.getWidth30d(),
                feature.getPosition30(),
                feature.getPctAbove30dLow(),
                feature.getPctBelow30dHigh(),
                feature.getStrategyReady(),
                styleWrapper.style(),
                styleWrapper.maturity(),
                styleWrapper.riskLevel()
        );
    }

    /**
     * 从月度状态解析风格、成熟度、风险等级。
     *
     * @param monthlyState 月度状态
     * @return 风格包装对象;风格为空或解析失败时返回 null
     */
    private StockStrategyFitEnumWrapper parseStyle(TornStockMonthlyStateDO monthlyState) {
        if (monthlyState == null) {
            return null;
        }
        if (monthlyState.getStrategyFitPrior() == null || monthlyState.getStrategyFitPrior().isBlank()) {
            return null;
        }
        try {
            StockStrategyFitEnum style = StockStrategyFitEnum.fromCode(monthlyState.getStrategyFitPrior());
            StockMaturityEnum maturity = monthlyState.getMaturity() != null
                    ? StockMaturityEnum.fromCode(monthlyState.getMaturity()) : null;
            StockRiskLevelEnum riskLevel = monthlyState.getRiskLevel() != null
                    ? StockRiskLevelEnum.fromCode(monthlyState.getRiskLevel()) : null;
            return new StockStrategyFitEnumWrapper(style, maturity, riskLevel);
        } catch (IllegalArgumentException e) {
            log.warn("月度状态风格解析失败: strategyFitPrior={}, error={}",
                    monthlyState.getStrategyFitPrior(), e.getMessage());
            return null;
        }
    }

    /**
     * 风格枚举包装器 - 封装从月度状态解析出的风格、成熟度、风险等级。
     *
     * @param style     策略适配风格
     * @param maturity  成熟度
     * @param riskLevel 风险等级
     */
    private record StockStrategyFitEnumWrapper(
            StockStrategyFitEnum style,
            StockMaturityEnum maturity,
            StockRiskLevelEnum riskLevel
    ) {
    }
}
