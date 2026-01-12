package pn.torn.goldeneye.torn.service.faction.oc.recommend;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.constants.torn.TornConstants;
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
import pn.torn.goldeneye.torn.model.faction.crime.recommend.OcRecommendationVO;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * OC队伍分配基础逻辑层
 *
 * @author Bai
 * @version 0.4.0
 * @since 2025.11.24
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TornOcAssignService {
    private final TornOcRecommendManager ocRecommendManager;
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
        return allocateGlobally(vacantSlots, userOcMap);
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
                    if (o1.getReadyTime() == null) {
                        return -1;
                    }

                    if (o2.getReadyTime() == null) {
                        return 1;
                    }

                    return o2.getReadyTime().compareTo(o1.getReadyTime());
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
     * 全局分配算法：先保证不停转，再保证人选合适
     */
    private Map<TornUserDO, OcRecommendationVO> allocateGlobally(
            TreeMap<TornFactionOcDO, List<TornFactionOcSlotDO>> vacantSlots,
            Map<TornUserDO, List<TornFactionOcUserDO>> userOcMap) {
        Map<TornUserDO, OcRecommendationVO> result = new HashMap<>();
        Set<Long> allocatedSlot = new HashSet<>();
        Map<Long, LocalDateTime> ocReadyMap = buildOriginTimeMap(vacantSlots);

        for (Map.Entry<TornUserDO, List<TornFactionOcUserDO>> entry : userOcMap.entrySet()) {
            List<UserMatchScore> candidates = findCandidatesForUser(entry.getKey(), entry.getValue(),
                    vacantSlots, allocatedSlot);
            if (candidates.isEmpty()) {
                result.put(entry.getKey(), null);
                continue;
            }

            UserMatchScore bestMatch = candidates.stream()
                    .max(Comparator.comparing(UserMatchScore::score))
                    .orElse(null);

            BigDecimal score = bestMatch.score();

            LocalDateTime originTime = ocReadyMap.get(bestMatch.oc().getId());
            String reason = ocRecommendManager.buildRecommendReason(originTime, bestMatch.matchData().getPassRate());
            result.put(bestMatch.user(), new OcRecommendationVO(bestMatch.oc(), bestMatch.slot(), score, reason));

            allocatedSlot.add(bestMatch.slot().getId());
            bestMatch.oc().setReadyTime(bestMatch.oc().getReadyTime().plusDays(1));
        }

        for (TornFactionOcDO oc : vacantSlots.keySet()) {
            if (ocReadyMap.containsKey(oc.getId())) {
                oc.setReadyTime(ocReadyMap.get(oc.getId()));
            }
        }

        return result;
    }

    /**
     * 为指定用户找到所有合格的候选岗位
     */
    private List<UserMatchScore> findCandidatesForUser(TornUserDO user, List<TornFactionOcUserDO> userOcData,
                                                       Map<TornFactionOcDO, List<TornFactionOcSlotDO>> ocMap,
                                                       Set<Long> allocatedPosition) {
        List<UserMatchScore> candidates = new ArrayList<>();
        boolean isReassign = ocRecommendManager.checkIsReassignRecommended(user, userOcData);
        for (Map.Entry<TornFactionOcDO, List<TornFactionOcSlotDO>> entry : ocMap.entrySet()) {
            TornFactionOcDO oc = entry.getKey();
            // 大锅饭制度的, 只要成功率够了就只判断大锅饭
            if (isReassign && !TornConstants.ROTATION_OC_NAME.contains(oc.getName())) {
                continue;
            }

            for (TornFactionOcSlotDO slot : entry.getValue()) {
                TornSettingOcSlotDO setting = ocRecommendManager.findSlotSetting(oc, slot);
                TornFactionOcUserDO matchedData = ocRecommendManager.findUserPassRate(userOcData, oc, setting);

                // 跳过已分配的岗位, 检查是否满足最低成功率要求
                if (allocatedPosition.contains(slot.getId()) ||
                        matchedData == null ||
                        matchedData.getPassRate() < setting.getPassRate()) {
                    continue;
                }

                // 计算综合评分
                BigDecimal score = ocRecommendManager.calcRecommendScore(isReassign, oc, setting, matchedData);
                candidates.add(new UserMatchScore(user, userOcData, matchedData, oc, slot, score));
            }
        }

        return candidates;
    }

    /**
     * 构建原本时间的Map，因为每次分配是多个人，假设能全部进队的话，同天内的OC应该以岗位优先考虑
     *
     * @return Key为OC ID, Value为原本的准备时间
     */
    private Map<Long, LocalDateTime> buildOriginTimeMap(TreeMap<TornFactionOcDO, List<TornFactionOcSlotDO>> vacantSlots) {
        if (CollectionUtils.isEmpty(vacantSlots)) {
            return Map.of();
        }

        Map<Long, LocalDateTime> resultMap = HashMap.newHashMap(vacantSlots.size());
        for (TornFactionOcDO oc : vacantSlots.keySet()) {
            resultMap.put(oc.getId(), oc.getReadyTime());

            LocalDateTime originTime = oc.getReadyTime() == null ? LocalDateTime.now() : oc.getReadyTime();
            long readyHours = Duration.between(LocalDateTime.now(), originTime).toHours();
            LocalDateTime targetTime = readyHours < 8 ? LocalDateTime.now() : oc.getReadyTime();
            oc.setReadyTime(targetTime);
        }

        return resultMap;
    }

    /**
     * 用户匹配评分
     */
    private record UserMatchScore(
            TornUserDO user,
            List<TornFactionOcUserDO> userOcData,
            TornFactionOcUserDO matchData,
            TornFactionOcDO oc,
            TornFactionOcSlotDO slot,
            BigDecimal score) {
    }
}