package pn.torn.goldeneye.torn.service.faction.oc.recommend;

import com.baomidou.mybatisplus.core.toolkit.CollectionUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import pn.torn.goldeneye.constants.torn.TornConstants;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcSlotDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcUserDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcUserDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcSlotDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.manager.setting.TornSettingOcSlotManager;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.OcRecommendationVO;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.OcSlotDictBO;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * OC队伍推荐基础逻辑层
 *
 * @author Bai
 * @version 0.3.0
 * @since 2025.11.01
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TornOcRecommendService {
    private final TornSettingOcSlotManager settingOcSlotManager;
    private final TornFactionOcDAO ocDao;
    private final TornFactionOcSlotDAO ocSlotDao;
    private final TornFactionOcUserDAO ocUserDao;

    /**
     * 为用户推荐OC队伍和岗位，权重：停转时间 > 成功率
     *
     * @param topN 返回Top N个推荐
     */
    public List<OcRecommendationVO> recommendOcForUser(TornUserDO user, int topN, OcSlotDictBO joinedOc,
                                                       List<TornFactionOcUserDO> userOcData) {
        // 1. 查询所有招募中的OC
        List<TornFactionOcDO> recruitOcList = findRecrutList(user.getFactionId(), joinedOc);
        if (CollectionUtils.isEmpty(recruitOcList)) {
            return List.of();
        }

        // 2. 查询所有未满员的OC
        List<TornFactionOcSlotDO> emptySlotList = findEmptySlotList(recruitOcList, joinedOc);
        if (CollectionUtils.isEmpty(emptySlotList)) {
            return List.of();
        }

        // 3. 为每个OC的每个空闲岗位计算推荐度
        List<OcRecommendationVO> recommendations = new ArrayList<>();
        for (TornFactionOcDO oc : recruitOcList) {
            // 查询当前OC下所有空闲岗位
            List<TornFactionOcSlotDO> vacantSlots = emptySlotList.stream()
                    .filter(s -> s.getOcId().equals(oc.getId())).toList();
            // 尝试匹配每个空闲岗位
            for (TornFactionOcSlotDO slot : vacantSlots) { // 查询岗位最低成功率要求
                TornSettingOcSlotDO slotSetting = settingOcSlotManager.getList().stream()
                        .filter(s -> s.getOcName().equals(oc.getName()))
                        .filter(s -> s.getRank().equals(oc.getRank()))
                        .filter(s -> s.getSlotCode().equals(slot.getPosition()))
                        .findAny().orElse(null);
                // 检查成功率是否达标
                if (slotSetting == null) {
                    log.error("未找到岗位配置: {}-{}-{}", oc.getName(), oc.getRank(), slot.getPosition());
                    continue;
                }

                // 查找用户在这个OC和岗位的成功率数据, 检查成功率是否达标
                TornFactionOcUserDO matchedData = userOcData.stream()
                        .filter(data -> data.getOcName().equals(oc.getName()))
                        .filter(data -> data.getRank().equals(oc.getRank()))
                        .filter(data -> data.getPosition().equals(slotSetting.getSlotShortCode()))  // 使用短Code
                        .findFirst().orElse(null);
                if (matchedData == null || matchedData.getPassRate() < slotSetting.getPassRate()) {
                    continue;
                }

                // 计算推荐度评分
                BigDecimal recommendScore = calculateRecommendScore(oc, slotSetting, matchedData);
                String recommentReason = buildRecommendReason(oc, matchedData.getPassRate());
                recommendations.add(new OcRecommendationVO(oc, slot, recommendScore, recommentReason));
            }
        }

        // 4. 按推荐度排序，返回Top N
        return recommendations.stream()
                .sorted(Comparator.comparing(OcRecommendationVO::getRecommendScore).reversed())
                .limit(topN)
                .toList();
    }

    /**
     * 为空闲人员推荐队伍和岗位-
     * 找出所有空闲人员（未参加OC且成功率达标），为他们推荐最合适的队伍
     *
     * @return 用户ID -> 推荐信息的映射
     */
    public Map<Long, OcRecommendationVO> recommendForIdleUsers() {
        // 1. 查询即将停转的OC（24小时内）
        LocalDateTime threshold = LocalDateTime.now().plusHours(24);
        List<TornFactionOcDO> urgentOcs = ocDao.queryRecrutList(TornConstants.FACTION_PN_ID, threshold);
        if (CollectionUtils.isEmpty(urgentOcs)) {
            return Map.of();
        }

        // 2. 收集所有空闲岗位
        List<Long> urgentOcIdList = urgentOcs.stream().map(TornFactionOcDO::getId).toList();
        List<TornFactionOcSlotDO> emptySlotList = ocSlotDao.queryEmptySlotList(urgentOcIdList);
        List<OcSlotDictBO> vacantSlots = new ArrayList<>();
        for (TornFactionOcDO oc : urgentOcs) {
            List<TornFactionOcSlotDO> slots = emptySlotList.stream()
                    .filter(s -> s.getOcId().equals(oc.getId()))
                    .toList();
            for (TornFactionOcSlotDO slot : slots) {
                vacantSlots.add(new OcSlotDictBO(oc, slot));
            }
        }

        if (vacantSlots.isEmpty()) {
            return Map.of();
        }

        // 3. 查询所有正在参与OC的用户ID
        List<Long> ocIdList = ocDao.queryExecutingOc(TornConstants.FACTION_PN_ID).stream()
                .map(TornFactionOcDO::getId).toList();
        List<Long> busyUserIds = ocSlotDao.queryWorkingSlotList(ocIdList).stream()
                .map(TornFactionOcSlotDO::getUserId)
                .distinct().toList();

        // 4. 查询所有帮派成员的OC能力数据
        List<TornFactionOcUserDO> allUsers = ocUserDao.lambdaQuery()
                .eq(TornFactionOcUserDO::getFactionId, TornConstants.FACTION_PN_ID)
                .list();

        // 5. 筛选空闲用户
        Map<Long, List<TornFactionOcUserDO>> idleUsersMap = allUsers.stream()
                .filter(user -> !busyUserIds.contains(user.getUserId()))
                .collect(Collectors.groupingBy(TornFactionOcUserDO::getUserId));

        // 6. 为每个空闲用户匹配最佳岗位
        Map<Long, OcRecommendationVO> recommendations = new HashMap<>();
        for (Map.Entry<Long, List<TornFactionOcUserDO>> entry : idleUsersMap.entrySet()) {
            Long userId = entry.getKey();
            List<TornFactionOcUserDO> userOcData = entry.getValue();

            OcRecommendationVO bestRecommendation = null;
            BigDecimal bestScore = BigDecimal.ZERO;

            // 遍历所有空闲岗位，找到最佳匹配
            for (OcSlotDictBO vacantSlot : vacantSlots) {
                // 检查用户是否有这个OC和岗位的数据
                TornFactionOcUserDO matchedData = userOcData.stream()
                        .filter(data -> data.getOcName().equals(vacantSlot.getOc().getName())
                                && data.getRank().equals(vacantSlot.getOc().getRank())
                                && data.getPosition().equals(vacantSlot.getSlot().getPosition()))
                        .findFirst().orElse(null);
                if (matchedData == null) {
                    continue;
                }

                // 检查成功率要求
                TornSettingOcSlotDO slotSetting = settingOcSlotManager.getList().stream()
                        .filter(s -> s.getOcName().equals(vacantSlot.getOc().getName()))
                        .filter(s -> s.getRank().equals(vacantSlot.getOc().getRank()))
                        .filter(s -> s.getSlotCode().equals(vacantSlot.getSlot().getPosition()))
                        .findAny().orElse(null);
                if (slotSetting == null || matchedData.getPassRate() < slotSetting.getPassRate()) {
                    continue;
                }

                // 计算评分
                BigDecimal score = calculateRecommendScore(vacantSlot.getOc(), slotSetting, matchedData);

                // 更新最佳推荐
                if (score.compareTo(bestScore) > 0) {
                    bestScore = score;
                    String recommendReason = buildRecommendReason(vacantSlot.getOc(), matchedData.getPassRate());
                    bestRecommendation = new OcRecommendationVO(vacantSlot.getOc(), vacantSlot.getSlot(),
                            score, recommendReason);
                }
            }

            if (bestRecommendation != null) {
                recommendations.put(userId, bestRecommendation);
            }
        }

        log.info("为{}个空闲用户生成了推荐", recommendations.size());
        return recommendations;
    }

    /**
     * 查找招募中的OC列表
     */
    public List<TornFactionOcDO> findRecrutList(long factionId, OcSlotDictBO joinedOcSlot) {
        List<TornFactionOcDO> recruitOcList = ocDao.queryRecrutList(factionId, null);
        if (joinedOcSlot == null) {
            return recruitOcList;
        }

        // 针对于空转进度的，按照进度跑完时间减一天去计算 如果OC已经进入Planning状态, 手动放到recruit列表中
        TornFactionOcDO joinedOc = joinedOcSlot.getOc();
        TornFactionOcDO calcOc = recruitOcList.stream()
                .filter(o -> o.getId().equals(joinedOc.getId()))
                .findAny().orElse(null);
        if (calcOc != null) {
            calcOc.setReadyTime(calcOc.getReadyTime().minusDays(-1));
        } else {
            joinedOc.setReadyTime(joinedOc.getReadyTime().minusDays(-1));
            recruitOcList.add(joinedOc);
        }
        return recruitOcList;
    }

    /**
     * 查找招募中的OC空位
     */
    public List<TornFactionOcSlotDO> findEmptySlotList(List<TornFactionOcDO> recruitOcList,
                                                       OcSlotDictBO joinedOcSlot) {
        List<Long> recrutIdList = recruitOcList.stream().map(TornFactionOcDO::getId).toList();
        List<TornFactionOcSlotDO> emptySlotList = ocSlotDao.queryEmptySlotList(recrutIdList);
        if (joinedOcSlot != null) {
            emptySlotList.add(joinedOcSlot.getSlot());
        }


        return emptySlotList;
    }

    /**
     * 计算推荐度评分
     */
    protected BigDecimal calculateRecommendScore(TornFactionOcDO oc, TornSettingOcSlotDO slotSetting,
                                                 TornFactionOcUserDO userPassRate) {
        BigDecimal passRateScore = calcPassRateScore(slotSetting, userPassRate);
        BigDecimal priorityScore = calcPriorityScore(slotSetting);
        BigDecimal rankScore = BigDecimal.valueOf(10).multiply(BigDecimal.valueOf(oc.getRank()));
        BigDecimal positionScore = passRateScore.multiply(priorityScore)
                .multiply(BigDecimal.valueOf(0.1))
                .add(rankScore);

        BigDecimal timeScore = calculateTimeScore(LocalDateTime.now());
        return positionScore.multiply(BigDecimal.valueOf(0.8))
                .add(timeScore.multiply(BigDecimal.valueOf(0.2)));
    }

    /**
     * 计算时间评分
     */
    protected BigDecimal calculateTimeScore(LocalDateTime readyTime) {
        if (readyTime == null) {
            return BigDecimal.valueOf(100); // 新队, 满分
        }

        LocalDateTime now = LocalDateTime.now();
        long hoursUntilReady = now.compareTo(readyTime) < 1 ? Duration.between(now, readyTime).toHours() + 1 : 0;

        // 已经停转 - 最高优先级
        if (hoursUntilReady <= 0) {
            return BigDecimal.valueOf(100);
        }
        // 6小时内 - 极高优先级
        if (hoursUntilReady <= 6) {
            return BigDecimal.valueOf(100 - hoursUntilReady * 2)
                    .max(BigDecimal.valueOf(95));
        }
        // 24小时内 - 高优先级，加速递减
        if (hoursUntilReady <= 24) {
            // 从95分降到65分
            return BigDecimal.valueOf(95 - (hoursUntilReady - 6) * 1.67)
                    .max(BigDecimal.valueOf(65));
        }
        // 48小时内 - 中等优先级
        if (hoursUntilReady <= 48) {
            // 从65分降到35分
            return BigDecimal.valueOf(65 - (hoursUntilReady - 24) * 1.25)
                    .max(BigDecimal.valueOf(35));
        }
        // 72小时内 - 低优先级
        if (hoursUntilReady <= 72) {
            return BigDecimal.valueOf(35 - (hoursUntilReady - 48) * 0.5)
                    .max(BigDecimal.valueOf(20));
        }
        // 72小时以上 - 极低优先级
        return BigDecimal.valueOf(20 - (hoursUntilReady - 72) * 0.2)
                .max(BigDecimal.valueOf(10));
    }

    /**
     * 计算成功率得分
     */
    private BigDecimal calcPassRateScore(TornSettingOcSlotDO slotSetting, TornFactionOcUserDO userPassRate) {
        int ability = userPassRate.getPassRate() - slotSetting.getPassRate();
        if (ability >= 10) {
            return BigDecimal.valueOf(10);
        } else if (ability >= 5) {
            return BigDecimal.valueOf(8);
        } else {
            return BigDecimal.ONE;
        }
    }

    /**
     * 计算权重得分
     */
    private BigDecimal calcPriorityScore(TornSettingOcSlotDO slotSetting) {
        if (slotSetting.getPriority() >= 25) {
            return BigDecimal.valueOf(5);
        } else if (slotSetting.getPriority() >= 20) {
            return BigDecimal.valueOf(4);
        } else if (slotSetting.getPriority() >= 15) {
            return BigDecimal.valueOf(3);
        } else if (slotSetting.getPriority() >= 10) {
            return BigDecimal.valueOf(2);
        } else {
            return BigDecimal.ONE;
        }
    }

    /**
     * 构建推荐理由
     */
    private String buildRecommendReason(TornFactionOcDO oc, int passRate) {
        List<String> reasons = new ArrayList<>();

        // 停转时间
        if (oc.getReadyTime() != null) {
            LocalDateTime now = LocalDateTime.now();
            long hours = now.compareTo(oc.getReadyTime()) < 1 ?
                    Duration.between(now, oc.getReadyTime()).toHours() + 1 : 0;
            if (hours <= 0) {
                reasons.add("已停转，急需加入");
            } else {
                reasons.add(String.format("%d小时内停转", hours));
            }
        } else {
            reasons.add("新队");
        }

        // 成功率
        if (passRate >= 75) {
            reasons.add("超高成功率");
        } else if (passRate >= 70) {
            reasons.add("高成功率");
        } else {
            reasons.add("成功率达标");
        }

        return String.join("、", reasons);
    }
}