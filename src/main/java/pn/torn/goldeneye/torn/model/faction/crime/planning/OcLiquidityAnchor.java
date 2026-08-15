package pn.torn.goldeneye.torn.model.faction.crime.planning;

import java.time.LocalDateTime;

/**
 * 时间线上一个已证明的流动性锚点，来自完整完成—释放事件，不能绑定为永久固定OC。
 *
 * @param anchorKey           匿名锚点键，指向产生释放事件的义务
 * @param releaseAt           成员释放时间
 * @param releasedMemberCount 本次释放的成员数量
 * @param replacesPrevious    该锚点是否替换了更早的锚点并保持路径连续
 * @author Bai
 * @version 1.3.0
 * @since 2026.08.15
 */
public record OcLiquidityAnchor(
        String anchorKey,
        LocalDateTime releaseAt,
        int releasedMemberCount,
        boolean replacesPrevious) {
}
