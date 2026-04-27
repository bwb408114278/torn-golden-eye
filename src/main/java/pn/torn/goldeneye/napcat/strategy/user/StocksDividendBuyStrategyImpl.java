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
public class StocksDividendBuyStrategyImpl extends BaseStocksDividendStrategy {
    @Override
    public String getCommand() {
        return BotCommands.STOCK_DIVIDEND_BUY;
    }

    @Override
    public String getCommandDescription() {
        return "分红股最高收益纯买版，g#" + BotCommands.STOCK_DIVIDEND_BUY + "#资金";
    }

    @Override
    protected boolean isBuyOnly() {
        return true;
    }

    @Override
    protected String isBestNowMsg() {
        return "资金不足以购买能获得分红的股票";
    }
}