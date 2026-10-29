package pn.torn.goldeneye.torn.service.faction.oc.recommend;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcSlotDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcUserDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcUserDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcSlotDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.manager.faction.crime.recommend.TornOcRecommendManager;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.*;
import pn.torn.goldeneye.torn.service.faction.oc.recommend.metric.OcYieldEstimatorRegistry;
import pn.torn.goldeneye.torn.service.faction.oc.recommend.metric.flow.OcFlowMarginService;
import pn.torn.goldeneye.torn.service.faction.oc.recommend.rank.OcRecommendRanker;
import pn.torn.goldeneye.torn.service.faction.oc.recommend.rank.OcRecommendRanker.OcRankContext;
import pn.torn.goldeneye.torn.service.faction.oc.recommend.rank.OcRecommendScore;
import pn.torn.goldeneye.torn.service.faction.oc.recommend.rank.OcRecommendTimeSupport;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * OC队伍推荐逻辑层
 *
 * <p>候选池是该帮派全部招募中OC的空位，不再按大锅饭名单剪枝；每个候选由度量层折算长期效率，
 * 由DAG计算个人相对标尺替补的期望收益增量，再由排序器按最高胜任层级、真实停转时间与ΔV统一排序。
 * 是否在大锅饭名单只影响分钱口径，不影响推荐结果。已加入队伍时，以当前岗位的评分作为基线，
 * 只输出严格优于基线的候选（同队换岗不受此限制，换岗不损失进度也不会改变评分）。</p>
 *
 * @author Bai
 * @version 1.9.6
 * @since 2025.11.01
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TornOcRecommendService {
    private final TornOcRecommendManager ocRecommendManager;
    private final OcYieldEstimatorRegistry yieldEstimatorRegistry;
    private final OcFlowMarginService ocFlowMarginService;
    private final OcRecommendRanker ocRecommendRanker;
    private final TornFactionOcDAO ocDao;
    private final TornFactionOcSlotDAO ocSlotDao;
    private final TornFactionOcUserDAO ocUserDao;

    /**
     * 为用户推荐OC队伍和岗位。
     *
     * <p>排序按最高胜任层级、真实停转时间、核心/大众层级和个人DAG边际执行。
     * 长期单位人天收益只保留诊断，不再决定现有队伍的跨OC顺序。同一OC的多个空位各自独立进入结果，
     * 允许一个OC占满Top N：抢位失败后同队的第二个岗位仍值得加入，不必再请求一次指令。
     * 候选不因负ΔV被删除，只有严格准入后没有任何可填空位时才返回空。</p>
     *
     * @param user     用户
     * @param topN     返回Top N个推荐
     * @param joinedOc 当前占用的OC岗位
     * @return 可切换的推荐岗位列表
     */
    public List<OcRecommendationVO> recommendOcForUser(TornUserDO user, int topN, OcSlotDictBO joinedOc) {
        LocalDateTime now = LocalDateTime.now();
        // 1. 查询所有招募中的OC
        List<TornFactionOcDO> recruitOcList = findRecrutList(user.getFactionId(), joinedOc, now);
        if (CollectionUtils.isEmpty(recruitOcList)) {
            return List.of();
        }

        // 2. 查询所有未满员的OC
        List<TornFactionOcSlotDO> emptySlotList = findEmptySlotList(recruitOcList);
        if (CollectionUtils.isEmpty(emptySlotList)) {
            return List.of();
        }

        // 3. 查询用户成功率数据；矩阵为空不再提前返回，交由度量层退回映射值
        List<TornFactionOcUserDO> userOcData = ocUserDao.queryByUserId(user.getId());
        RankFacts facts = buildRankFacts(user.getFactionId(), recruitOcList, emptySlotList,
                user.getId(), joinedOc, now);

        // 4. 构建候选和当前基线，共用同一批DAG边际结果排序
        List<OcRecommendCandidate> candidates = buildCandidates(recruitOcList, emptySlotList,
                user.getFactionId(), userOcData, facts.estimates());
        OcRecommendCandidate baseline = buildBaseline(user, joinedOc, userOcData, facts);
        List<TornFactionOcDO> marginOcList = withJoinedOc(recruitOcList, joinedOc);
        List<OcRecommendCandidate> marginInput = new ArrayList<>(candidates);
        if (baseline != null) {
            marginInput.add(baseline);
        }
        List<OcRecommendCandidate> marginOutput = ocFlowMarginService.apply(marginInput, user.getFactionId(),
                marginOcList, ocSlotDao.queryListByOc(marginOcList), Map.of());
        candidates = marginOutput.subList(0, candidates.size());
        if (baseline != null) {
            baseline = marginOutput.getLast();
        }
        List<OcRecommendCandidate> ranked = ocRecommendRanker.rank(candidates, baseline, facts.context());
        return ranked.stream()
                .limit(Math.max(0, topN))
                .map(this::toRecommendation)
                .toList();
    }

    /**
     * 查询用户当前OC状态，供指令层输出明确的当前状态提示。
     *
     * @param user     用户
     * @param joinedOc 当前占用的OC岗位
     * @return 当前状态；未入队时返回joined为false的状态
     */
    public CurrentOcStatus queryCurrentOcStatus(TornUserDO user, OcSlotDictBO joinedOc) {
        if (joinedOc == null) {
            return CurrentOcStatus.notJoined();
        }

        LocalDateTime now = LocalDateTime.now();
        List<TornFactionOcUserDO> userOcData = ocUserDao.queryByUserId(user.getId());
        TornFactionOcDO oc = joinedOc.getOc();
        boolean disabled = ocRecommendManager.isOcDisabled(user.getFactionId(), oc);
        TornSettingOcSlotDO requirement = ocRecommendManager.findSlotRequirement(user.getFactionId(), oc, joinedOc.getSlot());
        TornFactionOcUserDO passRateData = ocRecommendManager.findUserPassRate(userOcData, oc, requirement);
        Integer actualPassRate = passRateData == null ? null : passRateData.getPassRate();
        Integer requiredPassRate = requirement == null ? null : requirement.getPassRate();
        boolean passRateInsufficient = actualPassRate != null && requiredPassRate != null
                && actualPassRate < requiredPassRate;
        BigDecimal currentScore = calculateCurrentScore(user, joinedOc, requirement, passRateData, now);
        return new CurrentOcStatus(true, disabled, passRateInsufficient,
                actualPassRate, requiredPassRate, currentScore);
    }

    /**
     * 查找推荐候选OC列表。
     *
     * <p>当前已加入禁用OC且有进度时，恢复为帮派正常招募OC范围；当前OC未禁用且有进度时仍只返回当前OC，
     * 保持原有行为。用户未开动的当前OC会把自身准备时间归一化后再放入候选池。</p>
     *
     * @param factionId    帮派ID
     * @param joinedOcSlot 当前占用的OC岗位，未加入时传null
     * @param now          本次推荐的时间基准
     * @return 推荐候选OC列表
     */
    public List<TornFactionOcDO> findRecrutList(long factionId, OcSlotDictBO joinedOcSlot, LocalDateTime now) {
        if (shouldRecommendNormalOcForDisabledCurrentOc(factionId, joinedOcSlot)) {
            return ocDao.queryRecrutList(factionId);
        }
        // 跑了进度的, 只能判断当前队, 可以换位置
        if (joinedOcSlot != null && hasProgress(joinedOcSlot)) {
            return List.of(joinedOcSlot.getOc());
        }

        List<TornFactionOcDO> recruitOcList = ocDao.queryRecrutList(factionId);
        if (joinedOcSlot == null) {
            return recruitOcList;
        }
        return normalizeJoinedOc(recruitOcList, joinedOcSlot.getOc(), now);
    }

    /**
     * 查找招募中的OC空位，当前占用岗位不作为候选。
     *
     * @param recruitOcList 招募中的OC列表
     * @return 空闲岗位列表
     */
    public List<TornFactionOcSlotDO> findEmptySlotList(List<TornFactionOcDO> recruitOcList) {
        return ocSlotDao.queryEmptySlotList(recruitOcList);
    }

    /**
     * 计算当前岗位的推荐评分，作为候选过滤基线；无法评估时返回null表示不做基线过滤。
     */
    private BigDecimal calculateCurrentScore(TornUserDO user, OcSlotDictBO joinedOc,
                                             TornSettingOcSlotDO requirement,
                                             TornFactionOcUserDO passRateData,
                                             LocalDateTime now) {
        if (requirement == null || passRateData == null
                || passRateData.getPassRate() < requirement.getPassRate()) {
            return null;
        }
        List<TornFactionOcDO> ocList = List.of(joinedOc.getOc());
        List<TornFactionOcSlotDO> emptySlotList = ocSlotDao.queryEmptySlotList(ocList);
        RankFacts facts = buildRankFacts(user.getFactionId(), ocList, emptySlotList,
                user.getId(), joinedOc, now);
        OcRecommendCandidate current = new OcRecommendCandidate(joinedOc.getOc(), joinedOc.getSlot(),
                requirement, passRateData.getPassRate(), estimateOf(facts.estimates(), joinedOc.getOc()), null);
        return ocRecommendRanker.evaluate(current, facts.context()).score();
    }

    /**
     * 加载本次排序共用的只读事实：产出估算、帮派能力矩阵与空位统计。
     *
     * <p>产出估算只走批量入口，并把当前OC并入估算池：基线取值与候选出自同一次统计查询，
     * 整个请求不会为任何单个OC再查一次库。</p>
     */
    private RankFacts buildRankFacts(long factionId, List<TornFactionOcDO> ocList,
                                     List<TornFactionOcSlotDO> emptySlotList, Long userId,
                                     OcSlotDictBO joinedOc, LocalDateTime now) {
        Map<Long, OcYieldEstimate> estimates = yieldEstimatorRegistry.estimateAll(
                withJoinedOc(ocList, joinedOc), now);
        List<TornFactionOcUserDO> factionOcData = ocUserDao.queryFactionMatrix(factionId);
        Map<Long, Integer> emptySlotsByOc = emptySlotList.stream()
                .collect(Collectors.toMap(TornFactionOcSlotDO::getOcId, slot -> 1, Integer::sum));
        OcRankContext context = new OcRankContext(factionOcData, emptySlotsByOc, userId, now);
        return new RankFacts(estimates, context);
    }

    /**
     * 构建候选：能力门槛生效，成功率矩阵无记录或低于岗位要求都不进候选（口径见{@code OcSlotEligibility}）。
     */
    private List<OcRecommendCandidate> buildCandidates(List<TornFactionOcDO> recruitOcList,
                                                       List<TornFactionOcSlotDO> emptySlotList,
                                                       long factionId,
                                                       List<TornFactionOcUserDO> userOcData,
                                                       Map<Long, OcYieldEstimate> estimates) {
        Map<Long, List<TornFactionOcSlotDO>> slotsByOc = emptySlotList.stream()
                .collect(Collectors.groupingBy(TornFactionOcSlotDO::getOcId));
        List<OcRecommendCandidate> candidates = new ArrayList<>();
        for (TornFactionOcDO oc : recruitOcList) {
            for (TornFactionOcSlotDO slot : slotsByOc.getOrDefault(oc.getId(), List.of())) {
                OcSlotEligibility eligibility = ocRecommendManager.resolveEligibility(factionId, oc, slot, userOcData);
                if (eligibility == null || !eligibility.eligible()) {
                    continue;
                }
                candidates.add(new OcRecommendCandidate(oc, slot, eligibility.requirement(),
                        eligibility.passRate(), estimateOf(estimates, oc), null));
            }
        }
        return candidates;
    }

    /**
     * 构建当前岗位基线；岗位未配置、被帮派禁用或能力不达标时不设基线。
     */
    private OcRecommendCandidate buildBaseline(TornUserDO user, OcSlotDictBO joinedOc,
                                               List<TornFactionOcUserDO> userOcData, RankFacts facts) {
        if (joinedOc == null) {
            return null;
        }
        TornFactionOcDO oc = joinedOc.getOc();
        OcSlotEligibility eligibility = ocRecommendManager.resolveEligibility(
                user.getFactionId(), oc, joinedOc.getSlot(), userOcData);
        if (eligibility == null || !eligibility.eligible()) {
            return null;
        }
        OcYieldEstimate estimate = estimateOf(facts.estimates(), oc);
        return new OcRecommendCandidate(oc, joinedOc.getSlot(), eligibility.requirement(),
                eligibility.passRate(), estimate, null);
    }

    /**
     * 取OC的估算值，缺失时按0产出兜底，避免排序阶段空指针。
     */
    private OcYieldEstimate estimateOf(Map<Long, OcYieldEstimate> estimates, TornFactionOcDO oc) {
        return estimates.getOrDefault(oc.getId(), OcYieldEstimate.zero(OcYieldSourceEnum.MAPPED));
    }

    /**
     * 把当前OC并入估算池：推荐范围可能不含当前OC（当前OC被禁用但已有进度时回到常规招募范围），
     * 基线也必须取到与候选同源的估算值，不得为它单独再查一次统计。
     */
    private List<TornFactionOcDO> withJoinedOc(List<TornFactionOcDO> ocList, OcSlotDictBO joinedOc) {
        if (joinedOc == null || joinedOc.getOc() == null) {
            return ocList;
        }
        TornFactionOcDO joined = joinedOc.getOc();
        boolean contained = ocList.stream().anyMatch(oc -> Objects.equals(oc.getId(), joined.getId()));
        if (contained) {
            return ocList;
        }
        List<TornFactionOcDO> pool = new ArrayList<>(ocList.size() + 1);
        pool.addAll(ocList);
        pool.add(joined);
        return pool;
    }

    /**
     * 把候选转成对外推荐结果：对外评分是百分制，货币量纲的单位人天产出只留作内部诊断，
     * 理由文案只写「还要等多久、成功率如何」，不展示任何金额。
     */
    private OcRecommendationVO toRecommendation(OcRecommendCandidate candidate) {
        OcRecommendScore score = candidate.score();
        int passRate = candidate.passRate() != null
                ? candidate.passRate() : candidate.requirement().getPassRate();
        String reason = ocRecommendManager.buildRecommendReason(candidate.oc().getReadyTime(), passRate);
        return new OcRecommendationVO(candidate.oc(), candidate.slot(),
                BigDecimal.valueOf(score.rating()), reason,
                score.unitMemberDayValue(), score.substituteCount(), score.outcome());
    }

    /**
     * 把当前OC按归一化准备时间放回候选池，避免同队等价岗位以虚高的准备时间参与比较。
     */
    private List<TornFactionOcDO> normalizeJoinedOc(List<TornFactionOcDO> recruitOcList,
                                                    TornFactionOcDO joinedOc, LocalDateTime now) {
        List<TornFactionOcDO> normalized = new ArrayList<>(recruitOcList.size() + 1);
        boolean joinedInRecruitList = false;
        for (TornFactionOcDO oc : recruitOcList) {
            if (oc.getId().equals(joinedOc.getId())) {
                joinedInRecruitList = true;
                normalized.add(withNormalizedReadyTime(oc, now));
            } else {
                normalized.add(oc);
            }
        }
        if (!joinedInRecruitList) {
            normalized.add(withNormalizedReadyTime(joinedOc, now));
        }
        return normalized;
    }

    /**
     * 复制OC并归一化准备时间；新队没有偏移，直接返回原对象。
     */
    private TornFactionOcDO withNormalizedReadyTime(TornFactionOcDO oc, LocalDateTime now) {
        LocalDateTime normalizedReadyTime = OcRecommendTimeSupport.normalizeReadyTime(oc, now);
        if (normalizedReadyTime == null || normalizedReadyTime.equals(oc.getReadyTime())) {
            return oc;
        }
        TornFactionOcDO copy = new TornFactionOcDO();
        BeanUtils.copyProperties(oc, copy);
        copy.setReadyTime(normalizedReadyTime);
        return copy;
    }

    /**
     * 判断是否应为禁用当前OC且有进度的用户恢复正常招募OC候选范围。
     *
     * @param factionId    帮派ID
     * @param joinedOcSlot 当前占用的OC岗位
     * @return true表示当前OC被禁用且有进度，应使用正常招募OC作为推荐范围
     */
    private boolean shouldRecommendNormalOcForDisabledCurrentOc(long factionId, OcSlotDictBO joinedOcSlot) {
        return joinedOcSlot != null
                && hasProgress(joinedOcSlot)
                && ocRecommendManager.isOcDisabled(factionId, joinedOcSlot.getOc());
    }

    /**
     * 判断当前岗位是否已有进度。
     */
    private boolean hasProgress(OcSlotDictBO joinedOcSlot) {
        return BigDecimal.ZERO.compareTo(joinedOcSlot.getSlot().getProgress()) < 0;
    }

    /**
     * 本次排序共用的只读事实。
     *
     * @param estimates OC ID到期望产出估算值的映射
     * @param context   排序上下文
     */
    private record RankFacts(
            Map<Long, OcYieldEstimate> estimates,
            OcRankContext context) {
    }

    /**
     * 当前OC状态及评分基线。
     *
     * @param joined               是否已加入OC
     * @param disabled             当前OC是否被禁用
     * @param passRateInsufficient 当前岗位成功率是否低于要求
     * @param actualPassRate       当前实际成功率
     * @param requiredPassRate     岗位要求成功率
     * @param currentScore         当前岗位的推荐评分基线
     */
    public record CurrentOcStatus(
            boolean joined,
            boolean disabled,
            boolean passRateInsufficient,
            Integer actualPassRate,
            Integer requiredPassRate,
            BigDecimal currentScore) {
        private static CurrentOcStatus notJoined() {
            return new CurrentOcStatus(false, false, false, null, null, null);
        }
    }
}
