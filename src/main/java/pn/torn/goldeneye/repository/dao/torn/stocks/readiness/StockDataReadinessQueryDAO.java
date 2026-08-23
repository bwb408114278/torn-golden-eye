package pn.torn.goldeneye.repository.dao.torn.stocks.readiness;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import pn.torn.goldeneye.repository.mapper.torn.stocks.readiness.StockDataReadinessQueryMapper;
import pn.torn.goldeneye.repository.model.torn.stocks.readiness.GapSummary;
import pn.torn.goldeneye.repository.model.torn.stocks.readiness.MonthlyStateCount;
import pn.torn.goldeneye.repository.model.torn.stocks.readiness.NameCount;
import pn.torn.goldeneye.repository.model.torn.stocks.readiness.RoundStatusCount;
import pn.torn.goldeneye.repository.model.torn.stocks.readiness.SettingValue;
import pn.torn.goldeneye.repository.model.torn.stocks.readiness.SourceCount;
import pn.torn.goldeneye.repository.model.torn.stocks.readiness.StockMinuteBoundary;

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

    public int countStocks() {
        return mapper.countStocks();
    }

    public List<StockMinuteBoundary> selectStockMinuteBoundaries(LocalDateTime start, LocalDateTime end) {
        return mapper.selectStockMinuteBoundaries(start, end);
    }

    public List<SourceCount> selectMinuteSourceDistribution(LocalDateTime start, LocalDateTime end) {
        return mapper.selectMinuteSourceDistribution(start, end);
    }

    public long selectValidMinuteCount(LocalDateTime start, LocalDateTime end) {
        return mapper.selectValidMinuteCount(start, end);
    }

    public long selectDuplicateMinuteGroupCount(LocalDateTime start, LocalDateTime end) {
        return mapper.selectDuplicateMinuteGroupCount(start, end);
    }

    public long selectDuplicateMinuteRedundantRowCount(LocalDateTime start, LocalDateTime end) {
        return mapper.selectDuplicateMinuteRedundantRowCount(start, end);
    }

    public long selectInvalidMinuteCount(LocalDateTime start, LocalDateTime end) {
        return mapper.selectInvalidMinuteCount(start, end);
    }

    public GapSummary selectGapSummary(LocalDateTime start, LocalDateTime end) {
        return mapper.selectGapSummary(start, end);
    }

    public long selectBarCount(LocalDateTime start, LocalDateTime end, String buildVersion) {
        return mapper.selectBarCount(start, end, buildVersion);
    }

    public long selectUsableBarCount(LocalDateTime start, LocalDateTime end, String buildVersion) {
        return mapper.selectUsableBarCount(start, end, buildVersion);
    }

    public List<NameCount> selectUnusableBarReasonCounts(LocalDateTime start, LocalDateTime end, String buildVersion) {
        return mapper.selectUnusableBarReasonCounts(start, end, buildVersion);
    }

    public long selectFeatureCount(LocalDateTime start, LocalDateTime end, String featureVersion) {
        return mapper.selectFeatureCount(start, end, featureVersion);
    }

    public long selectUsableBarMissingFeatureCount(LocalDateTime start, LocalDateTime end,
                                                   String buildVersion, String featureVersion) {
        return mapper.selectUsableBarMissingFeatureCount(start, end, buildVersion, featureVersion);
    }

    public long selectFeatureOrphanCount(LocalDateTime start, LocalDateTime end,
                                         String buildVersion, String featureVersion) {
        return mapper.selectFeatureOrphanCount(start, end, buildVersion, featureVersion);
    }

    public long selectStrategyReadyFeatureCount(LocalDateTime start, LocalDateTime end, String featureVersion) {
        return mapper.selectStrategyReadyFeatureCount(start, end, featureVersion);
    }

    public List<NameCount> selectNotReadyFeatureReasonCounts(LocalDateTime start, LocalDateTime end,
                                                             String featureVersion) {
        return mapper.selectNotReadyFeatureReasonCounts(start, end, featureVersion);
    }

    public List<MonthlyStateCount> selectMonthlyStateCounts(LocalDateTime start, LocalDateTime end) {
        return mapper.selectMonthlyStateCounts(start, end);
    }

    public List<NameCount> selectMonthlyIncompleteReasonCounts(LocalDateTime start, LocalDateTime end) {
        return mapper.selectMonthlyIncompleteReasonCounts(start, end);
    }

    public List<RoundStatusCount> selectRoundStatusCounts(LocalDateTime start, LocalDateTime end) {
        return mapper.selectRoundStatusCounts(start, end);
    }

    public long selectRoundVersionMismatchCount(LocalDateTime start, LocalDateTime end,
                                                String buildVersion, String featureVersion) {
        return mapper.selectRoundVersionMismatchCount(start, end, buildVersion, featureVersion);
    }

    public List<SettingValue> selectVipStockSettings() {
        return mapper.selectVipStockSettings();
    }
}
