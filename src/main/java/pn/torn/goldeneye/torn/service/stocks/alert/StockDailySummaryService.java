package pn.torn.goldeneye.torn.service.stocks.alert;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.constants.bot.BotConstants;
import pn.torn.goldeneye.constants.torn.SettingConstants;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.*;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockNoticeAuditDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockPortfolioSlotDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockMarketBar15mDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockSignalEventDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockVirtualBatchDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockNoticeAuditDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockPortfolioSlotDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockSignalEventDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchDO;
import pn.torn.goldeneye.torn.manager.setting.SysSettingManager;
import pn.torn.goldeneye.torn.service.stocks.alert.notice.StockNoticeSendService;
import pn.torn.goldeneye.utils.JsonUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * VIP股票每日摘要服务 - 每天08:30汇总正式组合与影子研究数据并发送中文摘要
 * <p>
 * 在生产环境下每日08:30(Asia/Shanghai)触发,摘要日期为发送日前一自然日:
 * <ol>
 *   <li>检查 {@link SettingConstants#KEY_VIP_STOCK_DAILY_SUMMARY_ENABLED} 开关</li>
 *   <li>检查生产环境({@link BotConstants#ENV_PROD})</li>
 *   <li>调用 {@link #buildSummaryData(LocalDate)} 收集正式组合与影子数据</li>
 *   <li>构建中文摘要文本并写入PENDING通知审计</li>
 *   <li>调用 {@link StockNoticeSendService#sendSingleMessage} 发送至VIP群,根据发送结果更新通知审计状态</li>
 * </ol>
 * 摘要内容覆盖正式组合(占用槽位、权益、昨日买卖、净收益、开放批次、陈旧批次)
 * 与影子研究(信号总数、影子新批次、满仓拒绝、风格拒绝、动态卖出建议、高风险观察)。
 *
 * <h3>影子分类口径</h3>
 * <ul>
 *   <li>原始买入信号 -&gt; 昨日全部信号事件数量</li>
 *   <li>无限资金影子新批次 -&gt; portfolioDecision = SHADOW 的事件数</li>
 *   <li>满仓拒绝 -&gt; rejectReason = NO_AVAILABLE_SLOT 的事件数</li>
 *   <li>风格/趋势拒绝 -&gt; rejectReason = STYLE_NOT_READY 或 portfolioDecision = REJECTED 的事件数</li>
 *   <li>动态卖出影子建议 -&gt; 昨日 exitReason 含 DYNAMIC 的影子批次数</li>
 *   <li>高风险观察 -&gt; 昨日 riskLevel = HIGH 的影子批次数</li>
 * </ul>
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.25
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockDailySummaryService {

    /**
     * 开关启用标识
     */
    private static final String SETTING_ENABLED_VALUE = "true";
    /**
     * 消息规则版本(与 {@link VipStockAlertScheduler#MESSAGE_RULE_VERSION} 保持一致)
     */
    private static final String MESSAGE_RULE_VERSION = "1.0.0";
    /**
     * 通知编号前缀
     */
    private static final String NOTICE_NO_PREFIX = "D";
    /**
     * 通知编号时间戳格式
     */
    private static final String NOTICE_NO_TIMESTAMP_PATTERN = "yyyyMMddHHmmssSSS";
    /**
     * 摘要日期展示格式
     */
    private static final String SUMMARY_DATE_PATTERN = "yyyy-MM-dd";
    /**
     * 动态卖出退出原因关键字
     */
    private static final String DYNAMIC_EXIT_KEYWORD = "DYNAMIC";
    /**
     * 摘要标题模板
     */
    private static final String SUMMARY_TITLE_TEMPLATE = "【VIP股票组合日报｜%s】";
    /**
     * 影子研究免责声明
     */
    private static final String SHADOW_DISCLAIMER = "提示：影子数据仅用于策略研究，不代表正式操作建议。";
    /**
     * 通知编号格式化器
     */
    private static final DateTimeFormatter NOTICE_NO_FORMATTER =
            DateTimeFormatter.ofPattern(NOTICE_NO_TIMESTAMP_PATTERN);
    /**
     * 摘要日期格式化器
     */
    private static final DateTimeFormatter SUMMARY_DATE_FORMATTER =
            DateTimeFormatter.ofPattern(SUMMARY_DATE_PATTERN);

    private final TornStockPortfolioSlotDAO portfolioSlotDAO;
    private final TornStockVirtualBatchDAO virtualBatchDAO;
    private final TornStockSignalEventDAO signalEventDAO;
    private final TornStockNoticeAuditDAO noticeAuditDAO;
    private final TornStockMarketBar15mDAO bar15mDAO;
    private final StockPortfolioService portfolioService;
    private final StockNoticeSendService noticeSendService;
    private final StockMarketClock marketClock;
    private final ProjectProperty projectProperty;
    private final SysSettingManager sysSettingManager;

    /**
     * 每日08:30执行摘要调度(Asia/Shanghai)。
     * <p>
     * 执行前置检查:
     * <ol>
     *   <li>非生产环境直接返回</li>
     *   <li> {@link SettingConstants#KEY_VIP_STOCK_DAILY_SUMMARY_ENABLED} 开关不为 "true" 时返回</li>
     * </ol>
     * 通过后计算摘要日期(发送日前一自然日),构建摘要数据,写入PENDING通知审计,
     * 调用统一发送服务发送至VIP群,并根据发送结果更新通知审计状态。任一步骤异常时记录日志不中断后续调度。
     */
    @Scheduled(cron = "0 30 8 * * *", zone = "Asia/Shanghai")
    public void executeDailySummary() {
        if (!BotConstants.ENV_PROD.equals(projectProperty.getEnv())) {
            return;
        }

        String enabled = sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_DAILY_SUMMARY_ENABLED);
        if (!SETTING_ENABLED_VALUE.equalsIgnoreCase(enabled)) {
            return;
        }

        LocalDate summaryDate = LocalDate.now().minusDays(1);
        log.info("VIP股票每日摘要-开始执行, summaryDate={}", summaryDate);

        DailySummaryData data;
        try {
            data = buildSummaryData(summaryDate);
        } catch (Exception e) {
            log.error("VIP股票每日摘要-构建摘要数据失败, summaryDate={}", summaryDate, e);
            return;
        }

        String summaryText = buildSummaryText(data);
        TornStockNoticeAuditDO notice = savePendingNotice(summaryDate, summaryText);
        sendAndUpdateNotice(notice, summaryText);
    }

    /**
     * 构建每日摘要数据,包含正式组合与影子研究两部分。
     * <p>
     * 正式组合部分:查询VIP组合全部槽位与活跃正式批次,统计占用槽位、组合权益、
     * 昨日买入/卖出批次、昨日已实现净收益、开放批次股票列表与数据陈旧批次数量。
     * <br>影子研究部分:查询摘要日期范围内的信号事件,按portfolioDecision与rejectReason分组统计;
     * 查询昨日影子批次统计动态卖出建议与高风险观察数量。
     *
     * @param summaryDate 摘要日期(发送日前一自然日)
     * @return 摘要数据对象
     */
    public DailySummaryData buildSummaryData(LocalDate summaryDate) {
        FormalSummary formal = buildFormalSummary(summaryDate);
        ShadowSummary shadow = buildShadowSummary(summaryDate);
        return new DailySummaryData(formal, shadow);
    }

    /**
     * 构建正式组合摘要数据。
     * <p>
     * 查询VIP组合全部槽位,统计非AVAILABLE状态的占用槽位数;
     * 查询活跃正式批次,计算组合权益(简化处理:现金+预留+开放仓位投入资金);
     * 统计昨日entryTime/exitTime落在摘要日期范围内的买入/卖出批次,
     * 昨日净收益为卖出批次netReturn之和;
     * 开放批次为OPEN状态的stocksShortname列表,陈旧批次为DATA_STALE状态数量。
     *
     * @param summaryDate 摘要日期
     * @return 正式组合摘要
     */
    private FormalSummary buildFormalSummary(LocalDate summaryDate) {
        List<TornStockPortfolioSlotDO> slots = portfolioSlotDAO.selectAllByPortfolioCode(StockPortfolioService.PORTFOLIO_CODE);
        List<TornStockVirtualBatchDO> activeBatches = virtualBatchDAO.selectActiveFormalBatches();

        int occupiedSlots = countOccupiedSlots(slots);
        BigDecimal equity = calculateEquity(slots, activeBatches);

        LocalDateTime dayStart = summaryDate.atStartOfDay();
        LocalDateTime dayEnd = summaryDate.plusDays(1).atStartOfDay();

        List<TornStockVirtualBatchDO> yesterdayFormalBatches = queryFormalBatchesByTimeRange(dayStart, dayEnd);
        int yesterdayBuyCount = countBatchesInRange(yesterdayFormalBatches, dayStart, dayEnd, true);
        int yesterdaySellCount = countBatchesInRange(yesterdayFormalBatches, dayStart, dayEnd, false);
        BigDecimal yesterdayNetReturn = sumNetReturn(yesterdayFormalBatches, dayStart, dayEnd);

        List<String> openBatchStocks = extractOpenBatchStocks(activeBatches);
        int staleBatchCount = countStaleBatches(activeBatches);

        return new FormalSummary(occupiedSlots, equity, yesterdayBuyCount, yesterdaySellCount,
                yesterdayNetReturn, openBatchStocks, staleBatchCount, summaryDate);
    }

    /**
     * 构建影子研究摘要数据。
     * <p>
     * 查询摘要日期范围内的全部信号事件,按portfolioDecision与rejectReason分组统计:
     * <ul>
     *   <li>signalCount - 全部信号事件数</li>
     *   <li>shadowNewCount - portfolioDecision = SHADOW 的事件数</li>
     *   <li>fullRejectCount - rejectReason = NO_AVAILABLE_SLOT 的事件数</li>
     *   <li>styleRejectCount - rejectReason = STYLE_NOT_READY 或 portfolioDecision = REJECTED 的事件数</li>
     * </ul>
     * 查询昨日影子批次统计动态卖出建议(exitReason含DYNAMIC)与高风险观察(riskLevel=HIGH)数量。
     *
     * @param summaryDate 摘要日期
     * @return 影子研究摘要
     */
    private ShadowSummary buildShadowSummary(LocalDate summaryDate) {
        LocalDateTime dayStart = summaryDate.atStartOfDay();
        LocalDateTime dayEnd = summaryDate.plusDays(1).atStartOfDay();

        List<TornStockSignalEventDO> signalEvents = querySignalEventsByTimeRange(dayStart, dayEnd);
        int signalCount = signalEvents.size();
        int shadowNewCount = countByDecision(signalEvents, StockPortfolioDecisionEnum.SHADOW);
        int fullRejectCount = countByRejectReason(signalEvents, StockCancelReasonEnum.NO_AVAILABLE_SLOT);
        int styleRejectCount = countStyleReject(signalEvents);

        List<TornStockVirtualBatchDO> shadowBatches = queryShadowBatchesByTimeRange(dayStart, dayEnd);
        int dynamicSellCount = countDynamicSell(shadowBatches);
        int highRiskCount = countHighRisk(shadowBatches);

        return new ShadowSummary(signalCount, shadowNewCount, fullRejectCount,
                styleRejectCount, dynamicSellCount, highRiskCount);
    }

    /**
     * 统计占用槽位数(状态非AVAILABLE)。
     *
     * @param slots 全部槽位
     * @return 占用槽位数
     */
    private int countOccupiedSlots(List<TornStockPortfolioSlotDO> slots) {
        if (CollectionUtils.isEmpty(slots)) {
            return 0;
        }
        return (int) slots.stream()
                .filter(slot -> !StockSlotStatusEnum.AVAILABLE.getCode().equals(slot.getSlotStatus()))
                .count();
    }

    /**
     * 计算组合权益(现金+预留+开放仓位当前市值)
     * <p>
     * 开放仓位市值 = quantity × currentPrice × 0.999(扣除卖出手续费),
     * 其中currentPrice取最近已结束桶的bar.lastPrice。
     * 批量查询最新bar避免N+1,无bar数据时该仓位市值按投入资金近似。
     *
     * @param slots         全部槽位
     * @param activeBatches 活跃正式批次
     * @return 组合权益总额
     */
    private BigDecimal calculateEquity(List<TornStockPortfolioSlotDO> slots,
                                       List<TornStockVirtualBatchDO> activeBatches) {
        Map<Long, BigDecimal> batchMarketValues = new HashMap<>();
        if (activeBatches != null && !activeBatches.isEmpty()) {
            Map<Integer, TornStockMarketBar15mDO> latestBarByStock = loadLatestBars();
            for (TornStockVirtualBatchDO batch : activeBatches) {
                if (batch.getSlotId() == null || batch.getQuantity() == null) {
                    continue;
                }
                BigDecimal marketValue = calculateBatchMarketValue(batch, latestBarByStock);
                batchMarketValues.put(batch.getSlotId(), marketValue);
            }
        }
        return portfolioService.calculateEquity(slots, batchMarketValues);
    }

    /**
     * 批量加载最新bar,按股票ID索引避免N+1查询。
     *
     * @return 按股票ID索引的最新bar映射
     */
    private Map<Integer, TornStockMarketBar15mDO> loadLatestBars() {
        LocalDateTime latestBucket = marketClock.currentEndedBucket();
        List<TornStockMarketBar15mDO> bars = bar15mDAO.selectByBarStartTime(latestBucket, Stock15mBarBuildService.BUILD_VERSION);
        Map<Integer, TornStockMarketBar15mDO> barByStock = new HashMap<>();
        for (TornStockMarketBar15mDO bar : bars) {
            barByStock.put(bar.getStocksId(), bar);
        }
        return barByStock;
    }

    /**
     * 计算单个批次的当前市值。
     * <p>
     * marketValue = quantity × currentPrice × 0.999(扣除0.1%卖出手续费)。
     * 无最新bar或价格非法时按投入资金(investedCash)近似。
     *
     * @param batch          活跃批次
     * @param latestBarByStock 按股票ID索引的最新bar映射
     * @return 批次当前市值
     */
    private BigDecimal calculateBatchMarketValue(TornStockVirtualBatchDO batch,
                                                   Map<Integer, TornStockMarketBar15mDO> latestBarByStock) {
        TornStockMarketBar15mDO bar = latestBarByStock.get(batch.getStocksId());
        if (bar != null && bar.getLastPrice() != null && bar.getLastPrice().signum() > 0) {
            return bar.getLastPrice()
                    .multiply(BigDecimal.valueOf(batch.getQuantity()))
                    .multiply(StockPortfolioService.SELL_FEE_RATE);
        }
        BigDecimal investedCash = batch.getInvestedCash();
        return investedCash != null ? investedCash : BigDecimal.ZERO;
    }

    /**
     * 查询指定时间范围内入场的正式批次。
     * <p>
     * 使用MyBatis-Plus lambdaQuery按ledgerType=FORMAL与entryTime/exitTime范围过滤,
     * 一次性查询昨日有入场或出场动作的正式批次,避免逐条查询。
     *
     * @param dayStart 摘要日期起始(含)
     * @param dayEnd   摘要日期结束(不含)
     * @return 昨日有动作的正式批次列表
     */
    private List<TornStockVirtualBatchDO> queryFormalBatchesByTimeRange(LocalDateTime dayStart, LocalDateTime dayEnd) {
        List<TornStockVirtualBatchDO> byEntry = virtualBatchDAO.lambdaQuery()
                .eq(TornStockVirtualBatchDO::getLedgerType, StockLedgerTypeEnum.FORMAL.getCode())
                .ge(TornStockVirtualBatchDO::getEntryTime, dayStart)
                .lt(TornStockVirtualBatchDO::getEntryTime, dayEnd)
                .list();
        List<TornStockVirtualBatchDO> byExit = virtualBatchDAO.lambdaQuery()
                .eq(TornStockVirtualBatchDO::getLedgerType, StockLedgerTypeEnum.FORMAL.getCode())
                .ge(TornStockVirtualBatchDO::getExitTime, dayStart)
                .lt(TornStockVirtualBatchDO::getExitTime, dayEnd)
                .list();
        byEntry.addAll(byExit);
        return byEntry.stream().distinct().toList();
    }

    /**
     * 统计昨日买入或卖出批次数。
     *
     * @param batches  昨日有动作的正式批次
     * @param dayStart 摘要日期起始(含)
     * @param dayEnd   摘要日期结束(不含)
     * @param isBuy    true统计买入(entryTime),false统计卖出(exitTime)
     * @return 批次数
     */
    private int countBatchesInRange(List<TornStockVirtualBatchDO> batches,
                                    LocalDateTime dayStart, LocalDateTime dayEnd, boolean isBuy) {
        if (CollectionUtils.isEmpty(batches)) {
            return 0;
        }
        return (int) batches.stream()
                .filter(batch -> {
                    LocalDateTime time = isBuy ? batch.getEntryTime() : batch.getExitTime();
                    return time != null && !time.isBefore(dayStart) && time.isBefore(dayEnd);
                })
                .count();
    }

    /**
     * 汇总昨日卖出批次的净收益。
     *
     * @param batches  昨日有动作的正式批次
     * @param dayStart 摘要日期起始(含)
     * @param dayEnd   摘要日期结束(不含)
     * @return 净收益合计;无卖出批次时返回 {@link BigDecimal#ZERO}
     */
    private BigDecimal sumNetReturn(List<TornStockVirtualBatchDO> batches,
                                    LocalDateTime dayStart, LocalDateTime dayEnd) {
        if (CollectionUtils.isEmpty(batches)) {
            return BigDecimal.ZERO;
        }
        return batches.stream()
                .filter(batch -> {
                    LocalDateTime exitTime = batch.getExitTime();
                    return exitTime != null && !exitTime.isBefore(dayStart) && exitTime.isBefore(dayEnd);
                })
                .map(TornStockVirtualBatchDO::getNetReturn)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * 提取OPEN状态批次的股票简称列表。
     *
     * @param activeBatches 活跃正式批次
     * @return 股票简称列表;无开放批次时返回空列表
     */
    private List<String> extractOpenBatchStocks(List<TornStockVirtualBatchDO> activeBatches) {
        if (CollectionUtils.isEmpty(activeBatches)) {
            return Collections.emptyList();
        }
        return activeBatches.stream()
                .filter(batch -> StockBatchStatusEnum.OPEN.getCode().equals(batch.getBatchStatus()))
                .map(TornStockVirtualBatchDO::getStocksShortname)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    /**
     * 统计DATA_STALE状态批次数。
     *
     * @param activeBatches 活跃正式批次
     * @return 陈旧批次数
     */
    private int countStaleBatches(List<TornStockVirtualBatchDO> activeBatches) {
        if (CollectionUtils.isEmpty(activeBatches)) {
            return 0;
        }
        return (int) activeBatches.stream()
                .filter(batch -> StockBatchStatusEnum.DATA_STALE.getCode().equals(batch.getBatchStatus()))
                .count();
    }

    /**
     * 查询指定时间范围内的全部信号事件。
     * <p>
     * 使用MyBatis-Plus lambdaQuery按roundTime范围过滤,避免逐轮次查询产生N+1。
     *
     * @param dayStart 摘要日期起始(含)
     * @param dayEnd   摘要日期结束(不含)
     * @return 信号事件列表
     */
    private List<TornStockSignalEventDO> querySignalEventsByTimeRange(LocalDateTime dayStart, LocalDateTime dayEnd) {
        return signalEventDAO.lambdaQuery()
                .ge(TornStockSignalEventDO::getRoundTime, dayStart)
                .lt(TornStockSignalEventDO::getRoundTime, dayEnd)
                .list();
    }

    /**
     * 按组合决策统计信号事件数。
     *
     * @param signalEvents 信号事件列表
     * @param decision     组合决策
     * @return 匹配的事件数
     */
    private int countByDecision(List<TornStockSignalEventDO> signalEvents, StockPortfolioDecisionEnum decision) {
        if (CollectionUtils.isEmpty(signalEvents)) {
            return 0;
        }
        return (int) signalEvents.stream()
                .filter(event -> decision.getCode().equals(event.getPortfolioDecision()))
                .count();
    }

    /**
     * 按拒绝原因统计信号事件数。
     *
     * @param signalEvents 信号事件列表
     * @param reason       取消原因枚举
     * @return 匹配的事件数
     */
    private int countByRejectReason(List<TornStockSignalEventDO> signalEvents, StockCancelReasonEnum reason) {
        if (CollectionUtils.isEmpty(signalEvents)) {
            return 0;
        }
        return (int) signalEvents.stream()
                .filter(event -> reason.getCode().equals(event.getRejectReason()))
                .count();
    }

    /**
     * 统计风格/趋势拒绝的信号事件数。
     * <p>
     * 包含两种情形:
     * <ul>
     *   <li>rejectReason = STYLE_NOT_READY</li>
     *   <li>portfolioDecision = REJECTED 且 rejectReason 非 NO_AVAILABLE_SLOT</li>
     * </ul>
     *
     * @param signalEvents 信号事件列表
     * @return 风格/趋势拒绝事件数
     */
    private int countStyleReject(List<TornStockSignalEventDO> signalEvents) {
        if (CollectionUtils.isEmpty(signalEvents)) {
            return 0;
        }
        return (int) signalEvents.stream()
                .filter(event -> {
                    String reason = event.getRejectReason();
                    if (StockCancelReasonEnum.STYLE_NOT_READY.getCode().equals(reason)) {
                        return true;
                    }
                    return StockPortfolioDecisionEnum.REJECTED.getCode().equals(event.getPortfolioDecision())
                            && !StockCancelReasonEnum.NO_AVAILABLE_SLOT.getCode().equals(reason);
                })
                .count();
    }

    /**
     * 查询指定时间范围内的影子批次。
     * <p>
     * 使用MyBatis-Plus lambdaQuery按ledgerType=UNLIMITED_SHADOW或REJECTED_OBSERVATION,
     * 以及signalTime或exitTime范围过滤,一次性获取昨日有动作的影子批次。
     *
     * @param dayStart 摘要日期起始(含)
     * @param dayEnd   摘要日期结束(不含)
     * @return 昨日有动作的影子批次列表
     */
    private List<TornStockVirtualBatchDO> queryShadowBatchesByTimeRange(LocalDateTime dayStart, LocalDateTime dayEnd) {
        List<TornStockVirtualBatchDO> bySignal = virtualBatchDAO.lambdaQuery()
                .in(TornStockVirtualBatchDO::getLedgerType,
                        StockLedgerTypeEnum.UNLIMITED_SHADOW.getCode(),
                        StockLedgerTypeEnum.REJECTED_OBSERVATION.getCode())
                .ge(TornStockVirtualBatchDO::getSignalTime, dayStart)
                .lt(TornStockVirtualBatchDO::getSignalTime, dayEnd)
                .list();
        List<TornStockVirtualBatchDO> byExit = virtualBatchDAO.lambdaQuery()
                .in(TornStockVirtualBatchDO::getLedgerType,
                        StockLedgerTypeEnum.UNLIMITED_SHADOW.getCode(),
                        StockLedgerTypeEnum.REJECTED_OBSERVATION.getCode())
                .ge(TornStockVirtualBatchDO::getExitTime, dayStart)
                .lt(TornStockVirtualBatchDO::getExitTime, dayEnd)
                .list();
        bySignal.addAll(byExit);
        return bySignal.stream().distinct().toList();
    }

    /**
     * 统计动态卖出影子建议数(exitReason含DYNAMIC关键字)。
     *
     * @param shadowBatches 影子批次列表
     * @return 动态卖出建议数
     */
    private int countDynamicSell(List<TornStockVirtualBatchDO> shadowBatches) {
        if (CollectionUtils.isEmpty(shadowBatches)) {
            return 0;
        }
        return (int) shadowBatches.stream()
                .map(TornStockVirtualBatchDO::getExitReason)
                .filter(Objects::nonNull)
                .filter(reason -> reason.contains(DYNAMIC_EXIT_KEYWORD))
                .count();
    }

    /**
     * 统计高风险观察数(riskLevel=HIGH)。
     *
     * @param shadowBatches 影子批次列表
     * @return 高风险观察数
     */
    private int countHighRisk(List<TornStockVirtualBatchDO> shadowBatches) {
        if (CollectionUtils.isEmpty(shadowBatches)) {
            return 0;
        }
        return (int) shadowBatches.stream()
                .filter(batch -> StockRiskLevelEnum.HIGH.getCode().equals(batch.getRiskLevel()))
                .count();
    }

    /**
     * 构建中文摘要文本。
     * <p>
     * 按技术方案第12.5节格式拼接:标题、正式组合区块、影子研究区块、免责声明。
     *
     * @param data 摘要数据
     * @return 中文摘要文本
     */
    private String buildSummaryText(DailySummaryData data) {
        FormalSummary formal = data.formal();
        ShadowSummary shadow = data.shadow();
        String dateText = formal.summaryDate().format(SUMMARY_DATE_FORMATTER);
        String openStocks = formal.openBatchStocks().isEmpty()
                ? "无" : String.join("、", formal.openBatchStocks());

        return String.format(
                SUMMARY_TITLE_TEMPLATE + "%n%n正式组合%n- 当前占用槽位：%d / %d%n"
                        + "- 当前组合权益：%s%n- 昨日买入：%d批%n- 昨日卖出：%d批%n"
                        + "- 昨日已实现净收益：%s%n- 当前开放批次：%s%n- 数据陈旧批次：%d%n%n"
                        + "影子研究%n- 原始买入信号：%d个%n- 无限资金影子新批次：%d个%n"
                        + "- 满仓拒绝：%d个%n- 风格/趋势拒绝：%d个%n- 动态卖出影子建议：%d个%n"
                        + "- 高风险观察：%d个%n%n%s",
                dateText,
                formal.occupiedSlots(), StockPortfolioService.SLOT_COUNT,
                formal.equity().toPlainString(),
                formal.yesterdayBuyCount(), formal.yesterdaySellCount(),
                formal.yesterdayNetReturn().toPlainString(),
                openStocks, formal.staleBatchCount(),
                shadow.signalCount(), shadow.shadowNewCount(),
                shadow.fullRejectCount(), shadow.styleRejectCount(),
                shadow.dynamicSellCount(), shadow.highRiskCount(),
                SHADOW_DISCLAIMER
        );
    }

    /**
     * 保存PENDING状态的通知审计记录。
     * <p>
     * 构建DAILY_SUMMARY类型的通知审计DO,填充摘要日期、VIP群组ID、载荷快照与PENDING状态,
     * 通知编号格式为 "D" + yyyyMMddHHmmssSSS + "S"(Summary首字符)。
     *
     * @param summaryDate 摘要日期
     * @param summaryText 摘要文本
     * @return 已保存的通知审计DO(含主键ID)
     */
    private TornStockNoticeAuditDO savePendingNotice(LocalDate summaryDate, String summaryText) {
        TornStockNoticeAuditDO notice = new TornStockNoticeAuditDO();
        notice.setNoticeNo(generateNoticeNo());
        notice.setNoticeType(StockNoticeTypeEnum.DAILY_SUMMARY.getCode());
        notice.setSummaryDate(summaryDate);
        notice.setGroupId(projectProperty.getVipGroupId());
        notice.setSendStatus(StockNoticeStatusEnum.PENDING.getCode());
        notice.setSendAttemptCount(0);
        notice.setMessageRuleVersion(MESSAGE_RULE_VERSION);
        notice.setPayloadSnapshot(buildPayloadSnapshot(summaryDate, summaryText));
        notice.setPayloadHash(generatePayloadHash(summaryDate));
        noticeAuditDAO.save(notice);
        return notice;
    }

    /**
     * 生成通知编号。
     * <p>
     * 格式: "D" + yyyyMMddHHmmssSSS + "S"
     *
     * @return 通知编号
     */
    private String generateNoticeNo() {
        String timestamp = LocalDateTime.now().format(NOTICE_NO_FORMATTER);
        return NOTICE_NO_PREFIX + timestamp + "S";
    }

    /**
     * 生成载荷哈希(SHA-256,基于摘要日期,同一摘要日期哈希一致便于去重)。
     *
     * @param summaryDate 摘要日期
     * @return 载荷哈希(64位十六进制字符串)
     */
    private String generatePayloadHash(LocalDate summaryDate) {
        return StockHashUtils.sha256("DAILY_SUMMARY_" + summaryDate.toString());
    }

    /**
     * 构建载荷快照JSON。
     *
     * @param summaryDate 摘要日期
     * @param summaryText 摘要文本
     * @return 载荷快照JSON文本
     */
    private String buildPayloadSnapshot(LocalDate summaryDate, String summaryText) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("noticeType", StockNoticeTypeEnum.DAILY_SUMMARY.getCode());
        payload.put("summaryDate", summaryDate.toString());
        payload.put("groupId", projectProperty.getVipGroupId());
        payload.put("messageText", summaryText);
        return JsonUtils.objToJson(payload);
    }

    /**
     * 调用统一发送服务发送摘要至VIP群,并根据发送结果更新通知审计状态。
     * <p>
     * 复用 {@link StockNoticeSendService#sendSingleMessage} 发送(HTTP 2xx且body非空视为成功),
     * 发送成功时更新sendStatus=SENT并记录sentAt;发送失败时更新sendStatus=FAILED并记录错误信息。
     * 发送异常时不抛出,仅记录日志并标记FAILED,等待后续重试机制处理。
     *
     * @param notice      通知审计DO
     * @param summaryText 摘要文本
     */
    private void sendAndUpdateNotice(TornStockNoticeAuditDO notice, String summaryText) {
        notice.setSendAttemptCount(notice.getSendAttemptCount() == null ? 1 : notice.getSendAttemptCount() + 1);
        notice.setAttemptedAt(LocalDateTime.now());
        try {
            boolean sent = noticeSendService.sendSingleMessage(summaryText);
            if (sent) {
                notice.setSendStatus(StockNoticeStatusEnum.SENT.getCode());
                notice.setSentAt(LocalDateTime.now());
                noticeAuditDAO.updateById(notice);
                log.info("VIP股票每日摘要-发送成功, noticeNo={}", notice.getNoticeNo());
            } else {
                notice.setSendStatus(StockNoticeStatusEnum.FAILED.getCode());
                notice.setErrorMessage("统一发送服务返回失败");
                noticeAuditDAO.updateById(notice);
                log.warn("VIP股票每日摘要-发送失败, noticeNo={}", notice.getNoticeNo());
            }
        } catch (Exception e) {
            log.error("VIP股票每日摘要-发送异常, noticeNo={}", notice.getNoticeNo(), e);
            notice.setSendStatus(StockNoticeStatusEnum.FAILED.getCode());
            notice.setErrorMessage(e.getMessage());
            noticeAuditDAO.updateById(notice);
        }
    }

    // ==================== 值对象 ====================

    /**
     * 每日摘要数据 - 聚合正式组合与影子研究两部分摘要。
     *
     * @param formal 正式组合摘要
     * @param shadow 影子研究摘要
     */
    public record DailySummaryData(FormalSummary formal, ShadowSummary shadow) {
    }

    /**
     * 正式组合摘要数据。
     *
     * @param occupiedSlots      占用槽位数(非AVAILABLE)
     * @param equity             组合权益(现金+预留+开放仓位投入资金)
     * @param yesterdayBuyCount  昨日买入批次数
     * @param yesterdaySellCount 昨日卖出批次数
     * @param yesterdayNetReturn 昨日已实现净收益合计
     * @param openBatchStocks    当前开放批次的股票简称列表
     * @param staleBatchCount    数据陈旧批次数
     * @param summaryDate        摘要日期
     */
    public record FormalSummary(int occupiedSlots, BigDecimal equity, int yesterdayBuyCount,
                                int yesterdaySellCount, BigDecimal yesterdayNetReturn,
                                List<String> openBatchStocks, int staleBatchCount,
                                LocalDate summaryDate) {
    }

    /**
     * 影子研究摘要数据。
     *
     * @param signalCount      原始买入信号总数
     * @param shadowNewCount   无限资金影子新批次数(portfolioDecision=SHADOW)
     * @param fullRejectCount  满仓拒绝数(rejectReason=NO_AVAILABLE_SLOT)
     * @param styleRejectCount 风格/趋势拒绝数
     * @param dynamicSellCount 动态卖出影子建议数(exitReason含DYNAMIC)
     * @param highRiskCount    高风险观察数(riskLevel=HIGH)
     */
    public record ShadowSummary(int signalCount, int shadowNewCount, int fullRejectCount,
                                int styleRejectCount, int dynamicSellCount, int highRiskCount) {
    }
}
