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
import pn.torn.goldeneye.torn.model.faction.crime.recommend.OcRecommendationVO;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * OC队伍分配基础逻辑层
 *
 * @author Bai
 * @version 0.3.0
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

        // 2. 如果2天内不能开始准备的, 视为空转, 不算忙碌人员
        List<TornFactionOcSlotDO> waitSlotList = new ArrayList<>();
        List<Long> busyUserIdList = new ArrayList<>();
        for (TornFactionOcDO oc : ocList) {
            fillBusyAndWaitMember(oc, slotList, busyUserIdList, waitSlotList);
        }

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
        Map<TornUserDO, List<TornFactionOcUserDO>> paramMap = new TreeMap<>(Comparator.comparing(TornUserDO::getId));
        idleUsersMap.forEach((k, v) -> paramMap.put(userMap.get(k), v));

        // 5. 获取推荐结果, 如果已经加入的坑位已经是最佳选择, 从推荐列表里去掉
        Map<TornUserDO, OcRecommendationVO> recommendMap = assignUserList(factionId, paramMap);
        for (TornFactionOcSlotDO slot : waitSlotList) {
            TornUserDO user = userMap.get(slot.getUserId());
            OcRecommendationVO recommend = recommendMap.get(user);
            if (recommend != null &&
                    recommend.getOcId().equals(slot.getOcId()) &&
                    recommend.getRecommendedPosition().equals(slot.getPosition())) {
                recommendMap.remove(user);
            }
        }

        return recommendMap;
    }

    /**
     * 为OC结束人员推荐队伍和岗位
     *
     * @param userOcMap Key为用户ID, Value为对应的成功率数据
     * @return Key为用户ID, Value为对应推荐OC和岗位
     */
    public Map<TornUserDO, OcRecommendationVO> assignUserList(long factionId,
                                                              Map<TornUserDO, List<TornFactionOcUserDO>> userOcMap) {
        // 1. 查询即将停转的OC（24小时内）
        LocalDateTime threshold = LocalDateTime.now().plusHours(24);
        List<TornFactionOcDO> urgentOcs = ocDao.queryRecrutList(factionId, threshold);
        if (CollectionUtils.isEmpty(urgentOcs)) {
            return Map.of();
        }

        // 2. 构建岗位候选池
        TreeMap<TornFactionOcDO, List<TornFactionOcSlotDO>> vacantSlots = buildVacantSlotMap(urgentOcs);
        if (vacantSlots.isEmpty()) {
            return Map.of();
        }

        // 3. 全局分配
        return allocateGlobally(vacantSlots, userOcMap);
    }

    /**
     * 填充忙碌人员和空转人员
     */
    private void fillBusyAndWaitMember(TornFactionOcDO oc, List<TornFactionOcSlotDO> slotList,
                                       List<Long> busyUserIds, List<TornFactionOcSlotDO> waitSlotList) {
        if (oc.getReadyTime() == null) {
            return;
        }

        List<TornFactionOcSlotDO> currentSlotList = slotList.stream()
                .filter(s -> s.getOcId().equals(oc.getId())).toList();
        if (oc.getReadyTime().isBefore(LocalDateTime.now().plusDays(2))) {
            busyUserIds.addAll(currentSlotList.stream().map(TornFactionOcSlotDO::getUserId).toList());
            return;
        }

        for (TornFactionOcSlotDO slot : currentSlotList) {
            if (BigDecimal.ZERO.compareTo(slot.getProgress()) < 0) {
                busyUserIds.add(slot.getUserId());
            } else if (slot.getUserId() != null) {
                waitSlotList.add(slot);
            }
        }
    }

    /**
     * 构建空闲岗位列表
     */
    private TreeMap<TornFactionOcDO, List<TornFactionOcSlotDO>> buildVacantSlotMap(List<TornFactionOcDO> urgentOcList) {
        List<Long> urgentOcIdList = urgentOcList.stream().map(TornFactionOcDO::getId).toList();
        List<TornFactionOcSlotDO> emptySlotList = ocSlotDao.queryEmptySlotList(urgentOcIdList);

        TreeMap<TornFactionOcDO, List<TornFactionOcSlotDO>> resultMap = new TreeMap<>(
                (o1, o2) -> o2.getReadyTime().compareTo(o1.getReadyTime()));
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
        Map<Long, LocalDateTime> ocReadyMap = HashMap.newHashMap(vacantSlots.size());

        for (Map.Entry<TornUserDO, List<TornFactionOcUserDO>> entry : userOcMap.entrySet()) {
            List<UserMatchScore> candidates = findCandidatesForUser(entry.getKey(), entry.getValue(),
                    vacantSlots, allocatedSlot);
            if (candidates.isEmpty()) {
                continue;
            }

            UserMatchScore bestMatch = candidates.stream()
                    .max(Comparator.comparing(UserMatchScore::score))
                    .orElse(null);
            // 加入过的人要推迟24小时计算, 同时记录状态, 计算完成后恢复原准备时间
            if (!ocReadyMap.containsKey(bestMatch.oc().getId())) {
                ocReadyMap.put(bestMatch.oc().getId(), bestMatch.oc().getReadyTime());
            }

            LocalDateTime originTime = ocReadyMap.get(bestMatch.oc().getId());
            LocalDateTime afterJoinTime = (bestMatch.oc().getReadyTime() == null ||
                    bestMatch.oc().getReadyTime().isBefore(LocalDateTime.now()) ?
                    LocalDateTime.now() : bestMatch.oc().getReadyTime()).plusDays(1);
            bestMatch.oc().setReadyTime(originTime);

            BigDecimal score = bestMatch.score();
            String reason = ocRecommendManager.buildRecommendReason(bestMatch.oc(), bestMatch.matchData().getPassRate());
            result.put(bestMatch.user(), new OcRecommendationVO(bestMatch.oc(), bestMatch.slot(), score, reason));

            allocatedSlot.add(bestMatch.slot().getId());
            bestMatch.oc().setReadyTime(afterJoinTime);
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
        for (Map.Entry<TornFactionOcDO, List<TornFactionOcSlotDO>> entry : ocMap.entrySet()) {
            TornFactionOcDO oc = entry.getKey();
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
                BigDecimal score = ocRecommendManager.calcRecommendScore(user, userOcData, oc, setting, matchedData);
                candidates.add(new UserMatchScore(user, userOcData, matchedData, oc, slot, score));
            }
        }

        return candidates;
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