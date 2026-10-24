package pn.torn.goldeneye.torn.service.faction.oc.recommend.metric.flow;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import pn.torn.goldeneye.constants.torn.enums.TornOcStatusEnum;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcSlotDAO;
import pn.torn.goldeneye.repository.model.faction.oc.OcRankNameKey;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcFlowNodeDO;
import pn.torn.goldeneye.torn.manager.setting.TornSettingOcFlowManager;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.flow.*;
import pn.torn.goldeneye.torn.service.faction.oc.planning.replay.OcPlannerReplayTestConfiguration;
import pn.torn.goldeneye.torn.service.faction.oc.planning.replay.OcPlanningReadOnlyGuard;
import pn.torn.goldeneye.torn.service.faction.oc.recommend.rank.OcRecommendTunings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OC流程图成功率的真实历史回测门禁。
 *
 * <p>用途：B1交付的流程图求值器在接入推荐之前，先用真实完成的OC做一次可复算的取证，
 * 回答「由岗位实测成功率推出来的OC成功率，是否比等级阶梯（7级0.811、8级0.754）更能解释结果」。
 * 它不是回归测试，只在显式开启{@code -Doc.flow.backtest.enabled=true}时执行，正常构建与CI不会运行。</p>
 *
 * <p>门禁只覆盖等级7与8（历史样本足够），等级9与10只输出观察值、不参与断言：
 * ①Brier分数相对等级阶梯的劣化不超过+0.002；②十分位校准分档的平均偏差不超过5个点、最差档不超过7个点；
 * ③按预测成功率三分档后实际成功率保持单调（高≥中≥低）。</p>
 *
 * <p>预测口径：该OC执行时各岗位的实测pass_rate即判定成功率；参与者岗位在槽位表里找不到时退回
 * 该OC自身槽位成功率的均值，并把缺失条数作为回测前提断言为0——历史实测不完整就不谈回测结论。
 * 岗位编码在流程图配置与槽位表之间存在空白差异（配置写CatBurglar#1、槽位表写Cat Burglar#1），
 * 因此两侧统一去掉空白与非字母数字后再比对。</p>
 *
 * <p>数据整洁性：全程只读，读取放在{@link OcPlanningReadOnlyGuard}的
 * REQUIRES_NEW + READ ONLY + REPEATABLE READ事务内，OC与槽位在同一次快照内读取。</p>
 *
 * <p>明细写入{@code target/oc-flow-backtest-<时间戳>.txt}：每次运行各留一份，不复写历史证据。</p>
 *
 * @author Bai
 * @version 1.9.3
 * @since 2026.10.24
 */
@Slf4j
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = {OcPlannerReplayTestConfiguration.class,
        OcFlowBacktestTestConfiguration.class})
@TestPropertySource(properties = {
        "mybatis-plus.global-config.db-config.logic-delete-field=deleted",
        "mybatis-plus.global-config.db-config.logic-delete-value=1",
        "mybatis-plus.global-config.db-config.logic-not-delete-value=0",
        "mybatis-plus.type-aliases-package=pn.torn.goldeneye.repository.model"})
