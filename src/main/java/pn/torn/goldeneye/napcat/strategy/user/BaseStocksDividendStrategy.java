package pn.torn.goldeneye.napcat.strategy.user;

import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import pn.torn.goldeneye.base.torn.TornApi;
import pn.torn.goldeneye.configuration.TornApiKeyConfig;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.napcat.receive.msg.QqRecMsgSender;
import pn.torn.goldeneye.napcat.send.msg.param.QqMsgParam;
import pn.torn.goldeneye.napcat.send.msg.param.TextQqMsg;
import pn.torn.goldeneye.napcat.strategy.base.SmthMsgStrategy;
import pn.torn.goldeneye.repository.dao.torn.stocks.TornStocksDAO;
import pn.torn.goldeneye.repository.dao.user.TornUserStockBlockDAO;
import pn.torn.goldeneye.repository.model.setting.TornApiKeyDO;
import pn.torn.goldeneye.repository.model.torn.stocks.TornStocksDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.manager.user.StocksBonusAnalyzeManager;
import pn.torn.goldeneye.torn.model.user.stocks.TornUserStocksDTO;
import pn.torn.goldeneye.torn.model.user.stocks.TornUserStocksVO;
import pn.torn.goldeneye.utils.NumberUtils;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 股票分红计算基类
 *
 * <p>除分红计算外，还承载两个分红指令共用的股票屏蔽子指令：
 * <ul>
 *     <li>{@code g#指令#屏蔽#股票简称,股票简称}：屏蔽股票，多个用英文逗号分隔</li>
 *     <li>{@code g#指令#取消屏蔽#股票简称}：取消屏蔽</li>
 *     <li>{@code g#指令#屏蔽}：查看当前屏蔽列表</li>
 * </ul>
 * 被屏蔽股票不进入候选池与当前持仓识别，因此不会被推荐买入或卖出，
 * 其已投入成本也不计入本次预算。</p>
 *
 * @author Bai
 * @version 1.9.3
 * @since 2025.09.27
 */
@Component
public abstract class BaseStocksDividendStrategy extends SmthMsgStrategy {
    /**
     * 单次计算允许的最大资金，防止异常输入导致计算规模失控
     */
    private static final long MAX_CALC_MONEY = 100_000_000_000L;
    /**
     * 单次屏蔽/取消屏蔽允许的最大股票数量
     */
    private static final int MAX_BLOCK_STOCK_COUNT = 10;
    /**
     * 股票简称分隔符：英文逗号、中文逗号与空白
     */
    private static final String STOCK_NAME_SEPARATOR = "[,，\\s]+";

    @Resource
    private TornApiKeyConfig apiKeyConfig;
    @Resource
    private TornApi tornApi;
    @Resource
    private StocksBonusAnalyzeManager stocksBonusAnalyzeManager;
    @Resource
    private TornStocksDAO stocksDao;
    @Resource
    private TornUserStockBlockDAO stockBlockDao;

    @Override
    public List<? extends QqMsgParam<?>> handle(long groupId, QqRecMsgSender sender, String msg) {
        String[] segments = StringUtils.hasText(msg) ? msg.split("#") : new String[0];
        if (isSubCommand(segments, BotCommands.STOCK_DIVIDEND_BLOCK)) {
            return handleChangeBlock(sender, segments, true);
        }
        if (isSubCommand(segments, BotCommands.STOCK_DIVIDEND_UNBLOCK)) {
            return handleChangeBlock(sender, segments, false);
        }

        return handleCalculate(sender, msg);
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

    /**
     * 计算分红股配置
     *
     * @param sender 消息发送人
     * @param msg    指令参数
     * @return 回复消息
     */
    private List<? extends QqMsgParam<?>> handleCalculate(QqRecMsgSender sender, String msg) {
        Long money = NumberUtils.convert(msg);
        if (money == null) {
            return super.buildTextMsg(buildUsageMsg());
        }

        TornUserDO user = super.getTornUser(sender, "");
        TornApiKeyDO key = apiKeyConfig.getKeyByUserId(user.getId());
        if (key == null) {
            return super.buildTextMsg("这个人还没有绑定Key哦");
        }

        if (money > MAX_CALC_MONEY) {
            apiKeyConfig.returnKey(key);
            return super.buildTextMsg(user.getNickname() + ", 这么多钱咱吃点好的吧");
        }

        TornUserStocksVO userStocks = tornApi.sendRequest(new TornUserStocksDTO(), key, TornUserStocksVO.class);
        List<TornStocksDO> stocksList = stocksDao.selectDividendStocks();
        Set<Integer> blockedStocksIds = stockBlockDao.selectBlockedStocksIds(user.getId());
        List<StocksBonusAnalyzeManager.OptimalAction> result = stocksBonusAnalyzeManager
                .calculate(money, stocksList, userStocks, isBuyOnly(), blockedStocksIds);
        String blockedHint = buildBlockedHint(stocksList, blockedStocksIds);
        if (CollectionUtils.isEmpty(result)) {
            String reply = user.getNickname() + ", " + isBestNowMsg();
            return super.buildTextMsg(StringUtils.hasText(blockedHint) ? reply + "\n" + blockedHint : reply);
        }

        List<TextQqMsg> msgList = new ArrayList<>();
        msgList.add(new TextQqMsg(user.getNickname() + ", 推荐以下操作配置分红股: \n"));
        result.forEach(r -> msgList.add(new TextQqMsg(r.toString() + "\n")));
        if (StringUtils.hasText(blockedHint)) {
            msgList.add(new TextQqMsg(blockedHint));
        }
        return msgList;
    }

    /**
     * 屏蔽或取消屏蔽股票
     *
     * @param sender   消息发送人
     * @param segments 指令分段
     * @param block    true为屏蔽, false为取消屏蔽
     * @return 回复消息
     */
    private List<? extends QqMsgParam<?>> handleChangeBlock(QqRecMsgSender sender, String[] segments, boolean block) {
        TornUserDO user = super.getTornUser(sender, "");
        List<String> stockNames = buildStockNames(segments);
        if (stockNames.isEmpty()) {
            return super.buildTextMsg(buildBlockListMsg(user));
        }
        if (stockNames.size() > MAX_BLOCK_STOCK_COUNT) {
            return super.buildTextMsg(buildTooManyMsg(block));
        }

        ResolvedStocks resolved = resolveStocks(stockNames);
        Set<Integer> blockedStocksIds = stockBlockDao.selectBlockedStocksIds(user.getId());
        Map<Boolean, List<TornStocksDO>> groupedStocks = resolved.stocks().stream()
                .collect(Collectors.partitioningBy(stock -> blockedStocksIds.contains(stock.getId())));
        List<TornStocksDO> changedStocks = block ? groupedStocks.get(false) : groupedStocks.get(true);
        List<TornStocksDO> unchangedStocks = block ? groupedStocks.get(true) : groupedStocks.get(false);

        List<String> lines = new ArrayList<>();
        if (!changedStocks.isEmpty()) {
            updateBlocks(user.getId(), changedStocks, block);
            lines.add((block ? "已屏蔽: " : "已取消屏蔽: ") + describeStocks(changedStocks));
        }
        if (!unchangedStocks.isEmpty()) {
            String unchangedPrefix = block ? "之前已经屏蔽: " : "这些股票本来就没有屏蔽: ";
            lines.add(unchangedPrefix + describeStocks(unchangedStocks));
        }
        if (!resolved.notFoundNames().isEmpty()) {
            lines.add(buildNotFoundMsg(resolved.notFoundNames()));
        }
        if (lines.isEmpty()) {
            return super.buildTextMsg(buildBlockListMsg(user));
        }

        Set<Integer> latestBlockedIds = new HashSet<>(blockedStocksIds);
        changedStocks.forEach(stock -> updateIdSet(latestBlockedIds, stock.getId(), block));
        lines.add(buildBlockListLine(latestBlockedIds));
        return super.buildTextMsg(String.join("\n", lines));
    }

    /**
     * 写入或移除屏蔽记录
     *
     * @param userId 用户ID
     * @param stocks 变更的股票
     * @param block  true为屏蔽, false为取消屏蔽
     */
    private void updateBlocks(Long userId, List<TornStocksDO> stocks, boolean block) {
        List<Integer> stocksIds = stocks.stream().map(TornStocksDO::getId).toList();
        if (block) {
            stockBlockDao.saveBlocks(userId, stocksIds);
        } else {
            stockBlockDao.removeBlocks(userId, stocksIds);
        }
    }

    /**
     * 维护本次变更后的屏蔽股票ID集合
     *
     * @param stocksIds 屏蔽股票ID集合
     * @param stocksId  变更的股票ID
     * @param block     true为屏蔽, false为取消屏蔽
     */
    private void updateIdSet(Set<Integer> stocksIds, Integer stocksId, boolean block) {
        if (block) {
            stocksIds.add(stocksId);
        } else {
            stocksIds.remove(stocksId);
        }
    }

    /**
     * 构建单次操作股票数量超限的提示
     *
     * @param block true为屏蔽, false为取消屏蔽
     * @return 提示消息
     */
    private String buildTooManyMsg(boolean block) {
        return "一次最多" + MAX_BLOCK_STOCK_COUNT + "支股票, 可以分多次" + (block ? "屏蔽" : "取消屏蔽");
    }

    /**
     * 判断首个分段是否为指定子指令
     *
     * @param segments   指令分段
     * @param subCommand 子指令
     * @return true为是
     */
    private boolean isSubCommand(String[] segments, String subCommand) {
        return segments.length > 0 && subCommand.equals(segments[0].trim());
    }

    /**
     * 解析指令中的股票简称列表
     *
     * @param segments 指令分段
     * @return 股票简称列表; 无参数时返回空列表
     */
    private List<String> buildStockNames(String[] segments) {
        if (segments.length < 2 || !StringUtils.hasText(segments[1])) {
            return List.of();
        }

        return Arrays.stream(segments[1].split(STOCK_NAME_SEPARATOR))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
    }

    /**
     * 把股票简称解析为股票
     *
     * @param stockNames 股票简称列表
     * @return 解析结果，含未匹配到的简称
     */
    private ResolvedStocks resolveStocks(List<String> stockNames) {
        Map<String, TornStocksDO> shortNameMap = new HashMap<>();
        stocksDao.list().forEach(stock -> {
            if (StringUtils.hasText(stock.getStocksShortname())) {
                shortNameMap.put(stock.getStocksShortname().toUpperCase(Locale.ROOT), stock);
            }
        });

        Map<Integer, TornStocksDO> matchedStocks = new LinkedHashMap<>();
        Set<String> notFoundNames = new LinkedHashSet<>();
        for (String stockName : stockNames) {
            TornStocksDO stock = shortNameMap.get(stockName.toUpperCase(Locale.ROOT));
            if (stock == null) {
                notFoundNames.add(stockName);
            } else {
                matchedStocks.putIfAbsent(stock.getId(), stock);
            }
        }
        return new ResolvedStocks(new ArrayList<>(matchedStocks.values()), new ArrayList<>(notFoundNames));
    }

    /**
     * 构建当前屏蔽列表消息
     *
     * @param user 当前用户
     * @return 回复消息
     */
    private String buildBlockListMsg(TornUserDO user) {
        Set<Integer> blockedStocksIds = stockBlockDao.selectBlockedStocksIds(user.getId());
        if (CollectionUtils.isEmpty(blockedStocksIds)) {
            return "当前没有屏蔽的股票, 使用 g#" + getCommand() + "#屏蔽#股票简称 屏蔽股票, 多个用英文逗号分隔";
        }

        return buildBlockListLine(blockedStocksIds) + "\n使用 g#" + getCommand() + "#取消屏蔽#股票简称 取消屏蔽";
    }

    /**
     * 构建屏蔽列表行
     *
     * @param blockedStocksIds 屏蔽的股票ID集合
     * @return 屏蔽列表行
     */
    private String buildBlockListLine(Set<Integer> blockedStocksIds) {
        Map<Integer, TornStocksDO> stocksMap = new HashMap<>();
        stocksDao.list().forEach(stock -> stocksMap.put(stock.getId(), stock));
        List<String> stockTexts = blockedStocksIds.stream()
                .sorted()
                .map(stocksMap::get)
                .filter(Objects::nonNull)
                .map(this::describeStock)
                .toList();
        return "当前屏蔽: " + String.join(", ", stockTexts);
    }

    /**
     * 构建计算回复中的屏蔽提示
     *
     * @param stocks           候选股票列表
     * @param blockedStocksIds 屏蔽的股票ID集合
     * @return 屏蔽提示; 无屏蔽或屏蔽股票不在候选池时为空白
     */
    private String buildBlockedHint(List<TornStocksDO> stocks, Set<Integer> blockedStocksIds) {
        if (CollectionUtils.isEmpty(blockedStocksIds)) {
            return "";
        }

        List<TornStocksDO> blockedStocks = stocks.stream()
                .filter(stock -> stock != null && blockedStocksIds.contains(stock.getId()))
                .toList();
        return blockedStocks.isEmpty() ? "" : "已屏蔽: " + describeStocks(blockedStocks);
    }

    /**
     * 构建股票简称未匹配到的提示
     *
     * @param notFoundNames 未匹配到的股票简称
     * @return 提示消息
     */
    private String buildNotFoundMsg(List<String> notFoundNames) {
        return "没有找到股票简称: " + String.join(", ", notFoundNames) + ", 请输入股票简称, 例如 HRG";
    }

    /**
     * 构建指令用法提示
     *
     * @return 用法提示
     */
    private String buildUsageMsg() {
        return "请输入计算的金额, 例如有2b用于购买分红股, 则输入g#" + getCommand() + "#2b, 上限为100b" +
                "\n屏蔽股票: g#" + getCommand() + "#屏蔽#HRG, 多个用英文逗号分隔" +
                "\n取消屏蔽: g#" + getCommand() + "#取消屏蔽#HRG";
    }

    /**
     * 拼接多支股票的描述
     *
     * @param stocks 股票列表
     * @return 形如 {@code HRG(Home Retail Group), MUN(Munster Beverage Corp.)} 的描述
     */
    private String describeStocks(List<TornStocksDO> stocks) {
        return stocks.stream().map(this::describeStock).collect(Collectors.joining(", "));
    }

    /**
     * 拼接单支股票的描述
     *
     * @param stock 股票
     * @return 形如 {@code HRG(Home Retail Group)} 的描述
     */
    private String describeStock(TornStocksDO stock) {
        String shortName = stock.getStocksShortname();
        String name = stock.getStocksName();
        return StringUtils.hasText(name) ? shortName + "(" + name + ")" : shortName;
    }

    /**
     * 股票简称解析结果
     *
     * @param stocks        解析成功的股票
     * @param notFoundNames 未匹配到的股票简称
     */
    private record ResolvedStocks(
            List<TornStocksDO> stocks,
            List<String> notFoundNames) {
    }
}
