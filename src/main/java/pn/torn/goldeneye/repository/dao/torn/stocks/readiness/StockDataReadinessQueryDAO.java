package pn.torn.goldeneye.repository.dao.torn.stocks.readiness;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import pn.torn.goldeneye.repository.mapper.torn.stocks.readiness.StockDataReadinessQueryMapper;
import pn.torn.goldeneye.repository.model.torn.stocks.readiness.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 股票数据就绪只读查询 DAO。
 * <p>
 * 所有方法均为只读聚合查询，供本地审核报告使用；不提供任何写方法。
 *
 * @author Bai
 * @version 1.4.2
 * @since 2026.08.23
 */
@Repository
@RequiredArgsConstructor
public class StockDataReadinessQueryDAO {

    private final StockDataReadinessQueryMapper mapper;

    /**
     * 当前有效股票数量。
     *
     * @return 股票数量
     */
    public int countStocks() {
        return mapper.countStocks();
    }

    /**
     * 查询每支股票在范围内的最早/最晚分钟与有效自然分钟数。
     *
     * @param start 起始时间（含）
     * @param end   结束时间（不含）
     * @return 每支有数据股票的边界
     */
    public List<StockMinuteBoundary> selectStockMinuteBoundaries(LocalDateTime start, LocalDateTime end) {
        return mapper.selectStockMinuteBoundaries(start, end);
    }

    /**
     * 查询范围内分钟事实的来源分布。
     *
     * @param start 起始时间（含）
     * @param end   结束时间（不含）
     * @return 来源计数
     */
    public List<SourceCount> selectMinuteSourceDistribution(LocalDateTime start, LocalDateTime end) {
        return mapper.selectMinuteSourceDistribution(start, end);
    }

    /**
     * 查询范围内有效分钟事实行数。
     *
     * @param start 起始时间（含）
     * @param end   结束时间（不含）
     * @return 有效行数
     */
    public long selectValidMinuteCount(LocalDateTime start, LocalDateTime end) {
        return mapper.selectValidMinuteCount(start, end);
    }

    /**
     * 查询自然分钟重复组数。
     *
     * @param start 起始时间（含）
     * @param end   结束时间（不含）
     * @return 重复组数
     */
    public long selectDuplicateMinuteGroupCount(LocalDateTime start, LocalDateTime end) {
        return mapper.selectDuplicateMinuteGroupCount(start, end);
    }

    /**
     * 查询自然分钟重复冗余行数（组内超出首行的行数合计）。
     *
     * @param start 起始时间（含）
     * @param end   结束时间（不含）
     * @return 冗余行数
     */
    public long selectDuplicateMinuteRedundantRowCount(LocalDateTime start, LocalDateTime end) {
        return mapper.selectDuplicateMinuteRedundantRowCount(start, end);
    }

    /**
     * 查询范围内价格/总股数非法分钟行数。
     *
     * @param start 起始时间（含）
     * @param end   结束时间（不含）
     * @return 非法行数
     */
    public long selectInvalidMinuteCount(LocalDateTime start, LocalDateTime end) {
        return mapper.selectInvalidMinuteCount(start, end);
    }

    /**
     * 查询范围内分钟连续缺口汇总。
     *
     * @param start 起始时间（含）
     * @param end   结束时间（不含）
     * @return 缺口段数与最大缺口分钟数
     */
    public GapSummary selectGapSummary(LocalDateTime start, LocalDateTime end) {
        return mapper.selectGapSummary(start, end);
    }

    /**
     * 查询范围内当前版本 bar 行数。
     *
     * @param start        起始时间（含）
     * @param end          结束时间（不含）
     * @param buildVersion bar 构建版本
     * @return bar 行数
     */
    public long selectBarCount(LocalDateTime start, LocalDateTime end, String buildVersion) {
        return mapper.selectBarCount(start, end, buildVersion);
    }

    /**
     * 查询范围内当前版本可用 bar 行数。
     *
     * @param start        起始时间（含）
     * @param end          结束时间（不含）
     * @param buildVersion bar 构建版本
     * @return 可用 bar 行数
     */
    public long selectUsableBarCount(LocalDateTime start, LocalDateTime end, String buildVersion) {
        return mapper.selectUsableBarCount(start, end, buildVersion);
    }