@EnabledIfSystemProperty(named = "oc.flow.backtest.enabled", matches = "true")
@DisplayName("OC流程图成功率回测门禁")
class OcFlowBacktestTest {
    /**
     * 门禁覆盖的最低OC等级
     */
    private static final int GATED_MIN_RANK = 7;
    /**
     * 门禁覆盖的最高OC等级
     */
    private static final int GATED_MAX_RANK = 8;
    /**
     * 只观察、不参与门禁的OC等级
     */
    private static final List<Integer> OBSERVED_RANKS = List.of(9, 10);
    /**
     * 槽位查询的单批OC数量
     */
    private static final int SLOT_CHUNK_SIZE = 1000;
    /**
     * 门禁样本下限，低于该数量说明历史数据没取全
     */
    private static final int MIN_GATED_SAMPLES = 8000;
    /**
     * Brier分数允许的最大劣化（相对等级阶梯）
     */
    private static final double MAX_BRIER_DELTA = 0.002;
    /**
     * 校准分档的宽度
     */
    private static final double BUCKET_WIDTH = 0.1;
    /**
     * 校准分档的数量
     */
    private static final int BUCKET_COUNT = 10;
    /**
     * 校准分档参与门禁的最少样本数
     */
    private static final int MIN_BUCKET_SAMPLES = 30;
    /**
     * 校准分档平均偏差上限（点）
     */
    private static final double MAX_MEAN_BUCKET_BIAS_POINTS = 5.0;
    /**
     * 校准分档最差档偏差上限（点）
     */
    private static final double MAX_WORST_BUCKET_BIAS_POINTS = 7.0;
    /**
     * 单调性检查的分档数量
     */
    private static final int MONOTONE_TIER_COUNT = 3;
    /**
     * 单调性分档标签，按预测成功率从低到高
     */
    private static final List<String> TIER_LABELS = List.of("低", "中", "高");
    /**
     * 成功率百分点与概率的换算
     */
    private static final double PERCENT_UNIT = 100.0;
    /**
     * 回测明细输出目录
     */
    private static final Path REPORT_DIRECTORY = Path.of("target");
    /**
     * 回测明细文件名的时间戳格式
     */
    private static final DateTimeFormatter REPORT_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    @Autowired
    private OcPlanningReadOnlyGuard readOnlyGuard;
    @Autowired
    private TornSettingOcFlowManager flowManager;
    @Autowired
    private TornFactionOcDAO ocDao;
    @Autowired
    private TornFactionOcSlotDAO ocSlotDao;
    @Autowired
    private OcFlowEvaluator evaluator;

    @Test
    @DisplayName("等级7-8按流程图预测不劣于等级阶梯，且校准分档与单调性达标")
    void backtestRankSevenAndEightAgainstLadderBaseline() throws IOException {
        readOnlyGuard.verifyReadOnlySession();
        List<String> report = readOnlyGuard.inReadOnlyTransaction(status -> runBacktest());
        Path reportPath = REPORT_DIRECTORY.resolve(
                "oc-flow-backtest-" + REPORT_STAMP.format(LocalDateTime.now()) + ".txt");
        Files.writeString(reportPath, String.join(System.lineSeparator(), report), StandardCharsets.UTF_8);
        log.info("回测明细已写入 {}", reportPath.toAbsolutePath());
    }

    /**
     * 在只读事务内完成装配校验、预测与门禁断言。
     *
     * @return 回测明细行
     */
    private List<String> runBacktest() {
        Map<OcFlowKey, OcFlowGraph> graphs = flowManager.getGraphs();
        requireAllGraphsAssembled(graphs);
        List<Sample> samples = collectSamples(graphs);
        List<Sample> gated = samples.stream()
                .filter(sample -> sample.rank() >= GATED_MIN_RANK && sample.rank() <= GATED_MAX_RANK)
                .toList();
        Metrics metrics = metrics(gated);
        List<BucketBias> bucketBiases = bucketBiases(gated);
        List<Tier> tiers = tiers(gated);

        List<String> report = new ArrayList<>();
        report.add(summaryLine(samples, gated, metrics));
        report.add(brierLine(metrics));
        appendBucketLines(report, bucketBiases);
        appendTierLines(report, tiers);
        appendPerOcLines(report, gated);
        appendObservedLines(report, samples);
        report.forEach(log::info);

        requireGates(metrics, bucketBiases, tiers);
        return report;
    }

    /**
     * 断言配置中的每个(OC名称, 等级)都装配成功，即装配期没有被跳过的图。
     *
     * @param graphs 装配结果
     */
    private void requireAllGraphsAssembled(Map<OcFlowKey, OcFlowGraph> graphs) {
        Set<OcFlowKey> configuredKeys = new LinkedHashSet<>();
        for (TornSettingOcFlowNodeDO row : flowManager.getNodes()) {
            configuredKeys.add(OcFlowKey.of(row.getOcName(), row.getRank()));
        }
        log.info("OC流程图装配完成, 配置键数={}, 成功装配={}", configuredKeys.size(), graphs.size());
        assertThat(configuredKeys).as("流程图配置键").isNotEmpty();
        assertThat(graphs.keySet()).as("装配失败的流程图")
                .containsExactlyInAnyOrderElementsOf(configuredKeys);
    }

