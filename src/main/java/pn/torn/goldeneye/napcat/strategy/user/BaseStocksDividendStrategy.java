package pn.torn.goldeneye.napcat.strategy.user;

import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.base.torn.TornApi;
import pn.torn.goldeneye.configuration.TornApiKeyConfig;
import pn.torn.goldeneye.napcat.receive.msg.QqRecMsgSender;
import pn.torn.goldeneye.napcat.send.msg.param.QqMsgParam;
import pn.torn.goldeneye.napcat.send.msg.param.TextQqMsg;
import pn.torn.goldeneye.napcat.strategy.base.SmthMsgStrategy;
import pn.torn.goldeneye.repository.dao.torn.TornStocksDAO;
import pn.torn.goldeneye.repository.model.setting.TornApiKeyDO;
import pn.torn.goldeneye.repository.model.torn.TornStocksDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.manager.user.StocksBonusAnalyzeManager;
import pn.torn.goldeneye.torn.model.user.stocks.TornUserStocksDTO;
import pn.torn.goldeneye.torn.model.user.stocks.TornUserStocksVO;
import pn.torn.goldeneye.utils.NumberUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 股票分红计算基类
 *
 * @author Bai
 * @version 1.0.0
 * @since 2025.09.27
 */
@Component
public abstract class BaseStocksDividendStrategy extends SmthMsgStrategy {
    @Resource
    private TornApiKeyConfig apiKeyConfig;
    @Resource
    private TornApi tornApi;
    @Resource
    private StocksBonusAnalyzeManager stocksBonusAnalyzeManager;
    @Resource
    private TornStocksDAO stocksDao;

    @Override
    public List<? extends QqMsgParam<?>> handle(long groupId, QqRecMsgSender sender, String msg) {
        Long money = NumberUtils.convert(msg);
        if (money == null) {
            return super.buildTextMsg("请输入计算的金额, 例如有2b用于购买分红股, 则输入g#" + getCommand() + "#2b, 上限为100b");
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
        List<StocksBonusAnalyzeManager.OptimalAction> result = stocksBonusAnalyzeManager
                .calculate(money, stocksList, userStocks, isBuyOnly());
        if (CollectionUtils.isEmpty(result)) {
            return super.buildTextMsg(user.getNickname() + ", " + isBestNowMsg());
        }

        List<TextQqMsg> msgList = new ArrayList<>();
        msgList.add(new TextQqMsg(user.getNickname() + ", 推荐以下操作配置分红股: \n"));
        result.forEach(r -> msgList.add(new TextQqMsg(r.toString() + "\n")));
        return msgList;
    }

    /**
     * 是否只买不卖
     *
     * @return true为只买不卖
     */
    protected abstract boolean isBuyOnly();

    /**
     * 当前已是最佳的提示消息
     *
     * @return 消息内容
     */
    protected abstract String isBestNowMsg();
}