    /**
     * 查询不可用 bar 按原因分组计数。
     *
     * @param start        起始时间（含）
     * @param end          结束时间（不含）
     * @param buildVersion bar 构建版本
     * @return 不可用原因计数
     */
    public List<NameCount> selectUnusableBarReasonCounts(LocalDateTime start, LocalDateTime end, String buildVersion) {
        return mapper.selectUnusableBarReasonCounts(start, end, buildVersion);
    }

    /**
     * 查询范围内当前版本 feature 行数。
     *
     * @param start          起始时间（含）
     * @param end            结束时间（不含）
     * @param featureVersion feature 版本
     * @return feature 行数
     */
    public long selectFeatureCount(LocalDateTime start, LocalDateTime end, String featureVersion) {
        return mapper.selectFeatureCount(start, end, featureVersion);
    }

    /**
     * 查询 usable bar 缺 feature 的数量。
     *
     * @param start          起始时间（含）
     * @param end            结束时间（不含）
     * @param buildVersion   bar 构建版本
     * @param featureVersion feature 版本
     * @return 缺 feature 的 usable bar 数
     */
    public long selectUsableBarMissingFeatureCount(LocalDateTime start, LocalDateTime end,
                                                   String buildVersion, String featureVersion) {
        return mapper.selectUsableBarMissingFeatureCount(start, end, buildVersion, featureVersion);
    }

    /**
     * 查询 feature orphan（无对应 bar）的数量。
     *
     * @param start          起始时间（含）
     * @param end            结束时间（不含）
     * @param buildVersion   bar 构建版本
     * @param featureVersion feature 版本
     * @return orphan 数
     */
    public long selectFeatureOrphanCount(LocalDateTime start, LocalDateTime end,
                                         String buildVersion, String featureVersion) {
        return mapper.selectFeatureOrphanCount(start, end, buildVersion, featureVersion);
    }

    /**
     * 查询范围内 strategyReady=true 的 feature 数量。
     *
     * @param start          起始时间（含）
     * @param end            结束时间（不含）
     * @param featureVersion feature 版本
     * @return ready 数
     */
    public long selectStrategyReadyFeatureCount(LocalDateTime start, LocalDateTime end, String featureVersion) {
        return mapper.selectStrategyReadyFeatureCount(start, end, featureVersion);
    }

    /**
     * 查询范围内 strategyReady=false 的 feature 按原因分组计数。
     *
     * @param start          起始时间（含）
     * @param end            结束时间（不含）
     * @param featureVersion feature 版本
     * @return 未就绪原因计数
     */
    public List<NameCount> selectNotReadyFeatureReasonCounts(LocalDateTime start, LocalDateTime end,
                                                             String featureVersion) {
        return mapper.selectNotReadyFeatureReasonCounts(start, end, featureVersion);
    }

    /**
     * 查询范围内月度状态分组计数。
     *
     * @param start 起始时间（含）
     * @param end   结束时间（不含）
     * @return 月度状态计数
     */
    public List<MonthlyStateCount> selectMonthlyStateCounts(LocalDateTime start, LocalDateTime end) {
        return mapper.selectMonthlyStateCounts(start, end);
    }

    /**
     * 查询 DRAFT 月度状态未完整原因汇总。
     *
     * @param start 起始时间（含）
     * @param end   结束时间（不含）
     * @return 未完整原因计数
     */
    public List<NameCount> selectMonthlyIncompleteReasonCounts(LocalDateTime start, LocalDateTime end) {
        return mapper.selectMonthlyIncompleteReasonCounts(start, end);
    }

    /**
     * 查询范围内轮次状态计数。
     *
     * @param start 起始时间（含）
     * @param end   结束时间（不含）
     * @return 轮次状态计数
     */
    public List<RoundStatusCount> selectRoundStatusCounts(LocalDateTime start, LocalDateTime end) {
        return mapper.selectRoundStatusCounts(start, end);
    }

    /**
     * 查询范围内版本不一致的轮次数。
     *
     * @param start          起始时间（含）
     * @param end            结束时间（不含）
     * @param buildVersion   bar 构建版本
     * @param featureVersion feature 版本
     * @return 版本不一致轮次数
     */
    public long selectRoundVersionMismatchCount(LocalDateTime start, LocalDateTime end,
                                                String buildVersion, String featureVersion) {
        return mapper.selectRoundVersionMismatchCount(start, end, buildVersion, featureVersion);
    }

    /**
     * 查询当前五个 VIP 股票开关只读值。
     *
     * @return 开关设置列表
     */
    public List<SettingValue> selectVipStockSettings() {
        return mapper.selectVipStockSettings();
    }
}