    /**
     * 按(OC名称, 等级)取历史已完成OC，逐条求预测成功率。
     *
     * @param graphs 装配好的流程图
     * @return 回测样本
     */
    private List<Sample> collectSamples(Map<OcFlowKey, OcFlowGraph> graphs) {
        Map<OcRankNameKey, List<TornFactionOcDO>> completedByKey = loadCompletedOcs(graphs.keySet());
        List<Long> ocIds = new ArrayList<>();
        completedByKey.values().forEach(ocList -> ocList.forEach(oc -> ocIds.add(oc.getId())));
        ocIds.sort(Comparator.naturalOrder());
        Map<Long, List<TornFactionOcSlotDO>> slotsByOcId = loadSlots(ocIds);

        List<Sample> samples = new ArrayList<>(ocIds.size());
        int missingParticipants = 0;
        for (Map.Entry<OcRankNameKey, List<TornFactionOcDO>> entry : completedByKey.entrySet()) {
            OcRankNameKey key = entry.getKey();
            OcFlowGraph graph = graphs.get(OcFlowKey.of(key.ocName(), key.rank()));
            for (TornFactionOcDO oc : entry.getValue()) {
                Prediction prediction = predict(graph, slotsByOcId.getOrDefault(oc.getId(), List.of()), oc);
                missingParticipants += prediction.missingParticipants();
                samples.add(new Sample(key.ocName(), key.rank(), prediction.successRate(), successOf(oc)));
            }
        }
        log.info("回测样本构建完成, 样本={}, 槽位缺失参与者={}", samples.size(), missingParticipants);
        assertThat(missingParticipants)
                .as("流程图参与者岗位在历史槽位中缺失的条数, 回测前提是实测成功率完整")
                .isZero();
        return samples;
    }

    /**
     * 按(等级, OC名称)成对条件批量查询已完成的历史OC。
     *
     * @param graphKeys 流程图键
     * @return 键到该键历史OC列表的映射
     */
    private Map<OcRankNameKey, List<TornFactionOcDO>> loadCompletedOcs(Set<OcFlowKey> graphKeys) {
        List<OcRankNameKey> keys = graphKeys.stream()
                .map(key -> new OcRankNameKey(key.rank(), key.ocName()))
                .toList();
        Map<OcRankNameKey, List<TornFactionOcDO>> completedByKey = new HashMap<>();
        for (TornFactionOcDO oc : ocDao.queryCompletedByOcKeys(keys)) {
            completedByKey.computeIfAbsent(new OcRankNameKey(oc.getRank(), oc.getName()),
                    ignored -> new ArrayList<>()).add(oc);
        }
        log.info("回测历史OC加载完成, 分组={}, 明细={}", completedByKey.size(), describeGroups(completedByKey));
        return completedByKey;
    }

