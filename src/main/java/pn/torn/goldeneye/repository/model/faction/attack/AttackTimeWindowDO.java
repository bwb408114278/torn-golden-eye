package pn.torn.goldeneye.repository.model.faction.attack;

import java.time.LocalDateTime;

/**
 * 对冲时间窗口
 *
 * @param start 开始时间
 * @param end   结束时间
 */
public record AttackTimeWindowDO(LocalDateTime start,
                                 LocalDateTime end) {
    public boolean contains(LocalDateTime dateTime) {
        return !dateTime.isBefore(start) && dateTime.isBefore(end);
    }
}
