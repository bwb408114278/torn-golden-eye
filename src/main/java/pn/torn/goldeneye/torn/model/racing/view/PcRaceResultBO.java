package pn.torn.goldeneye.torn.model.racing.view;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * PC赛车榜单展示模型。
 *
 * <p>扁平展示模型，不承载数据库映射对象，也不得被直接写入数据库。</p>
 *
 * @param raceId         赛事ID
 * @param businessDate   业务日期
 * @param trackName      赛道名称快照；赛道ID未收录时为null
 * @param startTime      开赛时间（北京时间）
 * @param capturedTime   抓取完成时间（北京时间）
 * @param participants   家族全员榜单，未撞车在前、撞车置底
 * @param fastestLap     最快圈选手；无有效圈速时为null
 * @param crashedList    撞车的家族选手
 * @param allianceCount  家族参赛人数（含撞车）
 * @param totalCount     全部参赛人数（含撞车）
 * @param allianceRate   参赛率百分比，保留两位小数
 * @param drawWinner     抽奖中奖选手；抽奖池为空时为null
 * @param newcomerWinner 新人奖中奖选手；新人池为空时为null
 * @author Bai
 * @version 1.6.3
 * @since 2026.09.15
 */
public record PcRaceResultBO(
        long raceId,
        LocalDate businessDate,
        String trackName,
        LocalDateTime startTime,
        LocalDateTime capturedTime,
        List<PcRaceParticipantVO> participants,
        PcRaceParticipantVO fastestLap,
        List<PcRaceParticipantVO> crashedList,
        int allianceCount,
        int totalCount,
        BigDecimal allianceRate,
        PcRaceParticipantVO drawWinner,
        PcRaceParticipantVO newcomerWinner) {
}
