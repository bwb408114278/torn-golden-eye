package pn.torn.goldeneye.torn.manager.torn.stocks;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
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
import pn.torn.goldeneye.repository.dao.torn.stocks.TornStocksDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.TornStocksHistoryDAO;
import pn.torn.goldeneye.repository.model.torn.TornItemsDO;
import pn.torn.goldeneye.repository.model.torn.stocks.StocksChangeDO;
import pn.torn.goldeneye.repository.model.torn.stocks.StocksTradeStatsDO;
import pn.torn.goldeneye.repository.model.torn.stocks.TornStocksDO;
import pn.torn.goldeneye.repository.model.torn.stocks.TornStocksHistoryDO;
import pn.torn.goldeneye.torn.manager.setting.SysSettingManager;
import pn.torn.goldeneye.torn.manager.torn.TornItemsManager;
import pn.torn.goldeneye.torn.model.torn.stocks.TornStocksBonusVO;
import pn.torn.goldeneye.torn.model.torn.stocks.TornStocksDTO;
import pn.torn.goldeneye.torn.model.torn.stocks.TornStocksDetailVO;
import pn.torn.goldeneye.torn.model.torn.stocks.TornStocksVO;
import pn.torn.goldeneye.torn.service.stocks.alert.market.StockMarketClock;
import pn.torn.goldeneye.utils.DateTimeUtils;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Torn股票公共逻辑层
 *
 * @author Bai
 * @version 1.4.0
 * @since 2025.09.26
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TornStocksManager {
    private final ThreadPoolTaskExecutor virtualThreadExecutor;
    private final Bot bot;
    private final TornApi tornApi;
    private final StockFeatureBuildService featureBuildService;
    private final SysSettingManager settingManager;
    private final TornItemsManager itemsManager;
    private final TornStocksDAO stocksDao;
    private final TornStocksHistoryDAO stocksHistoryDao;
    private final ProjectProperty projectProperty;
    private final StockMarketClock marketClock;
    private final StockCollectionLogSummary collectionLogSummary;
    private static final long NOTICE_THRESHOLD = 100_000_000_000L;
    private static final String BUY_COUNT = "买入量: ";
    private static final String SELL_COUNT = "卖出量: ";
    private static final String AVG_PRICE = " | 平均价: ";
    private static final String OPEN_PRICE = " | 建仓价: ";
    private static final String CLOSE_PRICE = " | 清仓价: ";

    private static final Pattern CURRENCY_PATTERN = Pattern.compile("\\$(\\d{1,3}(?:,\\d{3})*)");
    private static final Pattern ITEM_PATTERN = Pattern.compile("1x (.+)");

    /**
     * JVM内实时采集防重入标记,同一时刻仅允许一个采集流程
     */
    private final AtomicBoolean realtimeProcessing = new AtomicBoolean(false);

    @Scheduled(cron = "5 * * * * ?", scheduler = "realtimeStockScheduler")
    public void spiderStockData() {
        if (!BotConstants.ENV_PROD.equals(projectProperty.getEnv())) {
            return;
        }

        if (!realtimeProcessing.compareAndSet(false, true)) {
            log.warn("股票实时采集-上一轮采集尚未完成, 跳过本次, 避免同JVM重入");
            return;
        }

        LocalDateTime startedAt = marketClock.now();
        LocalDateTime plannedMinute = startedAt.withSecond(0).withNano(0);
        try {
            TornStocksVO resp = tornApi.sendRequest(new TornStocksDTO(), TornStocksVO.class);
            LocalDateTime apiCompletedAt = marketClock.now();
            upsertStocksSnapshot(resp);
            HistoryInsertResult insertResult = saveStocksHistory(resp, plannedMinute);
            LocalDateTime historyPersistedAt = marketClock.now();
            logCollectionTiming(plannedMinute, startedAt, apiCompletedAt, historyPersistedAt,
                    insertResult.expectedCount(), insertResult.insertedCount());
            handleInsertResult(insertResult, plannedMinute);
        } catch (Exception e) {
            log.error("股票实时采集-异常, plannedMinute={}, startedAt={}, 不写半成功结论", plannedMinute, startedAt, e);
            throw e;
        } finally {
            realtimeProcessing.set(false);
        }
    }

    /**
     * 更新股票当前快照（新增/更新 torn_stocks）
     *
     * @param resp Torn 股票行情响应
     */
    private void upsertStocksSnapshot(TornStocksVO resp) {
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
    }

    /**
     * 保存股票历史（以计划自然分钟为分钟事实键,冲突安全写入）
     *
     * @param resp          Torn 股票行情响应
     * @param plannedMinute 计划自然分钟采样键（Asia/Shanghai,秒与纳秒清零）
     * @return 预期与实际插入行数
     */
    private HistoryInsertResult saveStocksHistory(TornStocksVO resp, LocalDateTime plannedMinute) {
        List<TornStocksHistoryDO> historyList = resp.getStocks().stream()
                .map(i -> i.convert2HistoryDO(plannedMinute)).toList();
        int expectedCount = historyList.size();
        int insertedCount = expectedCount == 0 ? 0 : stocksHistoryDao.insertRealtimeIgnoreConflict(historyList);
        return new HistoryInsertResult(expectedCount, insertedCount);
    }

    /**
     * 依据实际插入结果控制下游：全量插入才派发大额交易消息与旧分钟特征异步处理。
     * <p>
     * <ul>
     *   <li>{@code inserted == expected}: 正常完成,异步派发大额交易检测与旧分钟特征处理;</li>
     *   <li>{@code inserted == 0}: 本分钟已写入,INFO 后返回,不发消息、不推进旧特征游标;</li>
     *   <li>{@code 0 < inserted < expected}: 部分冲突,fail-closed 抛异常,不发消息、不推进旧特征游标。</li>
     * </ul>
     *
     * @param insertResult  插入结果
     * @param plannedMinute 计划自然分钟采样键
     */
    private void handleInsertResult(HistoryInsertResult insertResult, LocalDateTime plannedMinute) {
        if (insertResult.insertedCount() == insertResult.expectedCount()) {
            log.debug("股票实时采集-本分钟写入完成, 派发下游异步处理, plannedMinute={}, expected={}, inserted={}",
                    plannedMinute, insertResult.expectedCount(), insertResult.insertedCount());
            virtualThreadExecutor.execute(() -> sendGreatTradeChangeMsg(plannedMinute));
            calcStockFeature(plannedMinute);
        } else if (insertResult.insertedCount() == 0) {
            log.info("股票实时采集-本分钟已写入(全冲突跳过), 不发消息不推进旧特征游标, plannedMinute={}", plannedMinute);
        } else {
            log.error("股票实时采集-部分分钟冲突, fail-closed不发消息不推进旧特征游标, plannedMinute={}, "
                            + "expected={}, inserted={}",
                    plannedMinute, insertResult.expectedCount(), insertResult.insertedCount());
            throw new IllegalStateException("股票实时采集部分分钟冲突, plannedMinute=" + plannedMinute
                    + ", expected=" + insertResult.expectedCount()
                    + ", inserted=" + insertResult.insertedCount());
        }
    }

    /**
     * 记录采集时序指标日志（不额外建审计表）
     *
     * @param plannedMinute      计划自然分钟采样键
     * @param startedAt          采集方法实际开始时间
     * @param apiCompletedAt     Torn API 请求完成时间
     * @param historyPersistedAt 历史插入完成时间
     * @param expectedStockCount 预期股票数
     * @param insertedStockCount 实际插入行数
     */
    private void logCollectionTiming(LocalDateTime plannedMinute, LocalDateTime startedAt,
                                     LocalDateTime apiCompletedAt, LocalDateTime historyPersistedAt,
                                     int expectedStockCount, int insertedStockCount) {
        long queueOrStartDelayMillis = Duration.between(plannedMinute, startedAt).toMillis();
        long apiCostMillis = Duration.between(startedAt, apiCompletedAt).toMillis();
        long dbCostMillis = Duration.between(apiCompletedAt, historyPersistedAt).toMillis();
        log.debug("股票实时采集-时序, plannedMinute={}, startedAt={}, apiCompletedAt={}, historyPersistedAt={}, "
                        + "queueOrStartDelayMillis={}, apiCostMillis={}, dbCostMillis={}, "
                        + "expectedStockCount={}, insertedStockCount={}",
                plannedMinute, startedAt, apiCompletedAt, historyPersistedAt,
                queueOrStartDelayMillis, apiCostMillis, dbCostMillis,
                expectedStockCount, insertedStockCount);
        if (insertedStockCount == expectedStockCount) {
            StockCollectionLogSummary.WindowRecordResult result = collectionLogSummary.recordSuccess(
                    new StockCollectionLogSummary.MinuteMetric(
                            plannedMinute,
                            expectedStockCount,
                            insertedStockCount,
                            queueOrStartDelayMillis,
                            apiCostMillis,
                            dbCostMillis));
            if (result.discardedIncompleteWindow()) {
                log.debug("股票实时采集-窗口摘要-跨窗口丢弃未完成窗口, plannedMinute={}", plannedMinute);
            }
            result.completedWindow().ifPresent(summary ->
                    log.info("股票实时采集-窗口摘要, windowStart={}, windowEndExclusive={}, "
                                    + "successfulMinuteCount={}, expectedStockRows={}, insertedStockRows={}, "
                                    + "maxQueueOrStartDelayMs={}, maxApiCostMs={}, maxDbCostMs={}",
                            summary.windowStart(), summary.windowEndExclusive(),
                            summary.successfulMinuteCount(), summary.expectedStockRows(),
                            summary.insertedStockRows(), summary.maxQueueOrStartDelayMs(),
                            summary.maxApiCostMs(), summary.maxDbCostMs()));
        }
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

        List<String> msgPriceList = new ArrayList<>();
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("过去1分钟内, 检测到股票大额交易");
        for (StocksChangeDO change : changeList) {
            change.calculateNetTrade();
            String msgPrice = change.getStocksShortname() + ": "
                    + (change.isBuy() ? "买入: +" : "卖出: ")
                    + NumberUtils.formatCompactNumber(change.getNetTradeValue())
                    + " 当前价格: " + change.getCurrentPrice();
            msgPriceList.add(msgPrice);
            stringBuilder.append(buildStockMsgContent(change, msgPrice, statsMap));
        }

        List<QqMsgParam<?>> msgList = new ArrayList<>();
        TextImageUtils.TextConfig textConfig = new TextImageUtils.TextConfig()
                .setFont(new Font("微软雅黑", Font.PLAIN, 30));
        msgList.add(ImageQqMsg.fromBase64(TextImageUtils.renderTextToBase64(stringBuilder.toString(), textConfig)));
        msgList.add(new TextQqMsg(DateTimeUtils.convertToString(regDateTime) +
                " 股票大额交易\n" + String.join("\n", msgPriceList)));
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
    private String buildStockMsgContent(StocksChangeDO change, String msgPrice,
                                        Map<Integer, StocksTradeStatsDO> statsMap) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n").append(msgPrice);

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

    /**
     * 计算股票特征值
     */
    private void calcStockFeature(LocalDateTime regDateTime) {
        virtualThreadExecutor.execute(() -> {
            String setting = settingManager.getSettingValue(SettingConstants.KEY_STOCK_FEATURE_LOAD);
            featureBuildService.buildBetween(DateTimeUtils.convertToDateTime(setting), regDateTime);
            settingManager.updateSetting(SettingConstants.KEY_STOCK_FEATURE_LOAD,
                    DateTimeUtils.convertToString(regDateTime));
        });
    }

    /**
     * 历史写入结果
     *
     * @param expectedCount 预期写入行数
     * @param insertedCount 实际插入行数（自然分钟冲突跳过不计入）
     */
    private record HistoryInsertResult(int expectedCount, int insertedCount) {
    }
}