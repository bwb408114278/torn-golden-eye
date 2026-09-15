package pn.torn.goldeneye.torn.model.racing.view;

/**
 * PC赛车选手展示模型。
 *
 * @param userId           选手Torn用户ID
 * @param nickname         抓取时昵称快照
 * @param factionShortName 帮派简称
 * @param smthRank         SMTH内部名次（家族未撞车内序号）；撞车为null
 * @param position         赛事名次（API原始值）
 * @param raceTimeText     完赛用时文本，撞车或缺失为null
 * @param bestLapTimeText  最快圈文本，缺失为null
 * @param crashed          是否撞车
 * @author Bai
 * @version 1.6.3
 * @since 2026.09.15
 */
public record PcRaceParticipantVO(
        long userId,
        String nickname,
        String factionShortName,
        Integer smthRank,
        Integer position,
        String raceTimeText,
        String bestLapTimeText,
        boolean crashed) {
}
