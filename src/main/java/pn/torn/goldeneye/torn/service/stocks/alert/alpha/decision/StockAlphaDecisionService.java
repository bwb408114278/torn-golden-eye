package pn.torn.goldeneye.torn.service.stocks.alert.alpha.decision;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockAlphaDecisionDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockAlphaDecisionDO;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.basis.StockAlphaPriceBasis;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.basis.StockAlphaPriceBasis.StockAlphaBasisInput;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.basis.StockAlphaPriceBasisRegistry;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.config.StockAlphaRuleDefinition;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.execution.StockAlphaExecutionBarPolicy;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.market.StockAlphaDailyCloseCalculator;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.market.StockAlphaDailyCloseService;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.ranking.StockAlphaRankingCalculator;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.ranking.StockAlphaRankingResult;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.track.StockAlphaPhaseTrack;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.track.StockAlphaTrackRegistry;
import pn.torn.goldeneye.torn.service.stocks.alert.market.StockHashUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * α策略日线决策服务。
 *
 * <p>本服务只生成可追溯的日线决策记录，不创建交易批次、不扣减资金且不发送通知。</p>
 *
 * <p>时间因果:本服务只接收决策时点,执行bar起点由
 * {@link StockAlphaExecutionBarPolicy#expectedExecutionBarStart(LocalDateTime)}计算后持久化,
 * 即"决策时点所在15分钟桶 + 15分钟"。执行阶段只消费持久化执行桶,不得用轮次时间反推,
 * 也不跨桶追补;已存在决策时只复用原执行桶,不重新推导。</p>
 *
 * <p>决策bar因果:信号参考价只能来自决策时点所在桶中通过
 * {@link StockAlphaExecutionBarPolicy}校验的决策bar事实。决策bar缺失、不可用、价格非正,
 * 或与持久化执行桶不构成严格相邻关系时,本服务不落决策、不消费phase,交由后续轮次重试。</p>
 *
 * @author Bai
 * @version 1.6.5
 * @since 2026.09.05
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockAlphaDecisionService {
    /**
     * 来源快照摘要与当前日线排名快照不一致时的不可复核标记。
     */
    public static final String SOURCE_NOT_REPRODUCIBLE = "SOURCE_NOT_REPRODUCIBLE";

    private static final String PENDING_STATUS = "PENDING";

    private final StockAlphaDailyCloseService dailyCloseService;
    private final TornStockAlphaDecisionDAO decisionDao;

    /**
     * 按指定决策时点生成或读取唯一α决策。
     *
     * @param decisionDate 决策日期
     * @param decisionTime 决策时点;执行bar由该时点推导,不得直接传入执行bar
     * @param decisionBars 决策时点各股票的bar事实,用于固化信号参考价
     * @return 已持久化决策
     */
    public DecisionResult decide(LocalDate decisionDate, LocalDateTime decisionTime,
                                 Map<Integer, StockAlphaExecutionBarPolicy.DecisionBar> decisionBars) {
        return decideInternal(StockAlphaTrackRegistry.productionTrack(), decisionDate, null, null,
                decisionTime, decisionBars);
    }

    /**
     * 按指定相位轨道生成或读取该轨道唯一的α决策。
     * <p>
     * 轨道决定决策归属与决策日节奏:同一共同有效日,不同轨道按各自相位偏移判定,
     * 决策唯一键因此必须携带轨道编码,禁止跨轨道复用或覆盖决策。
     *
     * @param track           目标相位轨道
     * @param decisionDate    决策日期
     * @param currentStocksId 当前持仓股票ID
     * @param currentBatchId  当前持仓批次ID
     * @param decisionTime    决策时点;执行bar由该时点推导,不得直接传入执行bar
     * @param decisionBars    决策时点各股票的bar事实,用于固化信号参考价
     * @return 已持久化决策
     */
    public DecisionResult decide(StockAlphaPhaseTrack track, LocalDate decisionDate, Integer currentStocksId,
                                 Long currentBatchId, LocalDateTime decisionTime,
                                 Map<Integer, StockAlphaExecutionBarPolicy.DecisionBar> decisionBars) {
        return decideInternal(trackOf(track), decisionDate, currentStocksId, currentBatchId,
                decisionTime, decisionBars);
    }

    /**
     * 收敛轨道参数,空值按正式轨道处理。
     *
     * @param track 目标轨道;为空时取正式轨道
     * @return 非空轨道
     */
    private StockAlphaPhaseTrack trackOf(StockAlphaPhaseTrack track) {
        return track == null ? StockAlphaTrackRegistry.productionTrack() : track;
    }

    /**
     * 按共同有效日、持仓上下文和决策时点生成或读取唯一决策。
     * <p>
     * 决策时点由生产编排显式传入(轮次已结束bar起点),执行bar起点由
     * {@link StockAlphaExecutionBarPolicy}按"决策桶 + 15分钟"计算后持久化,
     * 执行阶段只消费该持久化值,不得再由轮次时间反推。
     * <p>
     * 为避免普通轮次产生不必要的重负载,本方法按以下顺序收敛:
     * <ol>
     *   <li>先按共同有效日廉价统计判断是否为phase边界:非决策日直接返回未就绪,
     *       不读取完整历史窗口、不排名;</li>
     *   <li>再按"决策业务日 + phase"业务键加锁读取已持久化决策:存在即复用原执行桶,
     *       不读取完整历史窗口、不重新排名、不写排名快照;</li>
     *   <li>决策时点没有任何决策bar事实时同样直接返回未就绪,不进入重负载排名;</li>
     *   <li>只有确认需要生成新决策时,才读取完整历史窗口并执行一次排名。</li>
     * </ol>
     *
     * @param track           目标相位轨道
     * @param decisionDate    决策日期
     * @param currentStocksId 当前持仓股票ID
     * @param currentBatchId  当前持仓批次ID
     * @param decisionTime    决策时点
     * @param decisionBars    决策时点各股票的bar事实
     * @return 已持久化决策
     */
    private DecisionResult decideInternal(StockAlphaPhaseTrack track, LocalDate decisionDate,
                                          Integer currentStocksId, Long currentBatchId,
                                          LocalDateTime decisionTime,
                                          Map<Integer, StockAlphaExecutionBarPolicy.DecisionBar> decisionBars) {
        Objects.requireNonNull(decisionDate, "决策日期不能为空");
        Objects.requireNonNull(decisionTime, "决策时点不能为空");
        LocalDateTime executionBar = StockAlphaExecutionBarPolicy.expectedExecutionBarStart(decisionTime);
        List<LocalDate> commonDates = dailyCloseService.commonValidDates(decisionDate);
        int commonDayCount = commonDates.size();
        if (!track.isDecisionDay(commonDayCount)) {
            return notReady(decisionDate, commonDayCount, executionBar);
        }
        int phase = track.phaseOf(commonDayCount);
        TornStockAlphaDecisionDO persisted = decisionDao.selectByBusinessKeyForUpdate(
                track.trackCode(), decisionDate, phase);
        if (persisted != null) {
            log.debug("α当前phase已存在决策,复用持久化执行桶且不重新排名: trackCode={}, decisionId={}, executionBar={}",
                    track.trackCode(), persisted.getId(), persisted.getExecutionBarStartTime());
            return toDecisionResult(persisted, List.of());
        }
        if (!StockAlphaExecutionBarPolicy.isDecisionWindowOpen(decisionTime)) {
            log.debug("α未进入决策窗口,本次不创建新决策且不消费phase: decisionDate={}, decisionTime={}",
                    decisionDate, decisionTime);
            return notReady(decisionDate, commonDayCount, executionBar);
        }
        if (decisionBars == null || decisionBars.isEmpty()) {
            log.warn("α决策时点没有决策bar事实,本次不消费phase且不排名: decisionDate={}, decisionTime={}",
                    decisionDate, decisionTime);
            return notReady(decisionDate, commonDayCount, executionBar);
        }
        Calculation calculation = calculate(track, decisionDate, currentStocksId, commonDayCount, decisionBars);
        if (!calculation.ready()) {
            return notReady(calculation, executionBar);
        }
        DecisionResult candidate = new DecisionResult(calculation.decisionDate(), true, calculation.commonDayCount(),
                calculation.rankings(), calculation.targetStocksId(), calculation.event(), phase, executionBar);
        StockAlphaExecutionBarPolicy.DecisionBar decisionBar = decisionBars.get(candidate.targetStocksId());
        if (!isUsableDecisionBar(decisionTime, decisionBar, executionBar)) {
            log.warn("α决策bar未通过连续性校验,本次不落决策且不消费phase: decisionDate={}, phase={}, "
                            + "targetStocksId={}, decisionTime={}, executionBar={}",
                    calculation.decisionDate(), phase, candidate.targetStocksId(), decisionTime, executionBar);
            return notReady(calculation, executionBar);
        }
        TornStockAlphaDecisionDO decision = toDecisionDO(candidate, currentBatchId, decisionTime,
                decisionBar.price(), calculation.observation(), track.trackCode());
        dailyCloseService.persistRankings(calculation.decisionDate(), calculation.latestCloses(),
                calculation.rankings());
        if (decisionDao.insertIgnoreConflict(decision) != 1) {
            log.warn("α决策插入未生效,本次不消费phase: decisionDate={}, phase={}",
                    calculation.decisionDate(), phase);
            return notReady(calculation, executionBar);
        }
        return toDecisionResult(decision, calculation.rankings());
    }

    /**
     * 校验决策的来源摘要与当前日线排名快照是否仍然一致。
     * <p>
     * 排名事实保存在日线快照表且按业务键UPSERT:后续补采或重建同一自然日后,
     * 回查得到的排名向量可能已变更。本方法按决策业务日重新计算排名向量与摘要并比对,
     * 不一致即判定不可复核,调用方必须fail-closed,不得把新结果冒充当时的决策事实。
     *
     * @param decision 已持久化决策
     * @return 摘要可由当前日线快照复现时返回true
     */
    public boolean isSourceReproducible(TornStockAlphaDecisionDO decision) {
        if (decision == null || decision.getSourceSnapshotDigest() == null
                || decision.getDecisionBusinessDate() == null || decision.getCommonDayIndex() == null) {
            return false;
        }
        List<StockAlphaRankingResult> rankings = rankingsOf(decision.getDecisionBusinessDate());
        if (rankings.isEmpty()) {
            return false;
        }
        return decision.getSourceSnapshotDigest().equals(
                buildSourceSnapshotDigest(decision.getDecisionBusinessDate(), decision.getCommonDayIndex(), rankings));
    }

    /**
     * 判断决策是否携带合法的决策时点参考价。
     *
     * @param decision 已持久化决策
     * @return 参考价存在且为正时返回true
     */
    public static boolean hasUsableSignalReferencePrice(TornStockAlphaDecisionDO decision) {
        BigDecimal price = decision == null ? null : decision.getSignalReferencePrice();
        return price != null && price.signum() > 0;
    }

    /**
     * 判断目标股票的决策bar事实可否固化为本次决策的信号参考价。
     * <p>
     * 校验只委托{@link StockAlphaExecutionBarPolicy}:决策bar必须位于决策时点对齐桶、
     * 满足正式可用标准且价格为正,且持久化执行桶必须是该决策bar的严格下一根bar。
     * 缺失、不可用或价格非正的bar不得用执行bar价格、成本价或0补参考价。
     *
     * @param decisionTime 决策时点
     * @param decisionBar  目标股票的决策bar事实
     * @param executionBar 按决策时点推导的执行bar起点
     * @return 决策bar合法且与执行桶严格连续时返回true
     */
    private boolean isUsableDecisionBar(LocalDateTime decisionTime,
                                        StockAlphaExecutionBarPolicy.DecisionBar decisionBar,
                                        LocalDateTime executionBar) {
        return StockAlphaExecutionBarPolicy.isUsableDecisionBar(decisionTime, decisionBar)
                && StockAlphaExecutionBarPolicy.isStrictNextBar(decisionBar.barStart(), executionBar);
    }

    /**
     * 构造不消费phase且未读取历史窗口的未就绪决策结果。
     *
     * @param decisionDate   决策日期
     * @param commonDayCount 共同有效日数量
     * @param executionBar   按决策时点推导的执行bar起点
     * @return ready=false且不携带目标与phase的决策结果
     */
    private DecisionResult notReady(LocalDate decisionDate, int commonDayCount, LocalDateTime executionBar) {
        return new DecisionResult(decisionDate, false, commonDayCount, null, null,
                StockAlphaTargetPolicy.TargetEvent.DATA_INSUFFICIENT, null, executionBar);
    }

    /**
     * 构造不消费phase的未就绪决策结果。
     *
     * @param calculation  本次计算结果
     * @param executionBar 按决策时点推导的执行bar起点
     * @return ready=false且不携带目标与phase的决策结果
     */
    private DecisionResult notReady(Calculation calculation, LocalDateTime executionBar) {
        return new DecisionResult(calculation.decisionDate(), false, calculation.commonDayCount(), null, null,
                calculation.event(), null, executionBar);
    }

    /**
     * 将决策结果转换为持久化对象。
     *
     * @param result               待持久化的决策结果;消费阶段与执行bar起点直接取自该结果,避免同值多来源
     * @param currentBatchId       当前持仓批次ID;初始入场时为空
     * @param decisionTime         决策时点,其对齐桶作为决策事实持久化
     * @param signalReferencePrice 决策时点参考价;缺失时执行阶段fail-closed
     * @param observation          观察口径结果;为空时观察列写null且不影响生产事实
     * @param trackCode            相位轨道编码,决策归属的唯一键分量
     * @return 决策持久化对象
     */
    private TornStockAlphaDecisionDO toDecisionDO(DecisionResult result, Long currentBatchId,
                                                  LocalDateTime decisionTime, BigDecimal signalReferencePrice,
                                                  Observation observation, String trackCode) {
        TornStockAlphaDecisionDO decision = new TornStockAlphaDecisionDO();
        decision.setPhaseTrackCode(trackCode);
        decision.setDecisionBusinessDate(result.decisionDate());
        decision.setCommonDayIndex(result.commonDayCount());
        decision.setPhase(result.phase());
        decision.setCurrentBatchId(currentBatchId);
        decision.setDecisionBarStartTime(StockAlphaExecutionBarPolicy.decisionBucket(decisionTime));
        decision.setExecutionBarStartTime(result.executionBarStartTime());
        decision.setDecisionType(result.event().name());
        decision.setSourceSnapshotDigest(buildSourceSnapshotDigest(result));
        decision.setSignalReferencePrice(signalReferencePrice);
        decision.setExecutionStatus(PENDING_STATUS);
        decision.setSelectedStocksId(result.targetStocksId());
        applyObservation(decision, observation);
        return decision;
    }

    /**
     * 写入观察口径列。
     * <p>
     * 观察列只供业务研究,生产目标始终读取{@code selected_stocks_id};观察口径失败时三列全部写null,
     * 不得 fail-closed 阻断生产决策,也不得用生产列冒充观察事实。
     *
     * @param decision    决策持久化对象
     * @param observation 观察口径结果;为空时不写入任何观察列
     */
    private void applyObservation(TornStockAlphaDecisionDO decision, Observation observation) {
        if (observation == null) {
            return;
        }
        decision.setAltSelectedStocksId(observation.altSelectedStocksId());
        decision.setAltSignalReferencePrice(observation.altSignalReferencePrice());
        decision.setAltSourceSnapshotDigest(observation.altSourceSnapshotDigest());
    }

    /**
     * 按决策结果构造来源快照摘要。
     *
     * @param result 决策结果
     * @return 摘要
     */
    private String buildSourceSnapshotDigest(DecisionResult result) {
        return buildSourceSnapshotDigest(result.decisionDate(), result.commonDayCount(), result.rankings());
    }

    /**
     * 按决策事实构造来源快照摘要。
     *
     * @param decisionDate   决策日期
     * @param commonDayCount 共同有效日序号
     * @param rankings       排名向量
     * @return 摘要
     */
    private String buildSourceSnapshotDigest(LocalDate decisionDate, int commonDayCount,
                                             List<StockAlphaRankingResult> rankings) {
        String source = decisionDate + "|" + commonDayCount + "|" + rankingDigestSource(rankings);
        return StockHashUtils.sha256(StockAlphaRuleDefinition.RULE_VERSION + "|" + source);
    }

    /**
     * 按观察口径构造来源摘要,供业务用同一算法重算复核。
     * <p>
     * 摘要前缀携带口径编码,与生产摘要严格区分:观察列的来源只能由观察口径复现,
     * 不得与{@code source_snapshot_digest}互相冒充。
     *
     * @param basisCode 观察口径编码
     * @param rankings  观察口径排名向量
     * @return 观察来源摘要
     */
    private String buildObservationDigest(String basisCode, List<StockAlphaRankingResult> rankings) {
        String source = basisCode + "|" + rankingDigestSource(rankings);
        return StockHashUtils.sha256(StockAlphaRuleDefinition.RULE_VERSION + "|" + source);
    }

    /**
     * 按股票ID升序序列化排名向量。
     *
     * @param rankings 排名向量
     * @return 稳定的排名摘要来源字符串
     */
    private String rankingDigestSource(List<StockAlphaRankingResult> rankings) {
        return rankings.stream().sorted(Comparator.comparing(StockAlphaRankingResult::stocksId))
                .map(ranking -> ranking.stocksId() + ":" + ranking.r20() + ":" + ranking.r1() + ":"
                        + ranking.r20Rank() + ":" + ranking.r1Rank() + ":" + ranking.alphaScore() + ":"
                        + ranking.rankPosition()).collect(Collectors.joining("|"));
    }

    /**
     * 将持久化决策转换为领域结果。
     * <p>
     * 日期、phase、目标、事件和执行桶全部取自持久化决策,不混入本次新算结果;
     * 只有新生成决策才允许携带本次计算的排名,避免两套事实混用。
     *
     * @param decision 决策持久化对象
     * @param rankings 本次计算排名;与持久化事实不对应时必须传空列表
     * @return 决策结果
     */
    private DecisionResult toDecisionResult(TornStockAlphaDecisionDO decision,
                                            List<StockAlphaRankingResult> rankings) {
        StockAlphaTargetPolicy.TargetEvent event = StockAlphaTargetPolicy.TargetEvent.valueOf(decision.getDecisionType());
        return new DecisionResult(decision.getDecisionBusinessDate(), true,
                decision.getCommonDayIndex(), rankings, decision.getSelectedStocksId(), event,
                decision.getPhase(), decision.getExecutionBarStartTime());
    }

    /**
     * 计算指定日期的α决策。
     * <p>
     * 只读取已完整持久化的最近排名窗口日线快照并执行纯内存排名,不重写历史快照。
     * 共同有效日序号由调用方按全量共同有效日统计传入,不得以排名窗口内的天数代替,
     * 否则phase会因周末和节假日漂移。
     *
     * @param track           目标相位轨道
     * @param decisionDate    决策日期
     * @param currentStocksId 当前持仓股票ID
     * @param commonDayCount  决策日期对应的共同有效日序号
     * @param decisionBars    决策时点各股票的决策bar事实;仅观察口径消费
     * @return 计算结果
     */
    private Calculation calculate(StockAlphaPhaseTrack track, LocalDate decisionDate, Integer currentStocksId,
                                  int commonDayCount,
                                  Map<Integer, StockAlphaExecutionBarPolicy.DecisionBar> decisionBars) {
        Map<LocalDate, Map<Integer, StockAlphaDailyCloseCalculator.CloseResult>> daily =
                dailyCloseService.loadDailyCloses(decisionDate);
        List<LocalDate> rankingDates = completeDates(daily);
        if (rankingDates.size() < StockAlphaRuleDefinition.RANKING_MIN_COMMON_DAYS) {
            return new Calculation(decisionDate, false, commonDayCount, daily, List.of(), null,
                    StockAlphaTargetPolicy.TargetEvent.DATA_INSUFFICIENT, null);
        }
        StockAlphaBasisInput basisInput = new StockAlphaBasisInput(rankingDates, daily, decisionBars);
        List<StockAlphaRankingResult> rankings = rank(basisInput, StockAlphaPriceBasisRegistry.productionBasis());
        StockAlphaTargetPolicy.TargetResult target =
                StockAlphaTargetPolicy.decide(track, commonDayCount, rankings, currentStocksId);
        return new Calculation(rankingDates.getLast(), true, commonDayCount, daily, rankings,
                target.targetStocksId(), target.event(), observe(basisInput));
    }

    /**
     * 按观察口径计算观察目标。
     * <p>
     * 观察口径与生产口径复用同一个{@link StockAlphaRankingCalculator},本方法只切换输入序列来源。
     * 观察口径失败(决策bar缺失、价格非正或序列不完整)时记录日志并返回null,
     * 由持久化层把观察列写空:观察失败绝不影响生产目标、下单、通知内容与发送时刻。
     *
     * @param basisInput 口径输入
     * @return 观察口径结果;无法形成完整观察序列时返回null
     */
    private Observation observe(StockAlphaBasisInput basisInput) {
        try {
            StockAlphaPriceBasis basis = StockAlphaPriceBasisRegistry.observationBasis();
            List<StockAlphaRankingResult> rankings = rank(basisInput, basis);
            if (rankings.isEmpty()) {
                log.warn("α观察口径序列不完整,本次观察列写null且不影响生产决策: basisCode={}, rankingDates={}",
                        basis.code(), basisInput.rankingDates().size());
                return null;
            }
            Integer targetStocksId = rankings.getFirst().stocksId();
            StockAlphaExecutionBarPolicy.DecisionBar bar = basisInput.decisionBars().get(targetStocksId);
            return new Observation(targetStocksId, bar.price(), buildObservationDigest(basis.code(), rankings));
        } catch (Exception e) {
            log.warn("α观察口径计算失败,本次观察列写null且不影响生产决策: rankingDates={}",
                    basisInput.rankingDates().size(), e);
            return null;
        }
    }

    /**
     * 按决策业务日重新计算当前日线快照对应的排名向量。
     *
     * @param decisionDate 决策业务日
     * @return 排名向量;数据不足时返回空列表
     */
    private List<StockAlphaRankingResult> rankingsOf(LocalDate decisionDate) {
        Map<LocalDate, Map<Integer, StockAlphaDailyCloseCalculator.CloseResult>> daily =
                dailyCloseService.loadDailyCloses(decisionDate);
        List<LocalDate> rankingDates = completeDates(daily);
        if (rankingDates.size() < StockAlphaRuleDefinition.RANKING_MIN_COMMON_DAYS) {
            return List.of();
        }
        return rank(new StockAlphaBasisInput(rankingDates, daily, Map.of()),
                StockAlphaPriceBasisRegistry.productionBasis());
    }

    /**
     * 筛选成员完整的排名日期。
     *
     * @param daily 日线收盘数据
     * @return 升序排名日期
     */
    private List<LocalDate> completeDates(
            Map<LocalDate, Map<Integer, StockAlphaDailyCloseCalculator.CloseResult>> daily) {
        return daily.entrySet().stream()
                .filter(entry -> entry.getValue().size() == StockAlphaRuleDefinition.MEMBER_COUNT
                        && entry.getValue().values().stream().allMatch(Objects::nonNull))
                .map(Map.Entry::getKey).sorted().toList();
    }

    /**
     * 按指定口径执行唯一排名实现。
     * <p>
     * 全部口径共用{@link StockAlphaRankingCalculator}:本方法只负责把口径输出的收盘序列交给它,
     * 不得复制第二份收益率或归一化排名实现。
     *
     * @param basisInput 口径输入
     * @param basis      口径实现
     * @return 排名向量;口径无法形成完整序列(返回空映射)时返回空列表
     */
    private List<StockAlphaRankingResult> rank(StockAlphaBasisInput basisInput, StockAlphaPriceBasis basis) {
        Map<Integer, List<BigDecimal>> closeSeries = basis.closeSeries(basisInput);
        return closeSeries.isEmpty() ? List.of() : StockAlphaRankingCalculator.calculate(closeSeries);
    }

    /**
     * α日线决策结果。
     *
     * @param decisionDate          决策日期
     * @param ready                 是否满足决策前置条件
     * @param commonDayCount        共同有效日数量
     * @param rankings              排名结果;复用已持久化决策时为空列表
     * @param targetStocksId        目标股票ID
     * @param event                 目标事件
     * @param phase                 消费阶段
     * @param executionBarStartTime 由决策时点推导并持久化的执行bar起点
     */
    public record DecisionResult(
            LocalDate decisionDate,
            boolean ready,
            int commonDayCount,
            List<StockAlphaRankingResult> rankings,
            Integer targetStocksId,
            StockAlphaTargetPolicy.TargetEvent event,
            Integer phase,
            LocalDateTime executionBarStartTime) {
    }

    /**
     * 单次决策的纯计算结果。
     *
     * @param decisionDate   决策日期(最后共同有效日)
     * @param ready          是否满足决策前置条件
     * @param commonDayCount 共同有效日数量
     * @param daily          已读取的日线收盘数据
     * @param rankings       排名结果
     * @param targetStocksId 目标股票ID
     * @param event          目标事件
     * @param observation    观察口径结果;生产目标不消费
     */
    private record Calculation(
            LocalDate decisionDate,
            boolean ready,
            int commonDayCount,
            Map<LocalDate, Map<Integer, StockAlphaDailyCloseCalculator.CloseResult>> daily,
            List<StockAlphaRankingResult> rankings,
            Integer targetStocksId,
            StockAlphaTargetPolicy.TargetEvent event,
            Observation observation) {
        /**
         * 返回决策日期当天的收盘结果,供排名持久化使用。
         *
         * @return 决策日期收盘结果;不存在时为空
         */
        private Map<Integer, StockAlphaDailyCloseCalculator.CloseResult> latestCloses() {
            return daily == null ? Map.of() : daily.getOrDefault(decisionDate, Map.of());
        }
    }

    /**
     * 观察口径结果 - 只在决策落库时写入观察列。
     * <p>
     * 观察列只写不读:生产目标、下单、通知内容与发送时刻全部只取生产列,
     * 全仓不存在消费{@code alt_*}列的生产代码。
     *
     * @param altSelectedStocksId     观察口径目标股票ID(Top1)
     * @param altSignalReferencePrice 观察口径参考价(决策桶现价)
     * @param altSourceSnapshotDigest 观察口径来源摘要,可按键序重算复核
     */
    private record Observation(
            Integer altSelectedStocksId,
            BigDecimal altSignalReferencePrice,
            String altSourceSnapshotDigest) {
    }
}