    /**
     * 生成分组明细，便于定位某个(OC名称, 等级)是否取到了历史样本。
     *
     * @param completedByKey 分组后的历史OC
     * @return 分组明细文本
     */
    private String describeGroups(Map<OcRankNameKey, List<TornFactionOcDO>> completedByKey) {
        return completedByKey.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparingInt(OcRankNameKey::rank)
                        .thenComparing(OcRankNameKey::ocName)))
                .map(entry -> entry.getKey().ocName() + "#" + entry.getKey().rank()
                        + "=" + entry.getValue().size())
                .collect(Collectors.joining(", "));
    }

    /**
     * 按OC ID每批{@value #SLOT_CHUNK_SIZE}条查询槽位。
     *
     * @param ocIds OC ID列表
     * @return OC ID到槽位列表的映射
     */
    private Map<Long, List<TornFactionOcSlotDO>> loadSlots(List<Long> ocIds) {
        Map<Long, List<TornFactionOcSlotDO>> slotsByOcId = new HashMap<>();
        for (int from = 0; from < ocIds.size(); from += SLOT_CHUNK_SIZE) {
            List<Long> chunk = ocIds.subList(from, Math.min(ocIds.size(), from + SLOT_CHUNK_SIZE));
            for (TornFactionOcSlotDO slot : ocSlotDao.queryListByOcId(chunk)) {
                slotsByOcId.computeIfAbsent(slot.getOcId(), ignored -> new ArrayList<>()).add(slot);
            }
        }
        log.info("回测槽位加载完成, OC={}, 批次数={}", ocIds.size(),
                (ocIds.size() + SLOT_CHUNK_SIZE - 1) / SLOT_CHUNK_SIZE);
        return slotsByOcId;
    }

    /**
     * 用一张图与一条历史OC的岗位实测成功率求预测成功率。
     *
     * @param graph 流程图
     * @param slots 该OC的槽位
     * @param oc    历史OC
     * @return 预测成功率与该OC缺失的参与者数量
     */
    private Prediction predict(OcFlowGraph graph, List<TornFactionOcSlotDO> slots, TornFactionOcDO oc) {
        Map<String, Double> rateByPosition = new HashMap<>();
        double rateSum = 0.0;
        int ratedSlots = 0;
        for (TornFactionOcSlotDO slot : slots) {
            if (slot.getPassRate() == null) {
                continue;
            }
            double rate = slot.getPassRate() / PERCENT_UNIT;
            rateByPosition.putIfAbsent(normalize(slot.getPosition()), rate);
            rateSum += rate;
            ratedSlots++;
        }
        double fallbackRate = ratedSlots == 0 ? ladderRate(oc.getRank()) : rateSum / ratedSlots;

        Map<String, Double> slotRates = new HashMap<>();
        int missingParticipants = 0;
        for (String participant : participantsOf(graph)) {
            Double rate = rateByPosition.get(normalize(participant));
            if (rate == null) {
                missingParticipants++;
                log.warn("回测槽位缺失: ocId={}, rank={}, participant={}", oc.getId(), oc.getRank(), participant);
                continue;
            }
            slotRates.put(participant, rate);
        }
        OcFlowOutcome outcome = evaluator.evaluate(graph, slotRates, fallbackRate);
        return new Prediction(outcome.successRate(), missingParticipants);
    }

    /**
     * 汇总图上所有判定节点的参与者岗位，去重后作为成功率查表的键。
     *
     * @param graph 流程图
     * @return 参与者岗位列表
     */
    private List<String> participantsOf(OcFlowGraph graph) {
        Set<String> participants = new LinkedHashSet<>();
        for (String nodeCode : graph.topologicalOrder()) {
            participants.addAll(graph.node(nodeCode).participants());
        }
        return List.copyOf(participants);
    }

    /**
     * 归一化岗位编码：去掉空白与非字母数字，抹平配置与槽位表的写法差异。
     *
     * @param position 岗位编码
     * @return 归一化后的岗位编码
     */
    private String normalize(String position) {
        return position == null ? "" : position.replaceAll("[^0-9A-Za-z#]", "").toUpperCase(Locale.ROOT);
    }

    /**
     * 该OC是否成功，成功为1、失败为0。
     *
     * @param oc 历史OC
     * @return 实际结果
     */
    private double successOf(TornFactionOcDO oc) {
        return TornOcStatusEnum.SUCCESSFUL.getCode().equals(oc.getStatus()) ? 1.0 : 0.0;
    }

    /**
     * 等级阶梯的成功率，作为对照基线。
     *
     * @param rank OC等级
     * @return 阶梯成功率
     */
    private double ladderRate(int rank) {
        return OcRecommendTunings.ladderSuccessRate(rank).doubleValue();
    }

    /**
     * 预测成功率的Brier分数。
     *
     * @param samples 回测样本
     * @return Brier分数
     */
    private double brier(List<Sample> samples) {
        return samples.stream()
                .mapToDouble(sample -> square(sample.predicted() - sample.actual()))
                .average().orElse(Double.NaN);
    }

    /**
     * 等级阶梯预测的Brier分数。
     *
     * @param samples 回测样本
     * @return Brier分数
     */
    private double ladderBrier(List<Sample> samples) {
        return samples.stream()
                .mapToDouble(sample -> square(ladderRate(sample.rank()) - sample.actual()))
                .average().orElse(Double.NaN);
    }

    /**
     * 计算平方，用于Brier分数。
     *
     * @param value 数值
     * @return 平方值
     */
    private double square(double value) {
        return value * value;
    }

    /**
     * 按取样函数求均值。
     *
     * @param samples 回测样本
     * @param mapper  取样函数
     * @return 均值；样本为空时为0
     */
    private double average(List<Sample> samples, ToDoubleFunction<Sample> mapper) {
        return samples.stream().mapToDouble(mapper).average().orElse(0.0);
    }

    /**
     * 汇总门禁样本的整体度量。
     *
     * @param gated 门禁样本
     * @return 整体度量
     */
    private Metrics metrics(List<Sample> gated) {
        return new Metrics(gated.size(), average(gated, Sample::predicted), average(gated, Sample::actual),
                brier(gated), ladderBrier(gated));
    }

    /**
     * 按预测成功率落到十分位分档，统计每档的预测与实际成功率。
     *
     * @param gated 门禁样本
     * @return 参与门禁的分档
     */
    private List<BucketBias> bucketBiases(List<Sample> gated) {
        Map<Integer, List<Sample>> byBucket = new HashMap<>();
        for (Sample sample : gated) {
            byBucket.computeIfAbsent(bucketOf(sample.predicted()), ignored -> new ArrayList<>()).add(sample);
        }
        List<BucketBias> bucketBiases = new ArrayList<>(BUCKET_COUNT);
        for (int index = 0; index < BUCKET_COUNT; index++) {
            List<Sample> bucket = byBucket.getOrDefault(index, List.of());
            if (bucket.size() < MIN_BUCKET_SAMPLES) {
                continue;
            }
            bucketBiases.add(new BucketBias(bucketBounds(index), bucket.size(),
                    average(bucket, Sample::predicted), average(bucket, Sample::actual)));
        }
        return bucketBiases;
    }

    /**
     * 计算预测成功率所属的分档下标。
     *
     * @param predicted 预测成功率
     * @return 分档下标
     */
    private int bucketOf(double predicted) {
        return Math.min(BUCKET_COUNT - 1, (int) Math.floor(predicted / BUCKET_WIDTH));
    }

    /**
     * 生成分档的取值区间文本。
     *
     * @param index 分档下标
     * @return 区间文本
     */
    private String bucketBounds(int index) {
        return String.format("[%.1f,%.1f)", index * BUCKET_WIDTH, (index + 1) * BUCKET_WIDTH);
    }

    /**
     * 按预测成功率排序后等分，得到低中高三档。
     *
     * @param gated 门禁样本
     * @return 低中高三档
     */
    private List<Tier> tiers(List<Sample> gated) {
        List<Sample> sorted = new ArrayList<>(gated);
        sorted.sort(Comparator.comparingDouble(Sample::predicted));
        int perTier = sorted.size() / MONOTONE_TIER_COUNT;
        if (perTier == 0) {
            return List.of();
        }
        List<Tier> tiers = new ArrayList<>(MONOTONE_TIER_COUNT);
        for (int index = 0; index < MONOTONE_TIER_COUNT; index++) {
            int from = index * perTier;
            int to = index == MONOTONE_TIER_COUNT - 1 ? sorted.size() : (index + 1) * perTier;
            List<Sample> tier = sorted.subList(from, to);
            tiers.add(new Tier(TIER_LABELS.get(index), tier.size(),
                    average(tier, Sample::predicted), average(tier, Sample::actual)));
        }
        return tiers;
    }

    /**
     * 校准分档的平均偏差（点）。
     *
     * @param bucketBiases 分档
     * @return 平均偏差（点）
     */
    private double meanAbsBiasPoints(List<BucketBias> bucketBiases) {
        return bucketBiases.stream().mapToDouble(bias -> Math.abs(bias.bias())).average().orElse(0.0)
                * PERCENT_UNIT;
    }

    /**
     * 校准分档的最差档偏差（点）。
     *
     * @param bucketBiases 分档
     * @return 最差档偏差（点）
     */
    private double worstAbsBiasPoints(List<BucketBias> bucketBiases) {
        return bucketBiases.stream().mapToDouble(bias -> Math.abs(bias.bias())).max().orElse(0.0)
                * PERCENT_UNIT;
    }

    /**
     * 回测结论：三条门禁全部达标才通过。
     *
     * @param metrics      整体度量
     * @param bucketBiases 校准分档
     * @param tiers        低中高三档
     */
    private void requireGates(Metrics metrics, List<BucketBias> bucketBiases, List<Tier> tiers) {
        assertThat(metrics.samples()).as("门禁样本数(等级%d-%d)", GATED_MIN_RANK, GATED_MAX_RANK)
                .isGreaterThan(MIN_GATED_SAMPLES);
        assertThat(metrics.brierDelta()).as("Brier相对等级阶梯的劣化")
                .isLessThanOrEqualTo(MAX_BRIER_DELTA);
        assertThat(bucketBiases).as("参与门禁的校准分档(样本≥%d)", MIN_BUCKET_SAMPLES).isNotEmpty();
        assertThat(meanAbsBiasPoints(bucketBiases)).as("校准分档平均偏差(点)")
                .isLessThanOrEqualTo(MAX_MEAN_BUCKET_BIAS_POINTS);
        assertThat(worstAbsBiasPoints(bucketBiases)).as("校准最差档偏差(点)")
                .isLessThanOrEqualTo(MAX_WORST_BUCKET_BIAS_POINTS);
        assertThat(tiers).as("单调性分档").hasSize(MONOTONE_TIER_COUNT);
        assertThat(tiers.get(2).actualRate()).as("高预测档实际成功率不低于中档")
                .isGreaterThanOrEqualTo(tiers.get(1).actualRate());
        assertThat(tiers.get(1).actualRate()).as("中预测档实际成功率不低于低档")
                .isGreaterThanOrEqualTo(tiers.get(0).actualRate());
    }

    /**
     * 生成汇总行。
     *
     * @param samples 全部样本
     * @param gated   门禁样本
     * @param metrics 整体度量
     * @return 汇总行
     */
    private String summaryLine(List<Sample> samples, List<Sample> gated, Metrics metrics) {
        return String.format("汇总 全部样本=%d 门禁样本(等级%d-%d)=%d 预测均值=%.4f 实际成功率=%.4f",
                samples.size(), GATED_MIN_RANK, GATED_MAX_RANK, metrics.samples(),
                metrics.predictedRate(), metrics.actualRate());
    }

    /**
     * 生成Brier对比行。
     *
     * @param metrics 整体度量
     * @return Brier对比行
     */
    private String brierLine(Metrics metrics) {
        return String.format("Brier 流程图=%.5f 等级阶梯=%.5f Δ=%+.5f 门禁=≤%+.4f 结果=%s",
                metrics.brier(), metrics.ladderBrier(), metrics.brierDelta(), MAX_BRIER_DELTA,
                metrics.brierDelta() <= MAX_BRIER_DELTA ? "达标" : "未达标");
    }

    /**
     * 追加校准分档明细与汇总行。
     *
     * @param report       明细行
     * @param bucketBiases 校准分档
     */
    private void appendBucketLines(List<String> report, List<BucketBias> bucketBiases) {
        for (BucketBias bucketBias : bucketBiases) {
            report.add(String.format("校准档 %s 样本=%d 预测=%.4f 实际=%.4f 偏差=%+.2f点",
                    bucketBias.bounds(), bucketBias.samples(), bucketBias.predictedRate(),
                    bucketBias.actualRate(), bucketBias.bias() * PERCENT_UNIT));
        }
        report.add(String.format("校准汇总 档数=%d 平均偏差=%.2f点(门禁≤%.0f) 最差档=%.2f点(门禁≤%.0f)",
                bucketBiases.size(), meanAbsBiasPoints(bucketBiases), MAX_MEAN_BUCKET_BIAS_POINTS,
                worstAbsBiasPoints(bucketBiases), MAX_WORST_BUCKET_BIAS_POINTS));
    }

    /**
     * 追加强调单调性的低中高三档。
     *
     * @param report 明细行
     * @param tiers  低中高三档
     */
    private void appendTierLines(List<String> report, List<Tier> tiers) {
        for (Tier tier : tiers) {
            report.add(String.format("单调档 %s 样本=%d 预测=%.4f 实际=%.4f",
                    tier.label(), tier.samples(), tier.predictedRate(), tier.actualRate()));
        }
    }

    /**
     * 追加每个(OC名称, 等级)的预测与实际对照，便于定位残差集中在哪张图。
     *
     * @param report 明细行
     * @param gated  门禁样本
     */
    private void appendPerOcLines(List<String> report, List<Sample> gated) {
        Map<String, List<Sample>> byOc = new LinkedHashMap<>();
        for (Sample sample : gated) {
            byOc.computeIfAbsent(sample.ocName() + "#" + sample.rank(),
                    ignored -> new ArrayList<>()).add(sample);
        }
        byOc.forEach((ocKey, ocSamples) -> report.add(String.format(
                "OC明细 %s 样本=%d 预测=%.4f 实际=%.4f 偏差=%+.2f点",
                ocKey, ocSamples.size(), average(ocSamples, Sample::predicted),
                average(ocSamples, Sample::actual),
                (average(ocSamples, Sample::predicted) - average(ocSamples, Sample::actual)) * PERCENT_UNIT)));
    }

    /**
     * 追加等级9与10的观察值：只输出，不做门禁。
     *
     * @param report  明细行
     * @param samples 全部样本
     */
    private void appendObservedLines(List<String> report, List<Sample> samples) {
        for (int rank : OBSERVED_RANKS) {
            List<Sample> observed = samples.stream().filter(sample -> sample.rank() == rank).toList();
            if (observed.isEmpty()) {
                report.add(String.format("观察 等级%d 无已完成样本", rank));
                continue;
            }
            report.add(String.format("观察 等级%d 样本=%d 预测=%.4f 实际=%.4f Brier=%.5f 阶梯Brier=%.5f 仅观察不入门禁",
                    rank, observed.size(), average(observed, Sample::predicted),
                    average(observed, Sample::actual), brier(observed), ladderBrier(observed)));
        }
    }

    /**
     * 单个回测样本：一张已完成OC的预测成功率与实际结果。
     *
     * @param ocName    OC名称
     * @param rank      OC等级
     * @param predicted 流程图预测成功率
     * @param actual    实际结果，成功为1、失败为0
     */
    private record Sample(String ocName, int rank, double predicted, double actual) {
    }

    /**
     * 门禁样本的整体度量。
     *
     * @param samples      样本数
     * @param predictedRate 预测成功率均值
     * @param actualRate    实际成功率
     * @param brier         流程图Brier分数
     * @param ladderBrier   等级阶梯Brier分数
     */
    private record Metrics(int samples, double predictedRate, double actualRate,
                           double brier, double ladderBrier) {
        /**
         * 相对等级阶梯的Brier劣化。
         *
         * @return 流程图Brier减阶梯Brier，正值表示更差
         */
        private double brierDelta() {
            return brier - ladderBrier;
        }
    }

    /**
     * 十分位校准分档。
     *
     * @param bounds        预测成功率的取值区间
     * @param samples       样本数
     * @param predictedRate 预测成功率均值
     * @param actualRate    实际成功率
     */
    private record BucketBias(String bounds, int samples, double predictedRate, double actualRate) {
        /**
         * 该档的预测减实际偏差。
         *
         * @return 偏差，正值为高估
         */
        private double bias() {
            return predictedRate - actualRate;
        }
    }

    /**
     * 单调性分档。
     *
     * @param label         档位标签
     * @param samples       样本数
     * @param predictedRate 预测成功率均值
     * @param actualRate    实际成功率
     */
    private record Tier(String label, int samples, double predictedRate, double actualRate) {
    }

    /**
     * 单条OC的预测结果。
     *
     * @param successRate         预测成功率
     * @param missingParticipants 该OC缺失实测成功率的参与者数量
     */
    private record Prediction(double successRate, int missingParticipants) {
    }
}
