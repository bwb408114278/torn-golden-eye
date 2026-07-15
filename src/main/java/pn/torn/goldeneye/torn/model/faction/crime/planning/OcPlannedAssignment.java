package pn.torn.goldeneye.torn.model.faction.crime.planning;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 一条可执行的人员入队安排。
 */
public record OcPlannedAssignment(long userId, String nickname, String slotCode,
                                  int passRate, int requiredPassRate,
                                  LocalDateTime joinAt, LocalDateTime stageCompleteAt,
                                  BigDecimal coefficient) {
}
