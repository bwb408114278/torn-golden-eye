package pn.torn.goldeneye.napcat.strategy.user;

import org.springframework.stereotype.Component;
import pn.torn.goldeneye.constants.bot.BotCommands;

/**
 * 股票分红配置实现类
 *
 * @author Bai
 * @version 0.5.0
 * @since 2026.04.22
 */
@Component
public class StocksDividendBuildStrategyImpl extends BaseStocksDividendStrategy {
    @Override
    public String getCommand() {
        return BotCommands.STOCK_DIVIDEND_CALC;
    }

    @Override
    public String getCommandDescription() {
        return "计算分红股最高收益配置方式，g#" + BotCommands.STOCK_DIVIDEND_CALC + "#资金";
    }

    @Override
    protected boolean isBuyOnly() {
        return false;
    }

    @Override
    protected String isBestNowMsg() {
        return "当前购买策略已是最佳";
    }
}