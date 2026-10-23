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
import pn.torn.goldeneye.torn.service.faction.oc.recommend.rank.OcOpportunityCost;
import pn.torn.goldeneye.torn.service.faction.oc.recommend.rank.OcOpportunityCostRanker;
import pn.torn.goldeneye.torn.service.faction.oc.recommend.rank.OcOpportunityCostRanker.OcRankContext;
import pn.torn.goldeneye.torn.service.faction.oc.recommend.rank.OcRecommendTimeSupport;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * OC队伍推荐逻辑层
 *
 * <p>候选池是该帮派全部招募中OC的空位，不再按大锅饭名单剪枝；每个候选先由度量层折算成
 * 期望净产出与期望人天，再由机会成本排序器统一排序。是否在大锅饭名单只影响分钱口径，
 * 不影响推荐结果。已加入队伍时，以当前岗位的机会成本作为基线，只输出严格优于基线的候选。</p>
 *
 * @author Bai
 * @version 1.9.3
 * @since 2025.11.01
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TornOcRecommendService {
    private final TornOcRecommendManager ocRecommendManager;
    private final OcYieldEstimatorRegistry yieldEstimatorRegistry;
    private final OcOpportunityCostRanker opportunityCostRanker;
    private final TornFactionOcDAO ocDao;
    private final TornFactionOcSlotDAO ocSlotDao;
    private final TornFactionOcUserDAO ocUserDao;

    /**
     * 为用户推荐OC队伍和岗位。
     *
     * <p>排序键为机会成本（不填这个位帮派损失多少）。同一OC的多个空位各自独立进入结果，
     * 允许一个OC占满Top N：抢位失败后同队的第二个岗位仍值得加入，不必再请求一次指令。</p>
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
        RankFacts facts = buildRankFacts(user.getFactionId(), recruitOcList, emptySlotList, user.getId(), now);

        // 4. 构建候选并以当前岗位为基线排序
        List<OcRecommendCandidate> candidates = buildCandidates(recruitOcList, emptySlotList,
                user.getFactionId(), userOcData, facts.estimates());
        OcRecommendCandidate baseline = buildBaseline(user, joinedOc, userOcData, facts);
        List<OcRecommendCandidate> ranked = opportunityCostRanker.rank(candidates, baseline, facts.context());
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
     * 计算当前岗位的机会成本，作为候选过滤基线；无法评估时返回null表示不做基线过滤。
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
        RankFacts facts = buildRankFacts(user.getFactionId(), ocList, emptySlotList, user.getId(), now);
        OcRecommendCandidate current = new OcRecommendCandidate(joinedOc.getOc(), joinedOc.getSlot(),
                requirement, passRateData.getPassRate(), estimateOf(facts.estimates(), joinedOc.getOc()), null);
        return opportunityCostRanker.evaluate(current, facts.context()).opportunityCost();
    }

    /**
     * 加载本次排序共用的只读事实：产出估算、帮派能力矩阵、已占用岗位与空位统计。
     */
    private RankFacts buildRankFacts(long factionId, List<TornFactionOcDO> ocList,
                                     List<TornFactionOcSlotDO> emptySlotList, Long userId,
                                     LocalDateTime now) {
        Map<Long, OcYieldEstimate> estimates = yieldEstimatorRegistry.estimateAll(ocList, now);
        List<TornFactionOcUserDO> factionOcData = ocUserDao.queryFactionMatrix(factionId);
        List<TornFactionOcSlotDO> occupiedSlots = ocSlotDao.queryListByOc(ocList).stream()
                .filter(slot -> slot.getUserId() != null)
                .toList();
        Map<Long, Integer> emptySlotsByOc = emptySlotList.stream()
                .collect(Collectors.toMap(TornFactionOcSlotDO::getOcId, slot -> 1, Integer::sum));
        OcRankContext context = new OcRankContext(factionOcData, occupiedSlots, emptySlotsByOc, userId, now);
        return new RankFacts(estimates, context);
    }

    /**
     * 构建候选：能力门槛仍然生效（成功率低于岗位要求的不进候选），
     * 但成功率矩阵无记录不再等同于不可推荐，改为按映射值参与排序。
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
                TornSettingOcSlotDO requirement = ocRecommendManager.findSlotSetting(factionId, oc, slot);
                if (requirement == null) {
                    continue;
                }
                TornFactionOcUserDO matchedData = ocRecommendManager.findUserPassRate(userOcData, oc, requirement);
                Integer passRate = matchedData == null ? null : matchedData.getPassRate();
                if (passRate != null && passRate < requirement.getPassRate()) {
                    continue;
                }
                candidates.add(new OcRecommendCandidate(oc, slot, requirement, passRate,
                        estimateOf(estimates, oc), null));
            }
        }
        return candidates;
    }

    /**
     * 构建当前岗位基线；当前OC被禁用或能力不达标时不设基线（当前岗位不是合法加入目标）。
     */
    private OcRecommendCandidate buildBaseline(TornUserDO user, OcSlotDictBO joinedOc,
                                               List<TornFactionOcUserDO> userOcData, RankFacts facts) {
        if (joinedOc == null) {
            return null;
        }
        TornFactionOcDO oc = joinedOc.getOc();
        if (ocRecommendManager.isOcDisabled(user.getFactionId(), oc)) {
            return null;
        }
        TornSettingOcSlotDO requirement = ocRecommendManager.findSlotRequirement(user.getFactionId(), oc, joinedOc.getSlot());
        TornFactionOcUserDO passRateData = ocRecommendManager.findUserPassRate(userOcData, oc, requirement);
        if (requirement == null || passRateData == null
                || passRateData.getPassRate() < requirement.getPassRate()) {
            return null;
        }
        OcYieldEstimate estimate = facts.estimates().containsKey(oc.getId())
                ? facts.estimates().get(oc.getId())
                : yieldEstimatorRegistry.estimate(oc, facts.context().now());
        return new OcRecommendCandidate(oc, joinedOc.getSlot(), requirement,
                passRateData.getPassRate(), estimate, null);
    }

    /**
     * 取OC的估算值，缺失时按0产出兜底，避免排序阶段空指针。
     */
    private OcYieldEstimate estimateOf(Map<Long, OcYieldEstimate> estimates, TornFactionOcDO oc) {
        return estimates.getOrDefault(oc.getId(), OcYieldEstimate.zero(OcYieldSourceEnum.MAPPED));
    }

    /**
     * 把候选转成对外推荐结果，推荐理由文案与排序前完全一致。
     */
    private OcRecommendationVO toRecommendation(OcRecommendCandidate candidate) {
        OcOpportunityCost cost = candidate.cost();
        int passRate = candidate.passRate() != null
                ? candidate.passRate() : candidate.requirement().getPassRate();
        String reason = ocRecommendManager.buildRecommendReason(candidate.oc().getReadyTime(), passRate);
        return new OcRecommendationVO(candidate.oc(), candidate.slot(), cost.opportunityCost(), reason,
                candidate.estimate().netPerMemberDay(), cost.opportunityCost(),
                cost.substituteCount(), cost.outcome());
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
     * 查找招募中的OC空位，当前占用岗位不作为候选。
     *
     * @param recruitOcList 招募中的OC列表
     * @return 空闲岗位列表
     */
    public List<TornFactionOcSlotDO> findEmptySlotList(List<TornFactionOcDO> recruitOcList) {
        return ocSlotDao.queryEmptySlotList(recruitOcList);
    }

    /**
     * 本次排序共用的只读事实。
     *
     * @param estimates OC ID到期望产出估算值的映射
     * @param context   机会成本排序上下文
     * @author Bai
     * @version 1.9.3
     * @since 2026.10.23
     */
    private record RankFacts(Map<Long, OcYieldEstimate> estimates, OcRankContext context) {
    }

    /**
     * 当前OC状态及机会成本基线。
     *
     * @param joined               是否已加入OC
     * @param disabled             当前OC是否被禁用
     * @param passRateInsufficient 当前岗位成功率是否低于要求
     * @param actualPassRate       当前实际成功率
     * @param requiredPassRate     岗位要求成功率
     * @param currentScore         当前岗位的机会成本基线
     */
    public record CurrentOcStatus(boolean joined, boolean disabled, boolean passRateInsufficient,
                                  Integer actualPassRate, Integer requiredPassRate,
                                  BigDecimal currentScore) {
        private static CurrentOcStatus notJoined() {
            return new CurrentOcStatus(false, false, false, null, null, null);
        }
    }
}
