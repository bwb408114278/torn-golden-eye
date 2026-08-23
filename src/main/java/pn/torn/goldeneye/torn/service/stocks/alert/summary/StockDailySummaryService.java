package pn.torn.goldeneye.torn.service.stocks.alert.summary;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.constants.bot.BotConstants;
import pn.torn.goldeneye.constants.torn.SettingConstants;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockNoticeAuditDO;
import pn.torn.goldeneye.torn.manager.setting.SysSettingManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import pn.torn.goldeneye.torn.service.stocks.alert.market.StockMarketClock;

/**
 * VIP股票每日摘要服务 - 每天08:30汇总正式组合与影子研究数据并发送中文摘要
 * <p>
 * 在生产环境下每日08:30(Asia/Shanghai)触发,摘要日期为发送日前一自然日。本类是纯编排入口,
 * 不含查询、计算、渲染与通知实现:
 * <ol>
 *   <li>检查 {@link SettingConstants#KEY_VIP_STOCK_DAILY_SUMMARY_ENABLED} 开关</li>
 *   <li>检查生产环境({@link BotConstants#ENV_PROD})</li>
 *   <li>通过 {@link StockMarketClock#summaryDate()} 计算摘要日期</li>
 *   <li>委托 {@link StockDailySummaryQueryService#buildSummaryData} 收集正式组合与影子数据</li>
 *   <li>委托 {@link StockDailySummaryRenderer} 构建中文摘要文本</li>
 *   <li>委托 {@link StockDailySummaryNoticeService} 写入PENDING通知审计并发送至VIP群,更新发送状态</li>
 * </ol>
 * 摘要内容覆盖正式组合(占用槽位、权益、昨日买卖、净收益、开放批次、陈旧批次)、
 * 候选影子组合(独立5槽占用、权益、买卖、净收益)与影子研究
 * (信号总数、无限资金影子新批次、满仓拒绝、风格拒绝、动态SELL研究状态、高风险观察)。
 *
 * <h3>影子分类口径</h3>
 * <ul>
 *   <li>原始买入信号 -&gt; 昨日全部信号事件数量</li>
 *   <li>无限资金影子新批次 -&gt; portfolioDecision = SHADOW 的事件数</li>
 *   <li>满仓拒绝 -&gt; rejectReason = NO_AVAILABLE_SLOT 的事件数</li>
 *   <li>风格/趋势拒绝 -&gt; rejectReason = STYLE_NOT_READY 或 portfolioDecision = REJECTED 的事件数</li>
 *   <li>动态SELL研究 -&gt; 以 torn_stock_batch_mark 为唯一数据源表达研究状态而非建议命中,
 *       公式未冻结时固定展示"规则未冻结，建议未启用"与输入覆盖率/缺失输入批次数</li>
 *   <li>高风险观察 -&gt; 昨日 riskLevel = HIGH 的影子批次数</li>
 *   <li>候选影子组合 -&gt; 独立5槽账本,占用槽位/权益/买卖/净收益单独展示,不与正式或无限资金影子合计</li>
 * </ul>
 *
 * @author Bai
 * @version 1.2.14
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

    private final StockDailySummaryQueryService queryService;
    private final StockDailySummaryRenderer renderer;
    private final StockDailySummaryNoticeService noticeService;
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
     * 通过后通过 {@link StockMarketClock#summaryDate()} 计算摘要日期,委托查询服务构建摘要数据、
     * 渲染器构建文本、通知服务写入PENDING通知审计并发送至VIP群。任一步骤异常时记录日志不中断后续调度。
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

        LocalDate summaryDate = marketClock.summaryDate();
        log.info("VIP股票每日摘要-开始执行, summaryDate={}", summaryDate);

        DailySummaryData data;
        try {
            data = buildSummaryData(summaryDate);
        } catch (Exception e) {
            log.error("VIP股票每日摘要-构建摘要数据失败, summaryDate={}", summaryDate, e);
            return;
        }

        String summaryText = buildSummaryText(data);
        TornStockNoticeAuditDO notice = noticeService.savePendingNotice(summaryDate, summaryText);
        noticeService.sendAndUpdateNotice(notice, summaryText);
    }

    /**
     * 构建每日摘要数据,包含正式组合与影子研究两部分。
     * <p>
     * 委托 {@link StockDailySummaryQueryService#buildSummaryData} 一次性读取三类只读数据:
     * 正式组合(占用槽位、组合权益、昨日买入/卖出、净收益、开放批次、陈旧批次)、
     * 候选影子组合(独立5槽占用、权益、昨日买卖与净收益)与影子研究
     * (信号统计、动态SELL研究状态与高风险观察)。
     *
     * @param summaryDate 摘要日期(发送日前一自然日)
     * @return 摘要数据对象
     */
    public DailySummaryData buildSummaryData(LocalDate summaryDate) {
        return queryService.buildSummaryData(summaryDate);
    }

    /**
     * 构建中文摘要文本。
     * <p>
     * 委托 {@link StockDailySummaryRenderer} 将只读摘要数据纯函数化为中文文本,不触发任何查询与持久化。
     *
     * @param data 摘要数据
     * @return 中文摘要文本
     */
    String buildSummaryText(DailySummaryData data) {
        return renderer.render(data);
    }

    // ==================== 值对象 ====================

    /**
     * 每日摘要数据 - 聚合正式组合、候选影子组合与影子研究三部分摘要。
     *
     * @param formal          正式组合摘要
     * @param candidateShadow 候选影子组合摘要
     * @param shadow          影子研究摘要
     */
    public record DailySummaryData(
            FormalSummary formal,
            CandidateShadowSummary candidateShadow,
            ShadowSummary shadow) {
    }

    /**
     * 正式组合摘要数据。
     *
     * @param occupiedSlots      占用槽位数(非AVAILABLE)
     * @param equity             完整组合权益；任一开放仓位缺行情时为null
     * @param cashAndReserved    可用现金与待买预留资金，不代表完整权益
     * @param missingPriceStocks 缺失有效行情的股票简称，按股票ID升序
     * @param priceAsOf          完整权益实际使用行情中的最早结束时点；行情不足时为null
     * @param yesterdayBuyCount  昨日买入批次数
     * @param yesterdaySellCount 昨日卖出批次数
     * @param yesterdayNetReturn 昨日已实现净收益合计
     * @param openBatchStocks    当前开放批次的股票简称列表
     * @param staleBatchCount    数据陈旧批次数
     * @param summaryDate        摘要日期
     */
    public record FormalSummary(
            int occupiedSlots,
            BigDecimal equity,
            BigDecimal cashAndReserved,
            List<String> missingPriceStocks,
            LocalDateTime priceAsOf,
            int yesterdayBuyCount,
            int yesterdaySellCount,
            BigDecimal yesterdayNetReturn,
            List<String> openBatchStocks,
            int staleBatchCount,
            LocalDate summaryDate) {
    }

    /**
     * 候选影子组合摘要数据。
     *
     * @param occupiedSlots      占用槽位数(非AVAILABLE)
     * @param equity             完整组合权益；任一开放仓位缺行情时为null
     * @param cashAndReserved    可用现金与待买预留资金，不代表完整权益
     * @param missingPriceStocks 缺失有效行情的股票简称，按股票ID升序
     * @param yesterdayBuyCount  昨日买入批次数
     * @param yesterdaySellCount 昨日卖出批次数
     * @param yesterdayNetReturn 昨日已实现净收益合计
     * @param openBatchStocks    当前开放批次的股票简称列表
     */
    public record CandidateShadowSummary(
            int occupiedSlots,
            BigDecimal equity,
            BigDecimal cashAndReserved,
            List<String> missingPriceStocks,
            int yesterdayBuyCount,
            int yesterdaySellCount,
            BigDecimal yesterdayNetReturn,
            List<String> openBatchStocks) {
    }

    /**
     * 影子研究摘要数据。
     *
     * @param signalCount               原始买入信号总数
     * @param shadowNewCount            无限资金影子新批次数(portfolioDecision=SHADOW)
     * @param fullRejectCount           满仓拒绝数(rejectReason=NO_AVAILABLE_SLOT)
     * @param styleRejectCount          风格/趋势拒绝数
     * @param researchMarkCount         动态SELL研究mark分母(dynamic_shadow_decision或reason非空)
     * @param completeResearchMarkCount 完整研究mark数(decision=NOT_EVALUATED且reason=DYNAMIC_RULE_NOT_FROZEN)
     * @param highRiskCount             高风险观察数(riskLevel=HIGH)
     */
    public record ShadowSummary(
            int signalCount,
            int shadowNewCount,
            int fullRejectCount,
            int styleRejectCount,
            int researchMarkCount,
            int completeResearchMarkCount,
            int highRiskCount) {
    }
}
