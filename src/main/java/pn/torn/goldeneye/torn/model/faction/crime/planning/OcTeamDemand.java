package pn.torn.goldeneye.torn.model.faction.crime.planning;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 一个现有或待启动OC的岗位需求。
 *
 * @param ocId 现有OC ID；条件性新队不存在ID时为null
 * @param ocName OC名称
 * @param rank OC等级
 * @param readyAt 当前下一准备阶段时间；尚无人加入时为null
 * @param expiresAt 空OC首位成员最晚加入时间；已有成员时为null
 * @param chain 所属高阶链节点序列
 * @param slots 完整岗位需求
 * @param fixedSlotCodes 已有成员占用的固定岗位编码集合
 * @param fixedMemberIds 已有成员用户ID集合
 * @author Bai
 * @version 1.2.10
 * @since 2026.07.17
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

    /**
     * 筛选未被旧队固定成员占用的岗位。
     *
     * @return 当前仍需匹配的岗位列表
     */
    public List<OcPlanSlot> getVacantSlots() {
        return slots.stream().filter(slot -> !fixedSlotCodes.contains(slot.code())).toList();
    }
}
