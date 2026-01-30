package pn.torn.goldeneye.napcat.strategy.user;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.base.torn.TornApi;
import pn.torn.goldeneye.configuration.TornApiKeyConfig;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.napcat.receive.msg.QqRecMsgSender;
import pn.torn.goldeneye.napcat.send.msg.param.QqMsgParam;
import pn.torn.goldeneye.napcat.send.msg.param.TextQqMsg;
import pn.torn.goldeneye.napcat.strategy.base.SmthMsgStrategy;
import pn.torn.goldeneye.repository.dao.torn.TornStocksDAO;
import pn.torn.goldeneye.repository.model.setting.TornApiKeyDO;
import pn.torn.goldeneye.repository.model.torn.TornStocksDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.manager.user.StocksDividendOptimizerManager;
import pn.torn.goldeneye.torn.model.user.stocks.TornUserStocksDTO;
import pn.torn.goldeneye.torn.model.user.stocks.TornUserStocksVO;
import pn.torn.goldeneye.utils.NumberUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 股票分红计算实现类
 *
 * @author Bai
 * @version 0.4.0
 * @since 2025.09.27
 */
@Component
@RequiredArgsConstructor
public class TornStocksDividendStrategyImpl extends SmthMsgStrategy {
    private final TornApiKeyConfig apiKeyConfig;
    private final TornApi tornApi;
    private final StocksDividendOptimizerManager stocksDividendOptimizerManager;
    private final TornStocksDAO stocksDao;

    @Override
    public String getCommand() {
        return BotCommands.STOCK_DIVIDEND_CALC;
    }

    @Override
    public String getCommandDescription() {
        return "计算分红股最高收益购买方式，g#" + BotCommands.STOCK_DIVIDEND_CALC + "#资金";
    }

    @Override
    public List<? extends QqMsgParam<?>> handle(long groupId, QqRecMsgSender sender, String msg) {
        Long money = NumberUtils.convert(msg);
        if (money == null) {
            return super.sendErrorFormatMsg();
        }

        TornUserDO user = super.getTornUser(sender, "");
        TornApiKeyDO key = apiKeyConfig.getKeyByUserId(user.getId());
        if (key == null) {
            return super.buildTextMsg("这个人还没有绑定Key哦");
        }

        if (money > 100_000_000_000L) {
            apiKeyConfig.returnKey(key);
            return super.buildTextMsg(user.getNickname() + ", 这么多钱咱吃点好的吧");
        }

        TornUserStocksVO userStocks = tornApi.sendRequest(new TornUserStocksDTO(), key, TornUserStocksVO.class);
        List<TornStocksDO> stocksList = stocksDao.lambdaQuery().gt(TornStocksDO::getProfit, 0).list();
        List<StocksDividendOptimizerManager.OptimalAction> result = stocksDividendOptimizerManager
                .calculate(money, stocksList, userStocks);
        if (CollectionUtils.isEmpty(result)) {
            return super.buildTextMsg(user.getNickname() + ", 当前购买策略已是最佳");
        }

        List<TextQqMsg> msgList = new ArrayList<>();
        msgList.add(new TextQqMsg(user.getNickname() + ", 推荐以下操作配置分红股: \n"));
        result.forEach(r -> msgList.add(new TextQqMsg(r.toString() + "\n")));
        return msgList;
    }
}