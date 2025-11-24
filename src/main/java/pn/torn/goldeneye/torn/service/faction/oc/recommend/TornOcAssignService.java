package pn.torn.goldeneye.torn.service.faction.oc.recommend;

import com.baomidou.mybatisplus.core.toolkit.CollectionUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
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
        // 1. 查询所有正在参与OC的用户ID
        List<Long> ocIdList = ocDao.queryExecutingOc(factionId).stream().map(TornFactionOcDO::getId).toList();
        List<Long> busyUserIds = ocSlotDao.queryWorkingSlotList(ocIdList).stream()
                .map(TornFactionOcSlotDO::getUserId)
                .distinct().toList();

        // 2. 查询所有帮派成员的OC能力数据
        List<TornFactionOcUserDO> allUsers = ocUserDao.lambdaQuery()
                .eq(TornFactionOcUserDO::getFactionId, factionId)
                .list();

        // 3. 筛选空闲用户
        Map<Long, List<TornFactionOcUserDO>> idleUsersMap = allUsers.stream()
                .filter(user -> !busyUserIds.contains(user.getUserId()))
                .collect(Collectors.groupingBy(TornFactionOcUserDO::getUserId));
        if (CollectionUtils.isEmpty(idleUsersMap)) {
            return Map.of();
        }

        // 4. 组装参数
        Map<Long, TornUserDO> userMap = userDao.queryUserMap(idleUsersMap.keySet());
        Map<TornUserDO, List<TornFactionOcUserDO>> paramMap = HashMap.newHashMap(idleUsersMap.size());
        idleUsersMap.forEach((k, v) -> paramMap.put(userMap.get(k), v));

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
        // 1. 查询即将停转的OC（24小时内）
        LocalDateTime threshold = LocalDateTime.now().plusHours(24);
        List<TornFactionOcDO> urgentOcs = ocDao.queryRecrutList(factionId, threshold);
        if (CollectionUtils.isEmpty(urgentOcs)) {
            return Map.of();
        }

        // 2. 构建岗位候选池（带权重信息）
        List<OcSlotWithPriority> vacantSlots = buildVacantSlotList(urgentOcs);
        if (vacantSlots.isEmpty()) {
            return Map.of();
        }

        // 3. 全局分配
        return allocateGlobally(vacantSlots, userOcMap);
    }

    /**
     * 构建空闲岗位列表
     */
    private List<OcSlotWithPriority> buildVacantSlotList(List<TornFactionOcDO> urgentOcList) {
        List<Long> urgentOcIdList = urgentOcList.stream().map(TornFactionOcDO::getId).toList();
        List<TornFactionOcSlotDO> emptySlotList = ocSlotDao.queryEmptySlotList(urgentOcIdList);

        List<OcSlotWithPriority> vacantSlots = new ArrayList<>();
        for (TornFactionOcDO oc : urgentOcList) {
            List<TornFactionOcSlotDO> slots = emptySlotList.stream()
                    .filter(s -> s.getOcId().equals(oc.getId()))
                    .toList();

            for (TornFactionOcSlotDO slot : slots) {
                TornSettingOcSlotDO slotSetting = ocRecommendManager.findSlotSetting(oc, slot);
                if (slotSetting != null) {
                    vacantSlots.add(new OcSlotWithPriority(oc, slot, slotSetting));
                }
            }
        }

        return vacantSlots;
    }

    /**
     * 全局分配算法：先保证不停转，再保证人选合适
     */
    private Map<TornUserDO, OcRecommendationVO> allocateGlobally(List<OcSlotWithPriority> vacantSlots,
                                                                 Map<TornUserDO, List<TornFactionOcUserDO>> userOcMap) {
        Map<TornUserDO, OcRecommendationVO> result = new HashMap<>();
        Set<Long> allocatedUsers = new HashSet<>();
        Set<String> allocatedSlots = new HashSet<>(); // 使用 ocId + slotId 作为key

        // 按OC停转时间排序（越早停转越优先）
        List<OcSlotWithPriority> sortedSlots = vacantSlots.stream()
                .sorted(Comparator
                        .comparing((OcSlotWithPriority s) -> s.oc().getReadyTime() != null ?
                                s.oc().getReadyTime() : LocalDateTime.MAX)
                        .thenComparing((OcSlotWithPriority s) -> s.setting().getPriority(),
                                Comparator.reverseOrder()))
                .toList();

        // 为每个岗位分配最合适的用户
        for (OcSlotWithPriority slotInfo : sortedSlots) {
            String slotKey = slotInfo.oc().getId() + "_" + slotInfo.slot().getId();
            List<UserMatchScore> candidates = findCandidatesForSlot(slotInfo, userOcMap, allocatedUsers);
            if (allocatedSlots.contains(slotKey) || candidates.isEmpty()) {
                continue;
            }

            // 选择最佳候选人（成功率最高）
            UserMatchScore bestMatch = candidates.stream()
                    .max(Comparator.comparing(UserMatchScore::passRate)
                            .thenComparing(UserMatchScore::score))
                    .orElse(null);

            // 分配该岗位
            BigDecimal score = ocRecommendManager.calcRecommendScore(
                    bestMatch.user(), bestMatch.userOcData(), slotInfo.oc(), slotInfo.setting(), bestMatch.matchData());
            String reason = ocRecommendManager.buildRecommendReason(slotInfo.oc(), bestMatch.matchData().getPassRate());

            result.put(bestMatch.user(), new OcRecommendationVO(slotInfo.oc(), slotInfo.slot(), score, reason));

            allocatedUsers.add(bestMatch.user().getId());
            allocatedSlots.add(slotKey);
        }

        log.info("为{}个空闲用户生成了推荐，共分配{}个岗位", result.size(), allocatedSlots.size());
        return result;
    }

    /**
     * 为指定岗位找到所有合格的候选用户
     */
    private List<UserMatchScore> findCandidatesForSlot(OcSlotWithPriority slotInfo,
                                                       Map<TornUserDO, List<TornFactionOcUserDO>> idleUsersMap,
                                                       Set<Long> allocatedUsers) {
        List<UserMatchScore> candidates = new ArrayList<>();
        for (Map.Entry<TornUserDO, List<TornFactionOcUserDO>> entry : idleUsersMap.entrySet()) {
            TornUserDO user = entry.getKey();
            List<TornFactionOcUserDO> userOcData = entry.getValue();
            TornFactionOcUserDO matchedData = ocRecommendManager.findUserPassRate(
                    userOcData, slotInfo.oc(), slotInfo.setting());

            // 跳过已分配的用户, 检查是否满足最低成功率要求
            if (allocatedUsers.contains(user.getId()) ||
                    matchedData == null ||
                    matchedData.getPassRate() < slotInfo.setting().getPassRate()) {
                continue;
            }

            // 计算综合评分
            BigDecimal score = ocRecommendManager.calcRecommendScore(
                    user, entry.getValue(), slotInfo.oc(), slotInfo.setting(), matchedData);
            candidates.add(new UserMatchScore(user, entry.getValue(), matchedData, score, matchedData.getPassRate()));
        }

        return candidates;
    }

    /**
     * 岗位信息
     */
    private record OcSlotWithPriority(
            TornFactionOcDO oc,
            TornFactionOcSlotDO slot,
            TornSettingOcSlotDO setting) {
    }

    /**
     * 用户匹配评分
     */
    private record UserMatchScore(
            TornUserDO user,
            List<TornFactionOcUserDO> userOcData,
            TornFactionOcUserDO matchData,
            BigDecimal score,
            int passRate) {
    }
}