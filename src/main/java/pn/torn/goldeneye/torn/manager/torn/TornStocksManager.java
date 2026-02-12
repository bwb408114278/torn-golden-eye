package pn.torn.goldeneye.torn.manager.torn;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.base.bot.Bot;
import pn.torn.goldeneye.base.bot.BotHttpReqParam;
import pn.torn.goldeneye.base.torn.TornApi;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.constants.bot.BotConstants;
import pn.torn.goldeneye.constants.torn.SettingConstants;
import pn.torn.goldeneye.napcat.send.msg.GroupMsgHttpBuilder;
import pn.torn.goldeneye.napcat.send.msg.param.QqMsgParam;
import pn.torn.goldeneye.napcat.send.msg.param.TextQqMsg;
import pn.torn.goldeneye.repository.dao.torn.TornStocksDAO;
import pn.torn.goldeneye.repository.dao.torn.TornStocksHistoryDAO;
import pn.torn.goldeneye.repository.model.torn.StocksChangeDO;
import pn.torn.goldeneye.repository.model.torn.TornItemsDO;
import pn.torn.goldeneye.repository.model.torn.TornStocksDO;
import pn.torn.goldeneye.repository.model.torn.TornStocksHistoryDO;
import pn.torn.goldeneye.torn.manager.setting.SysSettingManager;
import pn.torn.goldeneye.torn.model.torn.stocks.TornStocksBenefitVO;
import pn.torn.goldeneye.torn.model.torn.stocks.TornStocksDTO;
import pn.torn.goldeneye.torn.model.torn.stocks.TornStocksDetailVO;
import pn.torn.goldeneye.torn.model.torn.stocks.TornStocksVO;
import pn.torn.goldeneye.utils.NumberUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Torn股票公共逻辑层
 *
 * @author Bai
 * @version 0.5.0
 * @since 2025.09.26
 */
@Component
@RequiredArgsConstructor
public class TornStocksManager {
    private final Bot bot;
    private final TornApi tornApi;
    private final SysSettingManager settingManager;
    private final TornItemsManager itemsManager;
    private final TornStocksDAO stocksDao;
    private final TornStocksHistoryDAO stocksHistoryDao;
    private final ProjectProperty projectProperty;
    private static final Long NOTICE_THRESHOLD = 100_000_000_000L;

    private static final Pattern CURRENCY_PATTERN = Pattern.compile("\\$(\\d{1,3}(?:,\\d{3})*)");
    private static final Pattern ITEM_PATTERN = Pattern.compile("1x (.+)");

    @Scheduled(cron = "0 */5 * * * ?")
    public void spiderStockData() {
        if (!BotConstants.ENV_PROD.equals(projectProperty.getEnv())) {
            return;
        }

        TornStocksVO resp = tornApi.sendRequest(new TornStocksDTO(), TornStocksVO.class);
        List<TornStocksDO> stocksList = resp.getStocks().values().stream().map(this::convert2DO).toList();
        List<TornStocksDO> oldDataList = stocksDao.list();

        List<TornStocksDO> newDataList = new ArrayList<>();
        List<TornStocksDO> upadteDataList = new ArrayList<>();
        for (TornStocksDO stocks : stocksList) {
            if (oldDataList.stream().anyMatch(i -> i.getId().equals(stocks.getId()))) {
                upadteDataList.add(stocks);
            } else {
                newDataList.add(stocks);
            }
        }

        if (!CollectionUtils.isEmpty(newDataList)) {
            stocksDao.saveBatch(newDataList);
        }

        if (!CollectionUtils.isEmpty(upadteDataList)) {
            stocksDao.updateBatchById(upadteDataList);
        }

        saveStocksHistory(resp);
        sendGreatTradeChangeMsg();
    }

    /**
     * 计算日利润
     */
    private TornStocksDO convert2DO(TornStocksDetailVO stock) {
        TornStocksBenefitVO benefit = stock.getBenefit();
        long profit = parseBenefitValue(benefit.getDescription(), stock.getAcronym());
        long yearProfit = profit / benefit.getFrequency() * 365;
        long baseCost = stock.getCurrentPrice()
                .multiply(BigDecimal.valueOf(stock.getBenefit().getRequirement()))
                .longValue();
        return stock.convert2DO(profit, yearProfit, baseCost);
    }

    /**
     * 转换分红价值
     */
    private long parseBenefitValue(String description, String acronym) {
        // 特殊股票处理
        if ("PTS".equals(acronym)) {
            long pointValue = Long.parseLong(settingManager.getSettingValue(SettingConstants.KEY_POINT_VALUE));
            return pointValue * 100;
        } else if ("HRG".equals(acronym)) {
            return 50000000;
        }

        // 货币类型处理
        Matcher currencyMatcher = CURRENCY_PATTERN.matcher(description);
        if (currencyMatcher.find()) {
            String amount = currencyMatcher.group(1).replace(",", "");
            return Long.parseLong(amount);
        }

        // 物品类型处理
        Matcher itemMatcher = ITEM_PATTERN.matcher(description);
        if (itemMatcher.find()) {
            TornItemsDO item = itemsManager.getList().stream()
                    .filter(i -> i.getItemName().equals(itemMatcher.group(1)))
                    .findAny().orElse(null);
            return item == null ? 0L : item.getMarketPrice();
        }

        return 0L;
    }

    /**
     * 保存股票历史
     */
    private void saveStocksHistory(TornStocksVO resp) {
        LocalDateTime regDateTime = LocalDateTime.now();
        List<TornStocksHistoryDO> historyList = resp.getStocks().values().stream()
                .map(i -> i.convert2HistoryDO(regDateTime)).toList();
        stocksHistoryDao.saveBatch(historyList);
    }

    /**
     * 发送巨额交易信息
     */
    private void sendGreatTradeChangeMsg() {
        List<LocalDateTime> recordTimes = stocksHistoryDao.getLatestTwoRecordTimes();
        LocalDateTime latestTime = recordTimes.get(0);
        LocalDateTime previousTime = recordTimes.get(1);

        List<StocksChangeDO> changeList = stocksHistoryDao.getGreatTradeChangeList(latestTime, previousTime,
                NOTICE_THRESHOLD);
        if (CollectionUtils.isEmpty(changeList)) {
            return;
        }

        List<QqMsgParam<?>> msgList = new ArrayList<>();
        msgList.add(new TextQqMsg("过去5分钟内, 检测到股票大额交易"));
        for (StocksChangeDO change : changeList) {
            change.calculateNetTrade();
            msgList.add(new TextQqMsg("\n" + change.getStocksShortname() + ": "
                    + (change.isBuy() ? "买入: +" : "卖出: ")
                    + NumberUtils.formatCompactNumber(change.getNetTradeValue())
                    + " 当前价格: " + change.getCurrentPrice()));
        }

        BotHttpReqParam param = new GroupMsgHttpBuilder()
                .setGroupId(projectProperty.getVipGroupId())
                .addMsg(msgList)
                .build();
        bot.sendRequest(param, String.class);
    }
}