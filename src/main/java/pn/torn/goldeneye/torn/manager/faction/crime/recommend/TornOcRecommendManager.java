package pn.torn.goldeneye.torn.manager.faction.crime.recommend;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcUserDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionOcSlotDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcSlotDO;
import pn.torn.goldeneye.torn.manager.setting.TornSettingFactionOcManager;
import pn.torn.goldeneye.torn.manager.setting.TornSettingOcSlotManager;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.OcSlotEligibility;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * OC队伍推荐公共逻辑层
 *
 * <p>本类只保留配置查询、可填性判定与推荐理由拼装：排序已统一由机会成本排序器承担，
 * 不再提供任何评分方法，也不得按「是否在大锅饭名单」分叉出第二套公式。</p>
 *
 * @author Bai
 * @version 1.9.3
 * @since 2025.11.24
 */
@Component
@RequiredArgsConstructor
public class TornOcRecommendManager {
    /**
     * 成功率展示分档：达到该值展示「超高成功率」。
     */
    private static final int EXCELLENT_PASS_RATE = 75;
    /**
     * 成功率展示分档：达到该值展示「高成功率」。
     */
    private static final int HIGH_PASS_RATE = 70;

    private final TornSettingFactionOcManager settingFactionOcManager;
    private final TornSettingOcSlotManager settingOcSlotManager;

    /**
     * 查询对应的OC岗位配置
     */
    public TornSettingOcSlotDO findSlotSetting(long factionId, TornFactionOcDO oc, TornFactionOcSlotDO slot) {
        // 1. 检查帮派是否禁用整个OC
        if (settingFactionOcManager.isOcDisabled(factionId, oc)) {
            return null;
        }

        return findSlotRequirement(factionId, oc, slot);
    }

    /**
     * 查询OC是否被帮派禁用。
     *
     * @param factionId 帮派ID
     * @param oc        OC数据
     * @return true表示已禁用
     */
    public boolean isOcDisabled(long factionId, TornFactionOcDO oc) {
        return settingFactionOcManager.isOcDisabled(factionId, oc);
    }

    /**
     * 查询岗位成功率要求，不判断OC是否被帮派禁用。
     *
     * @param factionId 帮派ID
     * @param oc        OC数据
     * @param slot      岗位数据
     * @return 岗位配置，不存在时返回null
     */
    public TornSettingOcSlotDO findSlotRequirement(long factionId, TornFactionOcDO oc,
                                                   TornFactionOcSlotDO slot) {

        TornSettingOcSlotDO globalSetting = settingOcSlotManager.getList().stream()
                .filter(s -> s.getOcName().equals(oc.getName()))
                .filter(s -> s.getRank().equals(oc.getRank()))
                .filter(s -> s.getSlotCode().equals(slot.getPosition()))
                .findAny().orElse(null);
        if (globalSetting == null) {
            return null;
        }
        // 2. 检查帮派是否有岗位级别覆盖
        TornSettingFactionOcSlotDO override = settingFactionOcManager.getFactionSlot(factionId, oc, slot.getPosition());
        if (override != null) {
            // 克隆并覆盖成功率要求
            return globalSetting.overridePassRate(override.getPassRate());
        }

        return globalSetting;
    }

    /**
     * 查询对应的用户岗位成功率
     */
    public TornFactionOcUserDO findUserPassRate(List<TornFactionOcUserDO> userOcData, TornFactionOcDO oc,
                                                TornSettingOcSlotDO slotSetting) {
        if (slotSetting == null || userOcData == null) {
            return null;
        }

        return userOcData.stream()
                .filter(data -> data.getOcName().equals(oc.getName()))
                .filter(data -> data.getRank().equals(oc.getRank()))
                .filter(data -> data.getPosition().equals(slotSetting.getSlotShortCode()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 判定成员对某个空位的可填性，判定口径由{@link OcSlotEligibility#eligible()}统一持有。
     *
     * @param factionId  帮派ID
     * @param oc         候选OC
     * @param slot       候选空位
     * @param userOcData 成员成功率矩阵，可为空
     * @return 可填性判定结果；岗位未配置或被帮派禁用时返回null
     */
    public OcSlotEligibility resolveEligibility(long factionId, TornFactionOcDO oc, TornFactionOcSlotDO slot,
                                                List<TornFactionOcUserDO> userOcData) {
        TornSettingOcSlotDO requirement = findSlotSetting(factionId, oc, slot);
        if (requirement == null) {
            return null;
        }
        TornFactionOcUserDO passRateData = findUserPassRate(userOcData, oc, requirement);
        return new OcSlotEligibility(requirement, passRateData == null ? null : passRateData.getPassRate());
    }

    /**
     * 构建推荐理由，文案与排序键无关，逐字保持既有输出。
     *
     * @param dateTime 该OC的准备时间，null表示新队
     * @param passRate 展示用的成功率
     * @return 以「、」拼接的推荐理由
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
        if (passRate >= EXCELLENT_PASS_RATE) {
            reasons.add("超高成功率");
        } else if (passRate >= HIGH_PASS_RATE) {
            reasons.add("高成功率");
        } else {
            reasons.add("成功率达标");
        }

        return String.join("、", reasons);
    }
}
