package pn.torn.goldeneye.torn.model.faction.crime.planning;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 一条可执行的人员入队安排。
 *
 * @param userId 分配成员用户ID
 * @param nickname 分配成员昵称
 * @param slotCode 目标岗位编码
 * @param passRate 成员在目标岗位的成功率
 * @param requiredPassRate 目标岗位最低成功率要求
 * @param joinAt 建议加入岗位的时间
 * @param stageCompleteAt 该岗位准备阶段完成时间
 * @param coefficient 成员在目标岗位的工时评价系数
 */public record OcPlannedAssignment(long userId, String nickname, String slotCode,
                                  int passRate, int requiredPassRate,
                                  LocalDateTime joinAt, LocalDateTime stageCompleteAt,
                                  BigDecimal coefficient) {
}
