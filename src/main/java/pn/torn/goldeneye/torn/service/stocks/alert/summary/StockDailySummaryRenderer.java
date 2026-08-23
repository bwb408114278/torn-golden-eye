package pn.torn.goldeneye.torn.service.stocks.alert.summary;

import org.springframework.stereotype.Component;
import pn.torn.goldeneye.torn.service.stocks.alert.summary.StockDailySummaryService.CandidateShadowSummary;
import pn.torn.goldeneye.torn.service.stocks.alert.summary.StockDailySummaryService.DailySummaryData;
import pn.torn.goldeneye.torn.service.stocks.alert.summary.StockDailySummaryService.FormalSummary;
import pn.torn.goldeneye.torn.service.stocks.alert.summary.StockDailySummaryService.ShadowSummary;

import java.time.format.DateTimeFormatter;
import pn.torn.goldeneye.torn.service.stocks.alert.portfolio.StockPortfolioService;

/**
 * 股票日报渲染器 - 将 {@link DailySummaryData} 纯函数化为中文摘要文本
 * <p>
 * 本类不依赖Spring注入、DAO、时钟或序列号,只消费只读摘要数据并产生固定格式中文文本。
 * 按技术方案第12.5节格式拼接:标题、正式组合区块、候选影子组合区块、影子研究区块、免责声明;
 * 换行使用 {@code %n} 与 {@link System#lineSeparator()},不触发任何查询或持久化。
 *
 * @author Bai
 * @version 1.2.14
 * @since 2026.08.09
 */
@Component
public class StockDailySummaryRenderer {

    /**
     * 摘要日期展示格式
     */
    private static final String SUMMARY_DATE_PATTERN = "yyyy-MM-dd";
    /**
     * 动态SELL研究固定展示文本(公式冻结前建议未启用)
     */
    private static final String DYNAMIC_SELL_RESEARCH_NOT_FROZEN = "动态SELL研究：规则未冻结，建议未启用";
    /**
     * 动态SELL研究无研究输入展示文本
     */
    private static final String DYNAMIC_SELL_RESEARCH_NO_INPUT = "动态SELL研究：无研究输入";
    /**
     * 摘要标题模板
     */
    private static final String SUMMARY_TITLE_TEMPLATE = "【VIP股票组合日报｜%s】";
    /**
     * 影子研究免责声明
     */
    private static final String SHADOW_DISCLAIMER = "提示：影子数据仅用于策略研究，不代表正式操作建议。";
    /**
     * 摘要日期格式化器
     */
    private static final DateTimeFormatter SUMMARY_DATE_FORMATTER =
            DateTimeFormatter.ofPattern(SUMMARY_DATE_PATTERN);

    /**
     * 构建中文摘要文本。
     * <p>
     * 按技术方案第12.5节格式拼接:标题、正式组合区块、候选影子组合区块、影子研究区块、免责声明。
     *
     * @param data 摘要数据
     * @return 中文摘要文本
     */
    public String render(DailySummaryData data) {
        FormalSummary formal = data.formal();
        CandidateShadowSummary candidateShadow = data.candidateShadow();
        ShadowSummary shadow = data.shadow();
        String dateText = formal.summaryDate().format(SUMMARY_DATE_FORMATTER);
        String openStocks = formal.openBatchStocks().isEmpty()
                ? "无" : String.join("、", formal.openBatchStocks());
        String candidateOpenStocks = candidateShadow.openBatchStocks().isEmpty()
                ? "无" : String.join("、", candidateShadow.openBatchStocks());
        String dynamicSellText = buildDynamicSellResearchText(
                shadow.researchMarkCount(), shadow.completeResearchMarkCount());

        return String.format(
                SUMMARY_TITLE_TEMPLATE + "%n%n正式组合%n- 当前占用槽位：%d / %d%n"
                        + "- 当前组合权益：%s%n%s- 昨日买入：%d批%n- 昨日卖出：%d批%n"
                        + "- 昨日已实现净收益：%s%n- 当前开放批次：%s%n- 数据陈旧批次：%d%n%n"
                        + "候选影子组合%n- 当前占用槽位：%d / %d%n"
                        + "- 当前组合权益：%s%n%s- 昨日买入：%d批%n- 昨日卖出：%d批%n"
                        + "- 昨日已实现净收益：%s%n- 当前开放批次：%s%n%n"
                        + "影子研究%n- 原始买入信号：%d个%n- 无限资金影子新批次：%d个%n"
                        + "- 满仓拒绝：%d个%n- 风格/趋势拒绝：%d个%n- %s%n"
                        + "- 高风险观察：%d个%n%n%s",
                dateText,
                formal.occupiedSlots(), StockPortfolioService.SLOT_COUNT,
                formatEquity(formal),
                formatDataInsufficientDetails(formal),
                formal.yesterdayBuyCount(), formal.yesterdaySellCount(),
                formal.yesterdayNetReturn().toPlainString(),
                openStocks, formal.staleBatchCount(),
                candidateShadow.occupiedSlots(), StockPortfolioService.SLOT_COUNT,
                formatCandidateShadowEquity(candidateShadow),
                formatCandidateShadowInsufficientDetails(candidateShadow),
                candidateShadow.yesterdayBuyCount(), candidateShadow.yesterdaySellCount(),
                candidateShadow.yesterdayNetReturn().toPlainString(),
                candidateOpenStocks,
                shadow.signalCount(), shadow.shadowNewCount(),
                shadow.fullRejectCount(), shadow.styleRejectCount(),
                dynamicSellText, shadow.highRiskCount(),
                SHADOW_DISCLAIMER
        );
    }

