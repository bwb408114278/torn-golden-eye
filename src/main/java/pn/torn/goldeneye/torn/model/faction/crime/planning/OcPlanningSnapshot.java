package pn.torn.goldeneye.torn.model.faction.crime.planning;

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
 */
public record OcPlanningSnapshot(long factionId, LocalDateTime snapshotTime,
                                 OcFactionPlanningPolicy policy,
                                 List<TornFactionOcDO> activeOcs,
                                 Map<Long, List<TornFactionOcSlotDO>> slotsByOcId,
                                 List<OcMemberCandidate> members,
                                 Map<String, TornSettingOcPlanProfileDO> profiles,
                                 List<TornSettingOcChainDO> chains,
                                 Map<String, List<OcPlanSlot>> slotTemplates,
                                 Set<String> invalidOcKeys,
                                 List<String> warnings) {
    public OcPlanningSnapshot {
        activeOcs = List.copyOf(activeOcs);
        slotsByOcId = Map.copyOf(slotsByOcId);
        members = List.copyOf(members);
        profiles = Map.copyOf(profiles);
        chains = List.copyOf(chains);
        slotTemplates = Map.copyOf(slotTemplates);
        invalidOcKeys = Set.copyOf(invalidOcKeys);
        warnings = List.copyOf(warnings);
    }

    public static String ocKey(int rank, String name) {
        return rank + ":" + name;
    }
}
