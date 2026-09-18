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
import pn.torn.goldeneye.torn.service.stocks.alert.market.StockMarketClock;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * VIP股票每日摘要服务 - 每天08:10汇总α主仓、存量正式组合与α影子组合并发送中文摘要
 * <p>
 * 在生产环境下每日08:10(Asia/Shanghai)触发,摘要日期为发送日前一自然日。本类是纯编排入口,
 * 不含查询、计算、渲染与通知实现:
 * <ol>
 *   <li>检查 {@link SettingConstants#KEY_VIP_STOCK_DAILY_SUMMARY_ENABLED} 开关</li>
 *   <li>检查生产环境({@link BotConstants#ENV_PROD})</li>
 *   <li>通过 {@link StockMarketClock#summaryDate()} 计算摘要日期</li>
 *   <li>委托 {@link StockDailySummaryQueryService#buildSummaryData} 收集三段组合只读数据</li>
 *   <li>委托 {@link StockDailySummaryRenderer} 构建中文摘要文本</li>
 *   <li>委托 {@link StockDailySummaryNoticeService} 写入PENDING通知审计并发送至VIP群,更新发送状态</li>
 * </ol>
 * 摘要按组合分三段,字段口径完全一致(占用槽位、组合权益、可用现金、昨日买卖批数、
 * 昨日已实现净收益金额与收益率、当前持仓入场价、数据陈旧批次):
 * <ol>
 *   <li>α 正式组合({@code VIP_ALPHA}) - 新策略主仓;</li>
 *   <li>存量正式组合({@code VIP_FORMAL}) - 只出不进,仅按原规则退出;</li>
 *   <li>α 影子组合({@code VIP_ALPHA_SHADOW}) - 仅研究,不触真钱、不代表任何操作建议。</li>
 * </ol>
 * 已退场的信号事件统计、无限资金影子、候选影子组合、拒绝观察与动态SELL研究不再出现在日报中。
 *
 * @author Bai
 * @version 1.6.5
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
     * 每日08:10执行摘要调度(Asia/Shanghai)。
     * <p>
     * 执行前置检查:
     * <ol>
     *   <li>非生产环境直接返回</li>
     *   <li> {@link SettingConstants#KEY_VIP_STOCK_DAILY_SUMMARY_ENABLED} 开关不为 "true" 时返回</li>
     * </ol>
     * 通过后通过 {@link StockMarketClock#summaryDate()} 计算摘要日期,委托查询服务构建摘要数据、
     * 渲染器构建文本、通知服务写入PENDING通知审计并发送至VIP群。任一步骤异常时记录日志不中断后续调度。
     */
    @Scheduled(cron = "0 10 8 * * *", zone = "Asia/Shanghai")
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
     * 构建每日摘要数据,包含α正式组合、存量正式组合与α影子组合三段只读数据。
     * <p>
     * 委托 {@link StockDailySummaryQueryService#buildSummaryData} 一次性读取三段组合的
     * 槽位、活跃批次、昨日动作批次与行情,每段独立计算权益与统计,互不合计。
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
     * 每日摘要数据 - 聚合α正式组合、存量正式组合与α影子组合三段摘要。
     *
     * @param summaryDate 摘要日期
     * @param alpha       α正式组合摘要
     * @param legacy      存量正式组合摘要(只出不进)
     * @param alphaShadow α影子组合摘要(仅研究)
     */
    public record DailySummaryData(
            LocalDate summaryDate,
            PortfolioSummary alpha,
            PortfolioSummary legacy,
            PortfolioSummary alphaShadow) {
    }

    /**
     * 单个组合的摘要数据 - 三段组合共用同一字段口径。
     *
     * @param portfolioCode      组合编码
     * @param slotCount          该组合的槽位总数
     * @param occupiedSlots      占用槽位数(非AVAILABLE)
     * @param equity             完整组合权益;任一开放仓位缺行情时为null
     * @param cashAndReserved    可用现金与待买预留资金,不代表完整权益
     * @param missingPriceStocks 缺失有效行情的股票简称,按股票ID升序
     * @param priceAsOf          完整权益实际使用行情中的最早结束时点;行情不足时为null
     * @param yesterdayBuyCount  昨日买入批次数
     * @param yesterdaySellCount 昨日卖出批次数
     * @param yesterdayProfit    昨日已实现净收益金额(卖出收入-投入成本)合计
     * @param yesterdayInvested  昨日卖出批次的投入成本合计,用于派生收益率
     * @param openPositions      当前开放仓位的股票简称与入场参考价
     * @param staleBatchCount    数据陈旧批次数
     */
    public record PortfolioSummary(
            String portfolioCode,
            int slotCount,
            int occupiedSlots,
            BigDecimal equity,
            BigDecimal cashAndReserved,
            List<String> missingPriceStocks,
            LocalDateTime priceAsOf,
            int yesterdayBuyCount,
            int yesterdaySellCount,
            BigDecimal yesterdayProfit,
            BigDecimal yesterdayInvested,
            List<OpenPosition> openPositions,
            int staleBatchCount) {
    }

    /**
     * 开放仓位展示事实。
     *
     * @param stocksShortname     股票简称
     * @param entryReferencePrice 入场参考价
     */
    public record OpenPosition(
            String stocksShortname,
            BigDecimal entryReferencePrice) {
    }
}