    /**
     * 构建动态SELL研究展示文本。
     * <p>
     * 公式冻结前固定输出"动态SELL研究：规则未冻结，建议未启用";
     * 仅当存在研究mark(分母&gt;0)时追加"输入覆盖率：xx%"与"缺失输入批次数：N"。
     * 分母为0时展示"无研究输入",不得展示伪造的0%。
     *
     * @param researchMarkCount         研究mark分母
     * @param completeResearchMarkCount 完整研究mark数
     * @return 动态SELL研究展示文本
     */
    private String buildDynamicSellResearchText(int researchMarkCount, int completeResearchMarkCount) {
        if (researchMarkCount <= 0) {
            return DYNAMIC_SELL_RESEARCH_NO_INPUT;
        }
        int missingCount = researchMarkCount - completeResearchMarkCount;
        long coveragePercent = Math.round(completeResearchMarkCount * 100.0 / researchMarkCount);
        return DYNAMIC_SELL_RESEARCH_NOT_FROZEN + System.lineSeparator()
                + "- 输入覆盖率：" + coveragePercent + "%" + System.lineSeparator()
                + "- 缺失输入批次数：" + missingCount;
    }

    /**
     * 格式化候选影子组合权益，行情不足时明确展示数据不足。
     *
     * @param candidateShadow 候选影子组合摘要
     * @return 权益文本
     */
    private String formatCandidateShadowEquity(CandidateShadowSummary candidateShadow) {
        return candidateShadow.equity() == null
                ? "暂无法计算（行情数据不足）" : candidateShadow.equity().toPlainString();
    }

    /**
     * 构建候选影子组合行情不足时必须展示的缺失股票与现金明细。
     *
     * @param candidateShadow 候选影子组合摘要
     * @return 行情完整时为空字符串，缺失时返回两行详情
     */
    private String formatCandidateShadowInsufficientDetails(CandidateShadowSummary candidateShadow) {
        if (candidateShadow.equity() != null) {
            return "";
        }
        return "- 缺失行情：" + String.join("、", candidateShadow.missingPriceStocks()) + System.lineSeparator()
                + "- 可用现金及预留资金：" + candidateShadow.cashAndReserved().toPlainString()
                + System.lineSeparator();
    }

    /**
     * 格式化日报权益，行情不足时明确展示数据不足。
     *
     * @param formal 正式组合摘要
     * @return 权益文本
     */
    private String formatEquity(FormalSummary formal) {
        return formal.equity() == null ? "暂无法计算（行情数据不足）" : formal.equity().toPlainString();
    }

    /**
     * 构建行情不足时必须展示的缺失股票与现金明细。
     *
     * @param formal 正式组合摘要
     * @return 行情完整时为空字符串，缺失时返回两行详情
     */
    private String formatDataInsufficientDetails(FormalSummary formal) {
        if (formal.equity() != null) {
            return "";
        }
        return "- 缺失行情：" + String.join("、", formal.missingPriceStocks()) + System.lineSeparator()
                + "- 可用现金及预留资金：" + formal.cashAndReserved().toPlainString() + System.lineSeparator();
    }
}
