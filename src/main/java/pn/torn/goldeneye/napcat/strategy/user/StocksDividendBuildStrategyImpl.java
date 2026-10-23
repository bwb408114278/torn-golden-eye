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
public class StocksDividendBuildStrategyImpl extends BaseStocksDividendStrategy {
    @Override
    public String getCommand() {
        return BotCommands.STOCK_DIVIDEND_CALC;
    }

    @Override
    public String getCommandDescription() {
        return "计算分红收益最优的Stock配置方式，g#" + BotCommands.STOCK_DIVIDEND_CALC + "#资金, " +
                "g#" + BotCommands.STOCK_DIVIDEND_CALC + "#屏蔽#股票简称 屏蔽股票(多个用英文逗号分隔)";
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