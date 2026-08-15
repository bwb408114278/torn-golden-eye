package pn.torn.goldeneye.torn.model.faction.crime.planning;

import pn.torn.goldeneye.repository.model.faction.oc.OcPlanningRewardStatsDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcChainDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcPlanProfileDO;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一次OC新队规划使用的不可变数据快照。
 *
 * @param factionId     帮派ID
 * @param snapshotTime  快照生成时间
 * @param policy        帮派规划策略
 * @param activeOcs     快照时的活跃OC列表
 * @param slotsByOcId   按OC ID分组的岗位数据
 * @param members       当前帮派候选成员
 * @param profiles      按OC键索引的规划档案
 * @param chains        高阶链关系配置
 * @param slotTemplates 按OC键索引的岗位模板
 * @param invalidOcKeys 未通过配置校验的OC键集合
 * @param rewardStats   按OC键索引的历史收益统计
 * @param warnings      快照构建与配置校验警告
 * @author Bai
 * @version 1.3.0
 * @since 2026.07.15
 */
public record OcPlanningSnapshot(
        long factionId,
        LocalDateTime snapshotTime,
        OcFactionPlanningPolicy policy,
        List<TornFactionOcDO> activeOcs,
        Map<Long, List<TornFactionOcSlotDO>> slotsByOcId,
        List<OcMemberCandidate> members,
        Map<String, TornSettingOcPlanProfileDO> profiles,
        List<TornSettingOcChainDO> chains,
        Map<String, List<OcPlanSlot>> slotTemplates,
        Set<String> invalidOcKeys,
        Map<String, OcPlanningRewardStatsDO> rewardStats,
        List<String> warnings) {
    public OcPlanningSnapshot {
        activeOcs = List.copyOf(activeOcs);
        slotsByOcId = Map.copyOf(slotsByOcId);
        members = List.copyOf(members);
        profiles = Map.copyOf(profiles);
        chains = List.copyOf(chains);
        slotTemplates = Map.copyOf(slotTemplates);
        invalidOcKeys = Set.copyOf(invalidOcKeys);
        rewardStats = rewardStats == null ? Map.of() : Map.copyOf(rewardStats);
        warnings = List.copyOf(warnings);
    }

    /**
     * 构造OC规划配置的统一索引键。
     *
     * @param rank OC等级
     * @param name OC名称
     * @return 格式为等级和OC名称两段组合的索引键
     */
    public static String ocKey(int rank, String name) {
        return rank + ":" + name;
    }
}
