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
import pn.torn.goldeneye.napcat.send.msg.param.ImageQqMsg;
import pn.torn.goldeneye.napcat.send.msg.param.QqMsgParam;
import pn.torn.goldeneye.napcat.send.msg.param.TextQqMsg;
import pn.torn.goldeneye.repository.dao.torn.TornStocksDAO;
import pn.torn.goldeneye.repository.dao.torn.TornStocksHistoryDAO;
import pn.torn.goldeneye.repository.model.torn.*;
import pn.torn.goldeneye.torn.manager.setting.SysSettingManager;
import pn.torn.goldeneye.torn.model.torn.stocks.TornStocksBonusVO;
import pn.torn.goldeneye.torn.model.torn.stocks.TornStocksDTO;
import pn.torn.goldeneye.torn.model.torn.stocks.TornStocksDetailVO;
import pn.torn.goldeneye.torn.model.torn.stocks.TornStocksVO;
import pn.torn.goldeneye.utils.NumberUtils;
import pn.torn.goldeneye.utils.image.TextImageUtils;

import java.awt.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Torn股票公共逻辑层
 *
 * @author Bai
 * @version 1.0.0
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
    private static final long NOTICE_THRESHOLD = 100_000_000_000L;
    private static final String BUY_COUNT = "买入量: ";
    private static final String SELL_COUNT = "卖出量: ";
    private static final String AVG_PRICE = " | 平均价: ";
    private static final String OPEN_PRICE = " | 建仓价: ";
    private static final String CLOSE_PRICE = " | 清仓价: ";

    private static final Pattern CURRENCY_PATTERN = Pattern.compile("\\$(\\d{1,3}(?:,\\d{3})*)");
    private static final Pattern ITEM_PATTERN = Pattern.compile("1x (.+)");

    @Scheduled(cron = "5 * * * * ?")
    public void spiderStockData() {
        if (!BotConstants.ENV_PROD.equals(projectProperty.getEnv())) {
            return;
        }

        TornStocksVO resp = tornApi.sendRequest(new TornStocksDTO(), TornStocksVO.class);
        List<TornStocksDO> stocksList = resp.getStocks().stream().map(this::convert2DO).toList();
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

        LocalDateTime regDateTime = LocalDateTime.now();
        saveStocksHistory(resp, regDateTime);
        sendGreatTradeChangeMsg(regDateTime);
    }

    /**
     * 计算日利润
     */
    private TornStocksDO convert2DO(TornStocksDetailVO stock) {
        TornStocksBonusVO benefit = stock.getBonus();
        long profit = parseBenefitValue(benefit.getDescription(), stock.getAcronym());
        long yearProfit = profit / benefit.getFrequency() * 365;
        long baseCost = stock.getMarket().getPrice()
                .multiply(BigDecimal.valueOf(stock.getBonus().getRequirement()))
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
    private void saveStocksHistory(TornStocksVO resp, LocalDateTime regDateTime) {
        List<TornStocksHistoryDO> historyList = resp.getStocks().stream()
                .map(i -> i.convert2HistoryDO(regDateTime)).toList();
        stocksHistoryDao.saveBatch(historyList);
    }

    /**
     * 发送巨额交易信息
     */
    private void sendGreatTradeChangeMsg(LocalDateTime regDateTime) {
        List<LocalDateTime> recordTimes = stocksHistoryDao.getLatestTwoRecordTimes();
        LocalDateTime latestTime = recordTimes.get(0);
        LocalDateTime previousTime = recordTimes.get(1);
        long period = Duration.between(previousTime, latestTime).toMinutes();
        if (period > 2) {
            return;
        }

        List<StocksChangeDO> changeList = stocksHistoryDao.getGreatTradeChangeList(latestTime, previousTime,
                NOTICE_THRESHOLD);
        if (CollectionUtils.isEmpty(changeList)) {
            return;
        }

        List<Integer> stocksIds = changeList.stream().map(StocksChangeDO::getStocksId).toList();
        Map<Integer, StocksTradeStatsDO> statsMap = stocksHistoryDao.getTradeStats(stocksIds,
                        NOTICE_THRESHOLD, regDateTime.minusHours(24), regDateTime.minusDays(7))
                .stream().collect(Collectors.toMap(StocksTradeStatsDO::getStocksId, s -> s));

        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("过去1分钟内, 检测到股票大额交易");
        for (StocksChangeDO change : changeList) {
            stringBuilder.append(buildStockMsgContent(change, statsMap));
        }
        String msgContent = stringBuilder.toString();

        List<QqMsgParam<?>> msgList = new ArrayList<>();
        TextImageUtils.TextConfig textConfig = new TextImageUtils.TextConfig()
                .setFont(new Font("微软雅黑", Font.PLAIN, 30));
        msgList.add(ImageQqMsg.fromBase64(TextImageUtils.renderTextToBase64(msgContent, textConfig)));
        msgList.add(new TextQqMsg(msgContent));
        BotHttpReqParam param = new GroupMsgHttpBuilder()
                .setGroupId(projectProperty.getVipGroupId())
                .addMsg(msgList)
                .build();
        bot.sendRequest(param, String.class);
    }

    /**
     * 构建股票消息内容
     *
     * @param statsMap Key为股票ID
     */
    private String buildStockMsgContent(StocksChangeDO change,
                                        Map<Integer, StocksTradeStatsDO> statsMap) {
        change.calculateNetTrade();
        StringBuilder sb = new StringBuilder();
        sb.append("\n").append(change.getStocksShortname()).append(": ")
                .append(change.isBuy() ? "买入: +" : "卖出: ")
                .append(NumberUtils.formatCompactNumber(change.getNetTradeValue()))
                .append(" 当前价格: ").append(change.getCurrentPrice());

        StocksTradeStatsDO stats = statsMap.get(change.getStocksId());
        if (stats != null) {
            sb.append("\n  ").append("24h: ")
                    .append(change.isBuy() ? BUY_COUNT : SELL_COUNT)
                    .append(NumberUtils.formatCompactNumber(
                            change.isBuy() ? stats.getBuyVolume24h() : stats.getSellVolume24h()))
                    .append(AVG_PRICE).append(stats.getAvgPrice24h().setScale(2, RoundingMode.HALF_UP))
                    .append(change.isBuy() ?
                            OPEN_PRICE + stats.getAvgBuyPrice24h().setScale(2, RoundingMode.HALF_UP) :
                            CLOSE_PRICE + stats.getAvgSellPrice24h().setScale(2, RoundingMode.HALF_UP));
            sb.append("\n  ").append("7d: ")
                    .append(change.isBuy() ? BUY_COUNT : SELL_COUNT)
                    .append(NumberUtils.formatCompactNumber(
                            change.isBuy() ? stats.getBuyVolume7d() : stats.getSellVolume7d()))
                    .append(AVG_PRICE).append(stats.getAvgPrice7d().setScale(2, RoundingMode.HALF_UP))
                    .append(change.isBuy() ?
                            OPEN_PRICE + stats.getAvgBuyPrice7d().setScale(2, RoundingMode.HALF_UP) :
                            CLOSE_PRICE + stats.getAvgSellPrice7d().setScale(2, RoundingMode.HALF_UP));
        }

        return sb.toString();
    }
}