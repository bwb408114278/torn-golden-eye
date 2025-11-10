package pn.torn.goldeneye.torn.service.faction.oc.recommend;

import com.baomidou.mybatisplus.core.toolkit.CollectionUtils;
import lombok.AllArgsConstructor;
import lombok.Data;
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
import pn.torn.goldeneye.torn.manager.setting.TornSettingOcCoefficientManager;
import pn.torn.goldeneye.torn.manager.setting.TornSettingOcSlotManager;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.OcRecommendationVO;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * OC队伍推荐逻辑层
 *
 * @author Bai
 * @version 0.3.0
 * @since 2025.11.01
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TornOcRecommendService {
    private final TornSettingOcSlotManager settingOcSlotManager;
    private final TornSettingOcCoefficientManager coefficientManager;
    private final TornFactionOcDAO ocDao;
    private final TornFactionOcSlotDAO ocSlotDao;
    private final TornFactionOcUserDAO ocUserDao;

    /**
     * 为用户推荐OC队伍和岗位，权重：停转时间 > 成功率 > 收益
     *
     * @param topN 返回Top N个推荐
     */
    public List<OcRecommendationVO> recommendOcForUser(long userId, int topN) {
        // 1. 查询用户的成功率数据
        List<TornFactionOcUserDO> userOcData = ocUserDao.lambdaQuery().eq(TornFactionOcUserDO::getUserId, userId).list();
        if (CollectionUtils.isEmpty(userOcData)) {
            return List.of();
        }

        // 2. 查询所有招募中的OC
        List<TornFactionOcDO> recruitOcList = ocDao.queryRecrutingList(TornConstants.FACTION_PN_ID, null);
        if (CollectionUtils.isEmpty(recruitOcList)) {
            return List.of();
        }

        // 3. 查询所有未满员的OC
        List<Long> recrutIdList = recruitOcList.stream().map(TornFactionOcDO::getId).toList();
        List<TornFactionOcSlotDO> emptySlotList = ocSlotDao.queryEmptySlotList(recrutIdList);
        if (CollectionUtils.isEmpty(emptySlotList)) {
            return List.of();
        }

        // 4. 为每个OC的每个空闲岗位计算推荐度
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
                    log.warn("未找到岗位配置: {}-{}-{}", oc.getName(), oc.getRank(), slot.getPosition());
                    continue;
                }

                // 查找用户在这个OC和岗位的成功率数据
                TornFactionOcUserDO matchedData = userOcData.stream()
                        .filter(data -> data.getOcName().equals(oc.getName()))
                        .filter(data -> data.getRank().equals(oc.getRank()))
                        .filter(data -> data.getPosition().equals(slotSetting.getSlotShortCode()))  // 使用短Code
                        .findFirst().orElse(null);
                if (matchedData == null) {
                    continue;
                }

                // 检查成功率是否达标（用户的短Code成功率 >= 该具体岗位的要求）
                if (matchedData.getPassRate() < slotSetting.getPassRate()) {
                    continue;
                }

                // 计算推荐度评分（停转时间 > 成功率）
                BigDecimal recommendScore = calculateRecommendScore(oc, slotSetting, matchedData);
                String recommentReason = buildRecommendReason(oc, matchedData.getPassRate());
                recommendations.add(new OcRecommendationVO(oc, slot, recommendScore, recommentReason));
            }
        }

        // 5. 按推荐度排序，返回Top N
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
        List<TornFactionOcDO> urgentOcs = ocDao.queryRecrutingList(TornConstants.FACTION_PN_ID, threshold);
        if (CollectionUtils.isEmpty(urgentOcs)) {
            return Map.of();
        }

        // 2. 收集所有空闲岗位
        List<Long> urgentOcIdList = urgentOcs.stream().map(TornFactionOcDO::getId).toList();
        List<TornFactionOcSlotDO> emptySlotList = ocSlotDao.queryEmptySlotList(urgentOcIdList);
        List<VacantSlotInfo> vacantSlots = new ArrayList<>();
        for (TornFactionOcDO oc : urgentOcs) {
            List<TornFactionOcSlotDO> slots = emptySlotList.stream()
                    .filter(s -> s.getOcId().equals(oc.getId()))
                    .toList();
            for (TornFactionOcSlotDO slot : slots) {
                vacantSlots.add(new VacantSlotInfo(oc, slot));
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
            for (VacantSlotInfo vacantSlot : vacantSlots) {
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
     * 计算推荐度评分
     * 权重：停转时间(50%) > 成功率(30%) > 收益(20%)
     *
     * @return 推荐度评分（0-100）
     */
    private BigDecimal calculateRecommendScore(TornFactionOcDO oc, TornSettingOcSlotDO slotSetting,
                                               TornFactionOcUserDO userPassRate) {
        // 1. 停转时间评分（70%权重）
        BigDecimal timeScore = calculateTimeScore(oc.getReadyTime());

        // 2. 成功率评分（30%权重）- 直接使用成功率百分比
        BigDecimal coefficient = coefficientManager.getCoefficient(oc.getName(), oc.getRank(),
                slotSetting.getSlotCode(), userPassRate.getPassRate());
        BigDecimal passRateScore = BigDecimal.valueOf(userPassRate.getPassRate())
                .multiply(coefficient);

        // 4. 加权计算
        return timeScore.multiply(BigDecimal.valueOf(0.7))
                .add(passRateScore.multiply(BigDecimal.valueOf(0.3)))
                .setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 计算时间评分（越接近停转时间分数越高）
     */
    private BigDecimal calculateTimeScore(LocalDateTime readyTime) {
        if (readyTime == null) {
            return BigDecimal.valueOf(100); // 新队, 满分
        }

        LocalDateTime now = LocalDateTime.now();
        long hoursUntilReady = Duration.between(now, readyTime).toHours();

        // 已经停转 - 最高优先级
        if (hoursUntilReady <= 0) {
            return BigDecimal.valueOf(100);
        }

        // 24小时内 - 高优先级，线性递减
        if (hoursUntilReady <= 24) {
            return BigDecimal.valueOf(100 - hoursUntilReady * 1.5)
                    .max(BigDecimal.valueOf(60));
        }

        // 48小时内 - 中等优先级
        if (hoursUntilReady <= 48) {
            return BigDecimal.valueOf(60 - (hoursUntilReady - 24) * 1.0)
                    .max(BigDecimal.valueOf(40));
        }

        // 48小时以上 - 低优先级
        return BigDecimal.valueOf(40 - (hoursUntilReady - 48) * 0.5)
                .max(BigDecimal.valueOf(20));
    }

    /**
     * 构建推荐理由
     */
    private String buildRecommendReason(TornFactionOcDO oc, int passRate) {
        List<String> reasons = new ArrayList<>();

        // 停转时间
        if (oc.getReadyTime() != null) {
            long hours = Duration.between(LocalDateTime.now(), oc.getReadyTime()).toHours();
            if (hours <= 0) {
                reasons.add("已停转，急需加入");
            } else if (hours <= 6) {
                reasons.add(String.format("%d小时后停转", hours));
            } else if (hours <= 24) {
                reasons.add("24h内停转");
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

    /**
     * 空闲岗位信息
     */
    @Data
    @AllArgsConstructor
    private static class VacantSlotInfo {
        private TornFactionOcDO oc;
        private TornFactionOcSlotDO slot;
    }
}