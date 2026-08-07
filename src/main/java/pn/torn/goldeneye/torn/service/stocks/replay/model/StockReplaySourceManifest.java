package pn.torn.goldeneye.torn.service.stocks.replay.model;

import pn.torn.goldeneye.torn.service.stocks.alert.StockHashUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 回放输入来源清单(sourceManifest) - 固化一次成功回放的全部输入证据。
 * <p>
 * 同一回放请求在不同输入代际下(如bar/feature被重建)必须可区分: {@code sha256} 是对
 * 规范化输入字段(起止时间、各类型数据时间范围、版本、行数、每股时间边界)与每类实际
 * 输入内容的确定性摘要({@code contentSha256})共同计算的摘要,摘要同时记录在
 * {@link StockReplaySummary#sourceManifest()} 中,与 {@code runId}(请求归一化键)共同构成
 * 成功完成标识。全部字段无墙钟依赖,可由相同输入复现。</p>
 *
 * @param windowRange       输入窗口时间范围(请求窗口与各类数据实际范围)
 * @param versions          数据/规则版本(bar、feature与月度状态规则版本)
 * @param barCount          加载的bar总数
 * @param featureCount      加载的策略特征总数
 * @param monthlyStateCount 加载的已确认月度状态总数
 * @param stockBoundaries   每股时间边界
 * @param contentSha256     对每类实际回放输入内容按稳定顺序计算的流式SHA-256
 * @param sha256            对规范化输入字段与内容摘要计算的SHA-256
 * @author Bai
 * @version 1.2.14
 * @since 2026.08.06
 */
public record StockReplaySourceManifest(
        WindowRange windowRange,
        Versions versions,
        long barCount,
        long featureCount,
        long monthlyStateCount,
        List<StockBoundary> stockBoundaries,
        String contentSha256,
        String sha256
) {

    /**
     * 输入窗口时间范围。
     *
     * @param requestStartTime       回放请求开始时间
     * @param requestEndTime         回放请求结束时间
     * @param barStartTime           加载bar的开始时间
     * @param barEndTime             加载bar的结束时间(含观察尾窗)
     * @param featureStartTime       加载特征的开始时间
     * @param featureEndTime         加载特征的结束时间(含观察尾窗)
     * @param monthlyStateStartMonth 加载月度状态的起始生效月份
     * @param monthlyStateEndMonth   加载月度状态的结束生效月份
     * @author Bai
     * @version 1.2.14
     * @since 2026.08.06
     */
    public record WindowRange(
            LocalDateTime requestStartTime,
            LocalDateTime requestEndTime,
            LocalDateTime barStartTime,
            LocalDateTime barEndTime,
            LocalDateTime featureStartTime,
            LocalDateTime featureEndTime,
            LocalDate monthlyStateStartMonth,
            LocalDate monthlyStateEndMonth
    ) {
    }

    /**
     * 输入数据/规则版本。
     *
     * @param barBuildVersion     bar构建规则版本
     * @param featureVersion      特征计算规则版本
     * @param monthlyRuleVersions 加载月度状态包含的规则版本组合(去重有序)
     * @author Bai
     * @version 1.2.14
     * @since 2026.08.06
     */
    public record Versions(
            String barBuildVersion,
            String featureVersion,
            List<String> monthlyRuleVersions
    ) {
    }

    /**
     * 单支股票的输入时间边界。
     *
     * @param stocksId         股票ID
     * @param firstBarTime     该股票最早bar开始时间
     * @param lastBarTime      该股票最晚bar开始时间
     * @param firstFeatureTime 该股票最早特征bar开始时间
     * @param lastFeatureTime  该股票最晚特征bar开始时间
     * @author Bai
     * @version 1.2.14
     * @since 2026.08.06
     */
    public record StockBoundary(
            Integer stocksId,
            LocalDateTime firstBarTime,
            LocalDateTime lastBarTime,
            LocalDateTime firstFeatureTime,
            LocalDateTime lastFeatureTime
    ) {
    }

    /**
     * 由规范化输入字段构建清单并计算SHA-256摘要。
     *
     * @param windowRange       输入窗口时间范围
     * @param versions          数据/规则版本
     * @param barCount          bar总数
     * @param featureCount      特征总数
     * @param monthlyStateCount 月度状态总数
     * @param stockBoundaries   每股时间边界
     * @param contentSha256     对每类实际回放输入内容按稳定顺序计算的流式SHA-256
     * @return 含摘要的清单
     */
    public static StockReplaySourceManifest of(WindowRange windowRange,
                                               Versions versions,
                                               long barCount,
                                               long featureCount,
                                               long monthlyStateCount,
                                               List<StockBoundary> stockBoundaries,
                                               String contentSha256) {
        String canonical = canonical(windowRange, versions, barCount, featureCount,
                monthlyStateCount, stockBoundaries, contentSha256);
        return new StockReplaySourceManifest(windowRange, versions, barCount, featureCount,
                monthlyStateCount, stockBoundaries, contentSha256, StockHashUtils.sha256(canonical));
    }

    private static String canonical(WindowRange w, Versions v, long barCount, long featureCount,
                                    long monthlyStateCount, List<StockBoundary> stockBoundaries,
                                    String contentSha256) {
        List<String> ruleVersions = v.monthlyRuleVersions() == null ? List.of()
                : v.monthlyRuleVersions().stream().sorted().toList();
        String base = String.join("|",
                String.valueOf(w.requestStartTime()),
                String.valueOf(w.requestEndTime()),
                String.valueOf(w.barStartTime()),
                String.valueOf(w.barEndTime()),
                String.valueOf(w.featureStartTime()),
                String.valueOf(w.featureEndTime()),
                String.valueOf(w.monthlyStateStartMonth()),
                String.valueOf(w.monthlyStateEndMonth()),
                String.valueOf(v.barBuildVersion()),
                String.valueOf(v.featureVersion()),
                String.join(",", ruleVersions),
                String.valueOf(barCount),
                String.valueOf(featureCount),
                String.valueOf(monthlyStateCount),
                contentSha256 == null ? "" : contentSha256);
        String boundaryPart = stockBoundaries.stream()
                .sorted(Comparator.comparing(StockBoundary::stocksId,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .map(b -> String.join("|",
                        String.valueOf(b.stocksId()),
                        String.valueOf(b.firstBarTime()),
                        String.valueOf(b.lastBarTime()),
                        String.valueOf(b.firstFeatureTime()),
                        String.valueOf(b.lastFeatureTime())))
                .collect(Collectors.joining(";"));
        return base + "||" + boundaryPart;
    }
}
