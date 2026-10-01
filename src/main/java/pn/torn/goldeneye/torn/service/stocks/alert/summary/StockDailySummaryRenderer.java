package pn.torn.goldeneye.torn.service.stocks.alert.summary;

import org.springframework.stereotype.Component;
import pn.torn.goldeneye.torn.service.stocks.alert.notice.StockNoticeTextFormat;
import pn.torn.goldeneye.torn.service.stocks.alert.summary.StockDailySummaryService.DailySummaryData;
import pn.torn.goldeneye.torn.service.stocks.alert.summary.StockDailySummaryService.OpenPosition;
import pn.torn.goldeneye.torn.service.stocks.alert.summary.StockDailySummaryService.PortfolioSummary;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * 股票日报渲染器 - 将 {@link DailySummaryData} 纯函数化为中文摘要文本
 * <p>
 * 本类不依赖Spring注入、DAO、时钟或序列号,只消费只读摘要数据并产生固定格式中文文本。
 * 报文按α正式组合、α影子组合的顺序拼接,两段组合字段口径一致;存量正式组合已随旧版退场,
 * 不再出现在日报中。
 * <p>
 * 数值格式固定:
 * <ul>
 *   <li>金额(组合净值、昨日已实现盈亏) -&gt; X.XXb,统一由 {@link StockNoticeTextFormat#formatBillion} 输出;</li>
 *   <li>股价(入场参考价) -&gt; 千分位保留2位小数;</li>
 *   <li>收益率 -&gt; 百分数保留2位小数并带正负号,统一由 {@link StockNoticeTextFormat#formatNetReturn} 输出。</li>
 * </ul>
 * 换行使用 {@link System#lineSeparator()},不触发任何查询或持久化。
 *
 * @author Bai
 * @version 1.6.6
 * @since 2026.08.09
 */
@Component
public class StockDailySummaryRenderer {

    /**
     * 摘要日期展示格式
     */
    private static final String SUMMARY_DATE_PATTERN = "yyyy-MM-dd";
    /**
     * 摘要标题模板
     */
    private static final String SUMMARY_TITLE_TEMPLATE = "【Stock组合日报｜%s】";
    /**
     * α正式组合区块标题模板(含槽位总数占位符)
     */
    private static final String ALPHA_SECTION_TITLE = "α 正式组合（%d槽）";
    /**
     * α影子组合区块标题模板(含槽位总数占位符)
     */
    private static final String ALPHA_SHADOW_SECTION_TITLE = "α 影子组合（仅研究 · %d槽）";
    /**
     * 组合区块模板 - 首参数为已渲染标题,其余参数为该组合的统计值
     */
    private static final String SECTION_TEMPLATE = "%s%n"
            + "- 槽位占用：%d / %d%n"
            + "- 组合净值：%s%n"
            + "- 昨日买入：%d 笔 ／ 昨日卖出：%d 笔%n"
            + "- 昨日已实现盈亏：%s（%s）%n"
            + "- 当前持仓：%s%n"
            + "- 数据陈旧批次：%d";
    /**
     * 日报免责声明
     */
    private static final String DISCLAIMER = "本日报为系统内部虚拟组合记录，不构成投资建议。";
    /**
     * 股价展示格式(千分位2位小数)
     */
    private static final String PRICE_PATTERN = "#,##0.00";
    /**
     * 收益率计算精度
     */
    private static final int RATE_SCALE = 6;
    /**
     * 权益无法计算时的展示文本
     */
    private static final String EQUITY_INSUFFICIENT = "数据不足";
    /**
     * 空集合展示文本
     */
    private static final String EMPTY_TEXT = "无";
    /**
     * 分隔符
     */
    private static final String SEPARATOR = "、";
    /**
     * 摘要日期格式化器
     */
    private static final DateTimeFormatter SUMMARY_DATE_FORMATTER =
            DateTimeFormatter.ofPattern(SUMMARY_DATE_PATTERN);

    /**
     * 构建中文摘要文本。
     *
     * @param data 摘要数据
     * @return 中文摘要文本
     */
    public String render(DailySummaryData data) {
        return String.format(SUMMARY_TITLE_TEMPLATE, data.summaryDate().format(SUMMARY_DATE_FORMATTER))
                + System.lineSeparator() + System.lineSeparator()
                + DISCLAIMER
                + System.lineSeparator() + System.lineSeparator()
                + renderSection(sectionTitle(ALPHA_SECTION_TITLE, data.alpha()), data.alpha())
                + System.lineSeparator() + System.lineSeparator()
                + renderSection(sectionTitle(ALPHA_SHADOW_SECTION_TITLE, data.alphaShadow()), data.alphaShadow());
    }

    /**
     * 渲染组合区块标题,填充该组合的槽位总数。
     *
     * @param titleTemplate 区块标题模板(含槽位总数占位符)
     * @param summary       组合摘要
     * @return 区块标题
     */
    private String sectionTitle(String titleTemplate, PortfolioSummary summary) {
        return String.format(titleTemplate, summary.slotCount());
    }

    /**
     * 渲染单个组合区块。
     *
     * @param title   已渲染的区块标题
     * @param summary 组合摘要
     * @return 区块文本
     */
    private String renderSection(String title, PortfolioSummary summary) {
        return String.format(SECTION_TEMPLATE,
                title,
                summary.occupiedSlots(), summary.slotCount(),
                formatEquity(summary),
                summary.yesterdayBuyCount(), summary.yesterdaySellCount(),
                StockNoticeTextFormat.formatBillion(summary.yesterdayProfit()), formatRate(summary),
                formatOpenPositions(summary.openPositions()),
                summary.staleBatchCount());
    }

    /**
     * 格式化组合权益,行情不足时合并展示缺失行情明细。
     *
     * @param summary 组合摘要
     * @return 权益文本
     */
    private String formatEquity(PortfolioSummary summary) {
        if (summary.equity() != null) {
            return StockNoticeTextFormat.formatBillion(summary.equity());
        }
        return EQUITY_INSUFFICIENT + "（缺失行情：" + String.join(SEPARATOR, summary.missingPriceStocks()) + "）";
    }

    /**
     * 格式化昨日已实现收益率。
     * <p>
     * 收益率为"已实现净收益金额 ÷ 投入成本";无卖出批次或无投入成本时展示 0。
     *
     * @param summary 组合摘要
     * @return 收益率文本
     */
    private String formatRate(PortfolioSummary summary) {
        BigDecimal invested = summary.yesterdayInvested();
        if (invested == null || invested.signum() == 0) {
            return StockNoticeTextFormat.formatNetReturn(BigDecimal.ZERO);
        }
        return StockNoticeTextFormat.formatNetReturn(
                summary.yesterdayProfit().divide(invested, RATE_SCALE, RoundingMode.HALF_UP));
    }

    /**
     * 格式化当前持仓(股票简称与入场参考价)。
     *
     * @param openPositions 开放仓位
     * @return 持仓文本;无持仓时为"无"
     */
    private String formatOpenPositions(List<OpenPosition> openPositions) {
        if (openPositions == null || openPositions.isEmpty()) {
            return EMPTY_TEXT;
        }
        return openPositions.stream()
                .map(position -> position.stocksShortname() + " @ " + formatPrice(position.entryReferencePrice()))
                .collect(Collectors.joining(SEPARATOR));
    }

    /**
     * 格式化股价为千分位2位小数。
     *
     * @param value 价格
     * @return 展示文本
     */
    private String formatPrice(BigDecimal value) {
        DecimalFormat formatter = new DecimalFormat(PRICE_PATTERN, DecimalFormatSymbols.getInstance(Locale.US));
        formatter.setRoundingMode(RoundingMode.HALF_UP);
        return formatter.format(value == null ? BigDecimal.ZERO : value);
    }
}
