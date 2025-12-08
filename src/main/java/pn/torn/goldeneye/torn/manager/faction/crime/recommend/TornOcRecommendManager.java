package pn.torn.goldeneye.torn.manager.faction.crime.recommend;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.constants.torn.TornConstants;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcUserDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcSlotDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.manager.setting.TornSettingOcCoefficientManager;
import pn.torn.goldeneye.torn.manager.setting.TornSettingOcSlotManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * OC队伍推荐公共逻辑层
 *
 * @author Bai
 * @version 0.3.0
 * @since 2025.11.24
 */
@Component
@RequiredArgsConstructor
public class TornOcRecommendManager {
    private final TornSettingOcSlotManager settingOcSlotManager;
    private final TornSettingOcCoefficientManager coefficientManager;

    /**
     * 查询对应的OC岗位配置
     */
    public TornSettingOcSlotDO findSlotSetting(TornFactionOcDO oc, TornFactionOcSlotDO slot) {
        return settingOcSlotManager.getList().stream()
                .filter(s -> s.getOcName().equals(oc.getName()))
                .filter(s -> s.getRank().equals(oc.getRank()))
                .filter(s -> s.getSlotCode().equals(slot.getPosition()))
                .findAny()
                .orElse(null);
    }

    /**
     * 查询对应的用户岗位成功率
     */
    public TornFactionOcUserDO findUserPassRate(List<TornFactionOcUserDO> userOcData, TornFactionOcDO oc,
                                                TornSettingOcSlotDO slotSetting) {
        if (slotSetting == null) {
            return null;
        }

        return userOcData.stream()
                .filter(data -> data.getOcName().equals(oc.getName()))
                .filter(data -> data.getRank().equals(oc.getRank()))
                .filter(data -> data.getPosition().equals(slotSetting.getSlotShortCode()))  // 使用短Code
                .findFirst()
                .orElse(null);
    }

    /**
     * 检测是否大锅饭推荐
     *
     * @return true为推荐大锅饭
     */
    public boolean checkIsReassignRecommended(TornUserDO user, List<TornFactionOcUserDO> userOcData) {
        if (!TornConstants.REASSIGN_OC_FACTION.contains(user.getFactionId())) {
            return false;
        }

        List<TornSettingOcSlotDO> reassignSlotList = settingOcSlotManager.getList().stream()
                .filter(s -> TornConstants.ROTATION_OC_NAME.contains(s.getOcName()))
                .toList();

        boolean isMatch = false;
        for (TornSettingOcSlotDO setting : reassignSlotList) {
            TornFactionOcUserDO matchData = userOcData.stream()
                    .filter(u -> u.getOcName().equals(setting.getOcName()))
                    .filter(u -> u.getPosition().equals(setting.getSlotShortCode()))
                    .filter(u -> u.getPassRate().compareTo(setting.getPassRate()) > -1)
                    .findAny().orElse(null);
            if (matchData != null) {
                isMatch = true;
                break;
            }
        }

        return isMatch;
    }

    /**
     * 计算推荐度评分
     */
    public BigDecimal calcRecommendScore(boolean isReassign, TornFactionOcDO oc, TornSettingOcSlotDO slotSetting,
                                         TornFactionOcUserDO userPassRate) {
        if (isReassign) {
            return calcReassignRecommendScore(oc, slotSetting, userPassRate);
        } else {
            return calcRecommendScore(oc, slotSetting, userPassRate);
        }
    }

    /**
     * 计算推荐度评分
     */
    private BigDecimal calcRecommendScore(TornFactionOcDO oc, TornSettingOcSlotDO slotSetting,
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
     * 计算大锅饭推荐度评分
     */
    private BigDecimal calcReassignRecommendScore(TornFactionOcDO oc, TornSettingOcSlotDO slotSetting,
                                                  TornFactionOcUserDO userPassRate) {
        // 1. 停转时间评分
        BigDecimal timeScore = calculateTimeScore(oc.getReadyTime());
        // 2. 岗位评分, 根据系数、成功率和岗位权重
        BigDecimal coefficient = coefficientManager.getCoefficient(oc.getName(), oc.getRank(),
                slotSetting.getSlotCode(), userPassRate.getPassRate());
        BigDecimal passRateScore = calcPassRateScore(slotSetting, userPassRate);
        BigDecimal priorityScore = calcPriorityScore(slotSetting);
        BigDecimal positionScore = coefficient.multiply(BigDecimal.valueOf(4))
                .add(passRateScore.multiply(priorityScore).multiply(BigDecimal.valueOf(0.1)))
                .add(BigDecimal.valueOf(oc.getRank()));

        // 3. 加权计算：时间80% + 成功率20%
        return timeScore.multiply(BigDecimal.valueOf(0.8))
                .add(positionScore.multiply(BigDecimal.valueOf(0.2)))
                .setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 构建推荐理由
     */
    public String buildRecommendReason(LocalDateTime dateTime, int passRate) {
        List<String> reasons = new ArrayList<>();

        // 停转时间
        if (dateTime != null) {
            LocalDateTime now = LocalDateTime.now();
            long hours = now.compareTo(dateTime) < 1 ?
                    Duration.between(now, dateTime).toHours() + 1 : 0;
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

    /**
     * 计算时间评分
     */
    private BigDecimal calculateTimeScore(LocalDateTime readyTime) {
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
}