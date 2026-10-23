package pn.torn.goldeneye.torn.service.faction.oc.recommend;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcSlotDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcUserDAO;
import pn.torn.goldeneye.repository.dao.user.TornUserDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcUserDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcSlotDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.manager.faction.crime.recommend.TornOcRecommendManager;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.OcRecommendCandidate;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.OcRecommendationVO;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.OcYieldEstimate;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.OcYieldSourceEnum;
import pn.torn.goldeneye.torn.service.faction.oc.recommend.metric.OcYieldEstimatorRegistry;
import pn.torn.goldeneye.torn.service.faction.oc.recommend.rank.OcOpportunityCost;
import pn.torn.goldeneye.torn.service.faction.oc.recommend.rank.OcOpportunityCostRanker;
import pn.torn.goldeneye.torn.service.faction.oc.recommend.rank.OcOpportunityCostRanker.OcRankContext;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * OC队伍分配基础逻辑层
 *
 * <p>与单人推荐共用同一套度量与排序：候选先折算成期望净产出与期望人天，再按机会成本排序，
 * 不再按大锅饭名单剪枝，也不再有第二套评分公式。分配过程只把「预期开动时间」放进局部Map，
 * 不再就地改写共享的OC对象，因此不存在需要回滚的状态。</p>
 *
 * @author Bai
 * @version 1.9.3
 * @since 2025.11.24
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TornOcAssignService {
    private final TornOcRecommendManager ocRecommendManager;
    private final OcYieldEstimatorRegistry yieldEstimatorRegistry;
    private final OcOpportunityCostRanker opportunityCostRanker;
    private final TornFactionOcDAO ocDao;
    private final TornFactionOcSlotDAO ocSlotDao;
    private final TornFactionOcUserDAO ocUserDao;
    private final TornUserDAO userDao;

    /**
     * 为空闲人员推荐队伍和岗位
     *
     * @return Key为用户ID, Value为对应推荐OC和岗位
     */
    public Map<TornUserDO, OcRecommendationVO> assignUserList(long factionId) {
        // 1. 查询所有正在参与OC的岗位
        List<TornFactionOcDO> ocList = ocDao.queryExecutingOc(factionId);
        List<TornFactionOcSlotDO> slotList = ocSlotDao.queryListByOc(ocList);

        // 2. 筛选已入队的人, 不再调整
        List<Long> busyUserIdList = fillBusyAndWaitMember(slotList);

        // 3. 筛选帮派空闲用户
        List<TornFactionOcUserDO> allUsers = ocUserDao.queryByFactionId(factionId);
        Map<Long, List<TornFactionOcUserDO>> idleUsersMap = allUsers.stream()
                .filter(user -> !busyUserIdList.contains(user.getUserId()))
                .collect(Collectors.groupingBy(TornFactionOcUserDO::getUserId));
        if (CollectionUtils.isEmpty(idleUsersMap)) {
            return Map.of();
        }

        // 4. 组装参数
        Map<Long, TornUserDO> userMap = userDao.queryUserMap(idleUsersMap.keySet());
        Map<TornUserDO, List<TornFactionOcUserDO>> paramMap = new TreeMap<>(
                Comparator.comparing(TornUserDO::getCrimeExpRank));
        idleUsersMap.forEach((k, v) -> paramMap.put(userMap.get(k), v));

        // 5. 获取推荐结果
        return assignUserList(factionId, paramMap);
    }

    /**
     * 为OC结束人员推荐队伍和岗位
     *
     * @param userOcMap Key为用户ID, Value为对应的成功率数据
     * @return Key为用户ID, Value为对应推荐OC和岗位
     */
    public Map<TornUserDO, OcRecommendationVO> assignUserList(long factionId,
                                                              Map<TornUserDO, List<TornFactionOcUserDO>> userOcMap) {
        // 1. 查询招募中的OC
        List<TornFactionOcDO> recruitList = ocDao.queryRecrutList(factionId);
        if (CollectionUtils.isEmpty(recruitList)) {
            return Map.of();
        }

        // 2. 构建岗位候选池
        TreeMap<TornFactionOcDO, List<TornFactionOcSlotDO>> vacantSlots = buildVacantSlotMap(recruitList);
        if (vacantSlots.isEmpty()) {
            return Map.of();
        }

        // 3. 全局分配
        return allocateGlobally(factionId, vacantSlots, userOcMap);
    }

    /**
     * 填充忙碌人员和空转人员
     */
    private List<Long> fillBusyAndWaitMember(List<TornFactionOcSlotDO> slotList) {
        List<Long> busyUserIdList = new ArrayList<>();
        for (TornFactionOcSlotDO slot : slotList) {
            if (slot.getUserId() != null) {
                busyUserIdList.add(slot.getUserId());
            }
        }
        return busyUserIdList;
    }

    /**
     * 构建空闲岗位列表
     */
    private TreeMap<TornFactionOcDO, List<TornFactionOcSlotDO>> buildVacantSlotMap(List<TornFactionOcDO> urgentOcList) {
        List<TornFactionOcSlotDO> emptySlotList = ocSlotDao.queryEmptySlotList(urgentOcList);
        TreeMap<TornFactionOcDO, List<TornFactionOcSlotDO>> resultMap = new TreeMap<>(
                (o1, o2) -> {
                    int readyTimeCompare = compareReadyTime(o1.getReadyTime(), o2.getReadyTime());
                    if (readyTimeCompare != 0) {
                        return readyTimeCompare;
                    }
                    return compareOcId(o1.getId(), o2.getId());
                });
        for (TornFactionOcDO oc : urgentOcList) {
            List<TornFactionOcSlotDO> slots = emptySlotList.stream()
                    .filter(s -> s.getOcId().equals(oc.getId()))
                    .toList();
            resultMap.put(oc, slots);
        }

        return resultMap;
    }

    /**
     * 全局分配算法：按机会成本从高到低为每个空闲成员挑选岗位。
     */
    private Map<TornUserDO, OcRecommendationVO> allocateGlobally(
            long factionId,
            TreeMap<TornFactionOcDO, List<TornFactionOcSlotDO>> vacantSlots,
            Map<TornUserDO, List<TornFactionOcUserDO>> userOcMap) {
        LocalDateTime now = LocalDateTime.now();
        List<TornFactionOcDO> ocList = List.copyOf(vacantSlots.keySet());
        List<TornFactionOcSlotDO> emptySlotList = vacantSlots.values().stream()
                .flatMap(List::stream)
                .toList();
        Map<Long, OcYieldEstimate> estimates = yieldEstimatorRegistry.estimateAll(ocList, now);
        Map<Long, Integer> emptySlotsByOc = emptySlotList.stream()
                .collect(Collectors.toMap(TornFactionOcSlotDO::getOcId, slot -> 1, Integer::sum));
        List<TornFactionOcSlotDO> occupiedSlots = ocSlotDao.queryListByOc(ocList).stream()
                .filter(slot -> slot.getUserId() != null)
                .toList();
        OcRankContext baseContext = new OcRankContext(ocUserDao.queryFactionMatrix(factionId),
                occupiedSlots, emptySlotsByOc, null, now);
        // 预期开动时间只在本方法内推进，用于理由文案，不改写共享的OC对象
        Map<Long, LocalDateTime> plannedReadyMap = buildOriginReadyMap(vacantSlots);

        Map<TornUserDO, OcRecommendationVO> result = new HashMap<>();
        Set<Long> allocatedSlot = new HashSet<>();
        for (Map.Entry<TornUserDO, List<TornFactionOcUserDO>> entry : userOcMap.entrySet()) {
            TornUserDO user = entry.getKey();
            List<OcRecommendCandidate> candidates = buildCandidates(user, entry.getValue(),
                    vacantSlots, estimates, allocatedSlot);
            OcRankContext context = baseContext.withUserId(user.getId());
            List<OcRecommendCandidate> ranked = opportunityCostRanker.rank(candidates, null, context);
            if (ranked.isEmpty()) {
                result.put(user, null);
                continue;
            }

            OcRecommendCandidate bestMatch = ranked.getFirst();
            Integer passRate = bestMatch.passRate() != null
                    ? bestMatch.passRate() : bestMatch.requirement().getPassRate();
            String reason = ocRecommendManager.buildRecommendReason(
                    plannedReadyMap.get(bestMatch.oc().getId()), passRate);
            result.put(user, toRecommendation(bestMatch, reason));
            allocatedSlot.add(bestMatch.slot().getId());
            advancePlannedReady(plannedReadyMap, bestMatch.oc().getId());
        }

        return result;
    }

    /**
     * 为指定用户构建全部合格候选：跳过已分配岗位与缺配置岗位，
     * 成功率低于岗位要求的不进候选；成功率无记录时按能力未知处理，交由映射值参与排序。
     */
    private List<OcRecommendCandidate> buildCandidates(TornUserDO user, List<TornFactionOcUserDO> userOcData,
                                                       TreeMap<TornFactionOcDO, List<TornFactionOcSlotDO>> ocMap,
                                                       Map<Long, OcYieldEstimate> estimates,
                                                       Set<Long> allocatedSlot) {
        List<OcRecommendCandidate> candidates = new ArrayList<>();
        for (Map.Entry<TornFactionOcDO, List<TornFactionOcSlotDO>> entry : ocMap.entrySet()) {
            TornFactionOcDO oc = entry.getKey();
            for (TornFactionOcSlotDO slot : entry.getValue()) {
                if (allocatedSlot.contains(slot.getId())) {
                    continue;
                }
                TornSettingOcSlotDO requirement = ocRecommendManager.findSlotSetting(user.getFactionId(), oc, slot);
                if (requirement == null) {
                    continue;
                }
                TornFactionOcUserDO matchedData = ocRecommendManager.findUserPassRate(userOcData, oc, requirement);
                Integer passRate = matchedData == null ? null : matchedData.getPassRate();
                if (passRate != null && passRate < requirement.getPassRate()) {
                    continue;
                }
                candidates.add(new OcRecommendCandidate(oc, slot, requirement, passRate,
                        estimates.getOrDefault(oc.getId(), OcYieldEstimate.zero(OcYieldSourceEnum.MAPPED)),
                        null));
            }
        }
        return candidates;
    }

    /**
     * 把候选转成对外推荐结果，排序键写入机会成本。
     */
    private OcRecommendationVO toRecommendation(OcRecommendCandidate candidate, String reason) {
        OcOpportunityCost cost = candidate.cost();
        return new OcRecommendationVO(candidate.oc(), candidate.slot(), cost.opportunityCost(), reason,
                candidate.estimate().netPerMemberDay(), cost.opportunityCost(),
                cost.substituteCount(), cost.outcome());
    }

    /**
     * 构建原本时间的Map，仅用于理由文案，不改写共享的OC对象。
     *
     * @return Key为OC ID, Value为原本的准备时间
     */
    private Map<Long, LocalDateTime> buildOriginReadyMap(
            TreeMap<TornFactionOcDO, List<TornFactionOcSlotDO>> vacantSlots) {
        Map<Long, LocalDateTime> resultMap = HashMap.newHashMap(vacantSlots.size());
        for (TornFactionOcDO oc : vacantSlots.keySet()) {
            resultMap.put(oc.getId(), oc.getReadyTime());
        }
        return resultMap;
    }

    /**
     * 推进该OC的预期开动时间：每为它分配一人，开动时间顺延一天。
     */
    private void advancePlannedReady(Map<Long, LocalDateTime> plannedReadyMap, Long ocId) {
        plannedReadyMap.compute(ocId, (k, plannedReady) ->
                plannedReady == null ? null : plannedReady.plusDays(1));
    }

    /**
     * 比较OC准备时间，空值排在有值之前，非空时间按倒序排列。
     *
     * @param first  第一个准备时间
     * @param second 第二个准备时间
     * @return 比较结果
     */
    private int compareReadyTime(LocalDateTime first, LocalDateTime second) {
        return Comparator.nullsFirst(Comparator.<LocalDateTime>reverseOrder()).compare(first, second);
    }

    /**
     * 比较OC ID，空值排在有值之前，非空ID按升序排列。
     *
     * @param first  第一个OC ID
     * @param second 第二个OC ID
     * @return 比较结果
     */
    private int compareOcId(Long first, Long second) {
        return Comparator.nullsFirst(Comparator.<Long>naturalOrder()).compare(first, second);
    }
}
