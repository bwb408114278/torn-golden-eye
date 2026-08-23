package pn.torn.goldeneye.repository.mapper.torn.stocks.readiness;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import pn.torn.goldeneye.repository.model.torn.stocks.readiness.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 股票数据就绪只读查询 Mapper。
 * <p>
 * 只用于本地订阅库的人工/运维审核，不写生产业务表。所有查询必须显式带逻辑删除、
 * 版本与范围条件；不在生产每分钟调度中调用。
 *
 * @author Bai
 * @version 1.4.2
 * @since 2026.08.23
 */
@Mapper
public interface StockDataReadinessQueryMapper {

    /**
     * 当前有效股票数量。
     *
     * @return 股票数量
     */
    int countStocks();

    /**
     * 查询所有当前有效股票的分钟覆盖汇总。
     *
     * @param startInclusive 起始时间（含，必须整分钟）
     * @param endExclusive   结束时间（不含，必须整分钟）
     * @return 全股票覆盖汇总
     */
    List<StockMinuteCoverage> selectMinuteCoverageSummary(@Param("startInclusive") LocalDateTime startInclusive,
                                                         @Param("endExclusive") LocalDateTime endExclusive);

    /**
     * 查询范围内分钟事实的来源分布。
     *
     * @param startInclusive 起始时间（含）
     * @param endExclusive   结束时间（不含）
     * @return 来源计数
     */
    List<SourceCount> selectMinuteSourceDistribution(@Param("startInclusive") LocalDateTime startInclusive,
                                                     @Param("endExclusive") LocalDateTime endExclusive);

    /**
     * 查询范围内有效分钟事实行数。
     *
     * @param startInclusive 起始时间（含）
     * @param endExclusive   结束时间（不含）
     * @return 有效行数
     */
    long selectValidMinuteCount(@Param("startInclusive") LocalDateTime startInclusive,
                                @Param("endExclusive") LocalDateTime endExclusive);

    /**
     * 查询范围内价格/总股数非法分钟行数。
     *
     * @param startInclusive 起始时间（含）
     * @param endExclusive   结束时间（不含）
     * @return 非法行数
     */
    long selectInvalidMinuteCount(@Param("startInclusive") LocalDateTime startInclusive,
                                  @Param("endExclusive") LocalDateTime endExclusive);

    /**
     * 查询范围内当前版本 bar 行数。
     *
     * @param startInclusive 起始时间（含）
     * @param endExclusive   结束时间（不含）
     * @param buildVersion   bar 构建版本
     * @return bar 行数
     */
    long selectBarCount(@Param("startInclusive") LocalDateTime startInclusive,
                        @Param("endExclusive") LocalDateTime endExclusive,
                        @Param("buildVersion") String buildVersion);

    /**
     * 查询范围内当前版本可用 bar 行数。
     *
     * @param startInclusive 起始时间（含）
     * @param endExclusive   结束时间（不含）
     * @param buildVersion   bar 构建版本
     * @return 可用 bar 行数
     */
    long selectUsableBarCount(@Param("startInclusive") LocalDateTime startInclusive,
                              @Param("endExclusive") LocalDateTime endExclusive,
                              @Param("buildVersion") String buildVersion);

    /**
     * 查询不可用 bar 按原因分组计数。
     *
     * @param startInclusive 起始时间（含）
     * @param endExclusive   结束时间（不含）
     * @param buildVersion   bar 构建版本
     * @return 不可用原因计数
     */
    List<NameCount> selectUnusableBarReasonCounts(@Param("startInclusive") LocalDateTime startInclusive,
                                                  @Param("endExclusive") LocalDateTime endExclusive,
                                                  @Param("buildVersion") String buildVersion);

    /**
     * 查询范围内当前版本 feature 行数。
     *
     * @param startInclusive 起始时间（含）
     * @param endExclusive   结束时间（不含）
     * @param featureVersion feature 版本
     * @return feature 行数
     */
    long selectFeatureCount(@Param("startInclusive") LocalDateTime startInclusive,
                            @Param("endExclusive") LocalDateTime endExclusive,
                            @Param("featureVersion") String featureVersion);

