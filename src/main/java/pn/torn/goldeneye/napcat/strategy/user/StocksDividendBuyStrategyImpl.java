package pn.torn.goldeneye.napcat.strategy.user;

import org.springframework.stereotype.Component;
import pn.torn.goldeneye.constants.bot.BotCommands;

/**
 * 股票分红配置实现类
 *
 * @author Bai
 * @version 1.9.3
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
        return "分红收益最优的Stock纯买版，g#" + BotCommands.STOCK_DIVIDEND_BUY + "#资金\n" +
                "屏蔽股票: g#" + BotCommands.STOCK_DIVIDEND_BUY + "#屏蔽#股票简称 (多个用英文逗号分隔)";
    }

    @Override
    protected boolean isBuyOnly() {
        return true;
    }

    @Override
    protected String isBestNowMsg() {
        return "资金不足以购买能获得分红的Stock";
    }
}