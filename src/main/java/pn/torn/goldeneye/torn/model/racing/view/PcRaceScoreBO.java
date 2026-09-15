package pn.torn.goldeneye.torn.model.racing.view;

import java.time.LocalDate;
import java.util.List;

/**
 * PC赛车个人成绩展示模型。
 *
 * @param userId   目标选手Torn用户ID
 * @param nickname 展示昵称
 * @param items    近若干场联盟赛事成绩，按开赛时间降序
 * @author Bai
 * @version 1.6.3
 * @since 2026.09.15
 */
public record PcRaceScoreBO(
        long userId,
        String nickname,
        List<Item> items) {
    /**
     * 单场成绩条目。
     *
     * @param businessDate 赛事业务日期
     * @param participant  该场展示数据（含当场SMTH名次）
     */
    public record Item(
            LocalDate businessDate,
            PcRaceParticipantVO participant) {
    }
}
