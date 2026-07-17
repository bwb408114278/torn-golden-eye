package pn.torn.goldeneye.torn.service.faction.oc.planning;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * OC成员准备阶段时间计算器。
 *
 * @author Bai
 * @version 1.2.10
 * @since 2026.07.17
 */
@NoArgsConstructor(access = AccessLevel.NONE)
public final class OcPreparationTimeCalculator {
    private static final int HOURS_PER_STAGE = 24;

    /**
     * 计算一名新成员加入后的下一阶段时间。
     *
     * <p>若OC尚无人加入，准备阶段从本次加入时间开始；若已有准备阶段，
     * 新成员提前加入时从当前阶段结束时间顺延，停转后加入时从实际加入时间重启。</p>
     *
     * @param currentReadyTime 加入前的下一阶段时间；首人加入时为null
     * @param joinAt 新成员实际加入时间
     * @return 新成员加入后的下一阶段时间
     */
    public static LocalDateTime nextReadyTime(LocalDateTime currentReadyTime,
                                              LocalDateTime joinAt) {
        LocalDateTime stageStart = currentReadyTime == null || joinAt.isAfter(currentReadyTime)
                ? joinAt : currentReadyTime;
        return stageStart.plusHours(HOURS_PER_STAGE);
    }

    /**
     * 计算当前成员全部已加入后，在后续不再停转前提下的理想完成时间。
     *
     * @param currentReadyTime 当前下一阶段时间
     * @param totalSlotCount OC完整岗位数
     * @param joinedCount 当前已加入成员数
     * @return 后续成员均及时加入时的理想完成时间
     */
    public static LocalDateTime idealCompletionTime(LocalDateTime currentReadyTime,
                                                    int totalSlotCount,
                                                    int joinedCount) {
        int remainingStageCount = totalSlotCount - joinedCount;
        return currentReadyTime.plusHours((long) HOURS_PER_STAGE * remainingStageCount);
    }
}
