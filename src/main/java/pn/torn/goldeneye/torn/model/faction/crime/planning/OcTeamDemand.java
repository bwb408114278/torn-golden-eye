package pn.torn.goldeneye.torn.model.faction.crime.planning;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 一个现有或待启动OC的岗位需求。
 */
public record OcTeamDemand(long ocId, String ocName, int rank, LocalDateTime readyAt,
                           LocalDateTime expiresAt, boolean chain,
                           List<OcPlanSlot> slots, Set<String> fixedSlotCodes,
                           Set<Long> fixedMemberIds) {

    public OcTeamDemand(long ocId, String ocName, int rank, LocalDateTime readyAt,
                        LocalDateTime expiresAt, boolean chain,
                        List<OcPlanSlot> slots, Set<String> fixedSlotCodes) {
        this(ocId, ocName, rank, readyAt, expiresAt, chain, slots, fixedSlotCodes, Set.of());
    }

    public OcTeamDemand {
        slots = slots == null ? List.of() : List.copyOf(slots);
        fixedSlotCodes = fixedSlotCodes == null ? Set.of() : Set.copyOf(fixedSlotCodes);
        fixedMemberIds = fixedMemberIds == null ? Set.of() : Set.copyOf(fixedMemberIds);
    }

    public List<OcPlanSlot> getVacantSlots() {
        return slots.stream().filter(slot -> !fixedSlotCodes.contains(slot.code())).toList();
    }
}
