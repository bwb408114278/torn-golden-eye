package pn.torn.goldeneye.torn.service.faction.oc.planning.snapshot;

import org.springframework.stereotype.Component;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcPlanProfileDO;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcCurrentOccupancySummary;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcMemberCandidate;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlanSlot;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlanningSnapshot;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 根据不可变快照统计当前现实OC和达标成员占用情况。
 *
 * @author Bai
 * @version 1.3.0
 * @since 2026.07.17
 */
@Component
public class OcCurrentOccupancyCalculator {
    private static final String READY = "READY";

    /**
     * 计算当前全部现实OC及达标成员占用摘要。
     *
     * @param snapshot 规划快照
     * @return 现实OC和达标成员占用摘要
     */
    public OcCurrentOccupancySummary calculate(OcPlanningSnapshot snapshot) {
        Set<Long> occupiedMemberIds = occupiedMemberIds(snapshot);
        Set<Long> qualifiedMemberIds = qualifiedMemberIds(snapshot);
        long joinedTeamCount = snapshot.activeOcs().stream()
                .filter(oc -> hasJoinedMember(snapshot, oc))
                .count();
        int currentTeamCount = snapshot.activeOcs().size();
        int occupiedQualifiedMemberCount = (int) qualifiedMemberIds.stream()
                .filter(occupiedMemberIds::contains)
                .count();
        return new OcCurrentOccupancySummary(currentTeamCount,
                Math.toIntExact(joinedTeamCount),
                currentTeamCount - Math.toIntExact(joinedTeamCount),
                occupiedMemberIds.size(), qualifiedMemberIds.size(),
                occupiedQualifiedMemberCount,
                qualifiedMemberIds.size() - occupiedQualifiedMemberCount);
    }

    /**
     * 收集全部现实OC当前占用的去重成员ID。
     *
     * @param snapshot 规划快照
     * @return 被当前OC占用的成员ID
     */
    private Set<Long> occupiedMemberIds(OcPlanningSnapshot snapshot) {
        Set<Long> result = new HashSet<>();
        snapshot.slotsByOcId().values().stream().flatMap(List::stream)
                .map(TornFactionOcSlotDO::getUserId)
                .filter(java.util.Objects::nonNull)
                .forEach(result::add);
        return result;
    }

    /**
     * 收集满足任一计划内OC岗位实际门槛的成员ID。
     *
     * @param snapshot 规划快照
     * @return 达标成员ID
     */
    private Set<Long> qualifiedMemberIds(OcPlanningSnapshot snapshot) {
        Set<Long> result = new HashSet<>();
        for (OcMemberCandidate member : snapshot.members()) {
            if (isQualifiedForAnyPlannedSlot(snapshot, member)) {
                result.add(member.userId());
            }
        }
        return result;
    }

    /**
     * 判断成员是否满足任一计划内OC岗位的帮派实际门槛。
     *
     * @param snapshot 规划快照
     * @param member   候选成员
     * @return 至少满足一个岗位门槛时返回true
     */
    private boolean isQualifiedForAnyPlannedSlot(OcPlanningSnapshot snapshot,
                                                 OcMemberCandidate member) {
        for (String key : snapshot.policy().enabledOcKeys()) {
            TornSettingOcPlanProfileDO profile = snapshot.profiles().get(key);
            if (profile == null || !READY.equals(profile.getPlanStatus())
                    || snapshot.invalidOcKeys().contains(key)) {
                continue;
            }
            for (OcPlanSlot slot : snapshot.slotTemplates().getOrDefault(key, List.of())) {
                if (member.getPassRate(profile.getRank(), profile.getOcName(), slot.position())
                        >= slot.requiredPassRate()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 判断现实OC是否至少已有一名成员。
     *
     * @param snapshot 规划快照
     * @param oc       现实OC
     * @return 至少一个岗位已有成员时返回true
     */
    private boolean hasJoinedMember(OcPlanningSnapshot snapshot, TornFactionOcDO oc) {
        return snapshot.slotsByOcId().getOrDefault(oc.getId(), List.of()).stream()
                .map(TornFactionOcSlotDO::getUserId)
                .anyMatch(java.util.Objects::nonNull);
    }
}
