package pn.torn.goldeneye.torn.service.stocks.alert.settlement;

import org.springframework.stereotype.Component;
import pn.torn.goldeneye.torn.service.stocks.alert.notice.StockNoticeTextFormat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 年度报告渲染器 - 将年度结算结果纯函数化为成员可见的中文年报文本。
 *
 * <p>本类不依赖Spring注入之外的任何组件(无DAO、无时钟、不查询也不重算权益),只消费结算行派生数据与
 * 共享格式化工具,与{@link pn.torn.goldeneye.torn.service.stocks.alert.summary.StockDailySummaryRenderer}
 * 同层同风格;年报不参与BUY/SELL合并,因此不进入
 * {@link pn.torn.goldeneye.torn.service.stocks.alert.notice.StockNoticeComposeService}。
 *
 * <p>口径要点:
 * <ul>
 *   <li>金额一律 {@code X.XXb}(唯一实现 {@link StockNoticeTextFormat#formatBillion});</li>
 *   <li>收益率为带正负号的百分数(唯一实现 {@link StockNoticeTextFormat#formatNetReturn});</li>
 *   <li>年化只在试运行年度展示,完整年度省略该行(避免同一数字出现两次),样本不足时明确标注;</li>
 *   <li>术语统一为"账面利润",并保留"记账口径、不发生资金划转"的说明。</li>
 * </ul>
 *
 * @author Bai
 * @version 1.6.6
 * @since 2026.10.01
 */
@Component
public class StockAnnualSettlementRenderer {
    /**
     * 报告标题模板
     */
    private static final String TITLE_TEMPLATE = "【VIP股票 α 年度报告 · %d】";
    /**
     * 年度状态行模板(试运行,含区间起点与天数)
     */
    private static final String STATUS_PARTIAL_TEMPLATE = "年度状态：试运行（%s 起，共 %d 天）";
    /**
     * 年度状态行模板(完整年度)
     */
    private static final String STATUS_FULL_TEMPLATE = "年度状态：完整年度（%d 天）";
    /**
     * 区间收益行模板
     */
    private static final String INTERVAL_RETURN_TEMPLATE = "区间收益：%s";
    /**
     * 年化折算行模板(试运行且年化适用)
     */
    private static final String ANNUALIZED_RETURN_TEMPLATE = "年化折算：%s（试运行，仅供参考）";
    /**
     * 年化折算行文本(样本不足)
     */
    private static final String ANNUALIZED_UNAVAILABLE_TEXT = "年化折算：样本不足，不展示年化";
    /**
     * 本年账面利润行模板
     */
    private static final String YEAR_PROFIT_TEMPLATE = "本年账面利润：%s";
    /**
     * 累计账面利润行模板
     */
    private static final String CUMULATIVE_PROFIT_TEMPLATE = "累计账面利润：%s";
    /**
     * 负账面利润追加说明
     */
    private static final String NEGATIVE_PROFIT_NOTE = "注：本年账面利润为负表示账面利润被年度亏损回撤，非资金回补。";
    /**
     * 报告免责与记账口径说明
     */
    private static final String DISCLAIMER = "本报告为系统内部虚拟组合记录，不构成投资建议；"
            + "账面利润为记账口径，资金仍在槽内继续复利。";
    /**
     * 区间起点展示格式
     */
    private static final DateTimeFormatter RANGE_START_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * 构建年度报告中文本。
     *
     * @param data 年报数据
     * @return 年度报告中文本
     */
    public String render(AnnualReportData data) {
        StringBuilder text = new StringBuilder();
        text.append(String.format(TITLE_TEMPLATE, data.settleYear()))
                .append(System.lineSeparator()).append(System.lineSeparator())
                .append(buildStatusLine(data))
                .append(System.lineSeparator())
                .append(String.format(INTERVAL_RETURN_TEMPLATE, StockNoticeTextFormat.formatNetReturn(data.yearReturn())));
        if (data.partialYear()) {
            text.append(System.lineSeparator()).append(buildAnnualizedLine(data.annualizedReturn()));
        }
        text.append(System.lineSeparator()).append(System.lineSeparator())
                .append(String.format(YEAR_PROFIT_TEMPLATE, StockNoticeTextFormat.formatBillion(data.extractedAmount())))
                .append(System.lineSeparator())
                .append(String.format(CUMULATIVE_PROFIT_TEMPLATE,
                        StockNoticeTextFormat.formatBillion(data.cumulativeExtractedAfter())));
        if (data.extractedAmount() != null && data.extractedAmount().signum() < 0) {
            text.append(System.lineSeparator()).append(System.lineSeparator()).append(NEGATIVE_PROFIT_NOTE);
        }
        text.append(System.lineSeparator()).append(System.lineSeparator()).append(DISCLAIMER);
        return text.toString();
    }

    /**
     * 构建年度状态行。
     *
     * @param data 年报数据
     * @return 年度状态文本
     */
    private String buildStatusLine(AnnualReportData data) {
        if (!data.partialYear()) {
            return String.format(STATUS_FULL_TEMPLATE, data.coverageDays());
        }
        return String.format(STATUS_PARTIAL_TEMPLATE, data.rangeStartDate().format(RANGE_START_FORMATTER),
                data.coverageDays());
    }

    /**
     * 构建年化折算行,年化不适用时标注样本不足。
     *
     * @param annualizedReturn 年化收益率,可为空
     * @return 年化折算文本
     */
    private String buildAnnualizedLine(BigDecimal annualizedReturn) {
        if (annualizedReturn == null) {
            return ANNUALIZED_UNAVAILABLE_TEXT;
        }
        return String.format(ANNUALIZED_RETURN_TEMPLATE, StockNoticeTextFormat.formatNetReturn(annualizedReturn));
    }

    /**
     * 年度报告渲染数据。
     *
     * @param settleYear               被结算的自然年
     * @param partialYear              是否不完整年度(试运行)
     * @param rangeStartDate           区间起点(首笔α批次入场日)
     * @param coverageDays             区间自然日数
     * @param yearReturn               区间收益率
     * @param annualizedReturn         年化折算(不适用时为null)
     * @param extractedAmount          本年账面利润
     * @param cumulativeExtractedAfter 累计账面利润
     */
    public record AnnualReportData(int settleYear,
                                   boolean partialYear,
                                   LocalDate rangeStartDate,
                                   int coverageDays,
                                   BigDecimal yearReturn,
                                   BigDecimal annualizedReturn,
                                   BigDecimal extractedAmount,
                                   BigDecimal cumulativeExtractedAfter) {
    }
}
