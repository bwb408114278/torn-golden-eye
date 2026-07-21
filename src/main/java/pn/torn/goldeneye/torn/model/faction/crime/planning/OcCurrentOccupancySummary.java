package pn.torn.goldeneye.torn.model.faction.crime.planning;

/**
 * 当前全部现实OC及达标成员占用摘要。
 *
 * @param currentTeamCount 当前现实OC队伍数量
 * @param joinedTeamCount 至少已有一名成员的队伍数量
 * @param emptyTeamCount 完全无人的队伍数量
 * @param occupiedMemberCount 当前OC实际占用的去重成员数量
 * @param qualifiedMemberCount 满足任一计划内OC岗位实际门槛的成员数量
 * @param occupiedQualifiedMemberCount 已被当前OC占用的达标成员数量
 * @param idleQualifiedMemberCount 当前未被OC占用的达标成员数量
 * @author Bai
 * @version 1.2.11
 * @since 2026.07.17
 */
public record OcCurrentOccupancySummary(int currentTeamCount,
                                        int joinedTeamCount,
                                        int emptyTeamCount,
                                        int occupiedMemberCount,
                                        int qualifiedMemberCount,
                                        int occupiedQualifiedMemberCount,
                                        int idleQualifiedMemberCount) {
}