    /**
     * 查询 usable bar 缺 feature 的数量。
     *
     * @param startInclusive 起始时间（含）
     * @param endExclusive   结束时间（不含）
     * @param buildVersion   bar 构建版本
     * @param featureVersion feature 版本
     * @return 缺 feature 的 usable bar 数
     */
    long selectUsableBarMissingFeatureCount(@Param("startInclusive") LocalDateTime startInclusive,
                                            @Param("endExclusive") LocalDateTime endExclusive,
                                            @Param("buildVersion") String buildVersion,
                                            @Param("featureVersion") String featureVersion);

    /**
     * 查询 feature orphan（无对应 bar）的数量。
     *
     * @param startInclusive 起始时间（含）
     * @param endExclusive   结束时间（不含）
     * @param buildVersion   bar 构建版本
     * @param featureVersion feature 版本
     * @return orphan 数
     */
    long selectFeatureOrphanCount(@Param("startInclusive") LocalDateTime startInclusive,
                                  @Param("endExclusive") LocalDateTime endExclusive,
                                  @Param("buildVersion") String buildVersion,
                                  @Param("featureVersion") String featureVersion);

    /**
     * 查询范围内 strategyReady=true 的 feature 数量。
     *
     * @param startInclusive 起始时间（含）
     * @param endExclusive   结束时间（不含）
     * @param featureVersion feature 版本
     * @return ready 数
     */
    long selectStrategyReadyFeatureCount(@Param("startInclusive") LocalDateTime startInclusive,
                                         @Param("endExclusive") LocalDateTime endExclusive,
                                         @Param("featureVersion") String featureVersion);

    /**
     * 查询范围内 strategyReady=false 的 feature 按原因分组计数。
     *
     * @param startInclusive 起始时间（含）
     * @param endExclusive   结束时间（不含）
     * @param featureVersion feature 版本
     * @return 未就绪原因计数
     */
    List<NameCount> selectNotReadyFeatureReasonCounts(@Param("startInclusive") LocalDateTime startInclusive,
                                                      @Param("endExclusive") LocalDateTime endExclusive,
                                                      @Param("featureVersion") String featureVersion);

    /**
     * 查询 DRAFT 月度状态未完整原因汇总。
     *
     * @param startInclusive 起始时间（含）
     * @param endExclusive   结束时间（不含）
     * @return 未完整原因计数
     */
    List<NameCount> selectMonthlyIncompleteReasonCounts(@Param("startInclusive") LocalDateTime startInclusive,
                                                        @Param("endExclusive") LocalDateTime endExclusive);

    /**
     * 查询范围内月度状态分组计数。
     *
     * @param startInclusive 起始时间（含）
     * @param endExclusive   结束时间（不含）
     * @return 月度状态计数
     */
    List<MonthlyStateCount> selectMonthlyStateCounts(@Param("startInclusive") LocalDateTime startInclusive,
                                                     @Param("endExclusive") LocalDateTime endExclusive);

    /**
     * 查询范围内轮次状态计数。
     *
     * @param startInclusive 起始时间（含）
     * @param endExclusive   结束时间（不含）
     * @return 轮次状态计数
     */
    List<RoundStatusCount> selectRoundStatusCounts(@Param("startInclusive") LocalDateTime startInclusive,
                                                   @Param("endExclusive") LocalDateTime endExclusive);

    /**
     * 查询范围内版本不一致的轮次数。
     *
     * @param startInclusive 起始时间（含）
     * @param endExclusive   结束时间（不含）
     * @param buildVersion   bar 构建版本
     * @param featureVersion feature 版本
     * @return 版本不一致轮次数
     */
    long selectRoundVersionMismatchCount(@Param("startInclusive") LocalDateTime startInclusive,
                                         @Param("endExclusive") LocalDateTime endExclusive,
                                         @Param("buildVersion") String buildVersion,
                                         @Param("featureVersion") String featureVersion);

    /**
     * 查询当前五个 VIP 股票开关只读值。
     *
     * @return 开关设置列表
     */
    List<SettingValue> selectVipStockSettings();
}
