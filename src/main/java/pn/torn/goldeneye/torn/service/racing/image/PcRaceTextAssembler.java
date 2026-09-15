package pn.torn.goldeneye.torn.service.racing.image;

import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import pn.torn.goldeneye.constants.torn.RacingConstants;
import pn.torn.goldeneye.torn.model.racing.view.PcRaceParticipantVO;
import pn.torn.goldeneye.torn.model.racing.view.PcRaceResultBO;
import pn.torn.goldeneye.torn.model.racing.view.PcRaceScoreBO;
import pn.torn.goldeneye.utils.DateTimeUtils;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

/**
 * PC赛车榜单汇总文本与个人成绩文本组装器。
 *
 * <p>选手条目统一渲染为{@code 昵称 [用户ID] (第N名)}，便于群内直接复制用户ID查询；
 * 无成绩时以“无”明确表达，不输出null或空串。</p>
 *
 * @author Bai
 * @version 1.6.3
 * @since 2026.09.15
 */
@Component
public class PcRaceTextAssembler {
    private static final DateTimeFormatter MONTH_DAY_FORMATTER = DateTimeFormatter.ofPattern("MM-dd");
    private static final String NO_DATA_TEXT = "无";
    private static final String NO_NEWCOMER_TEXT = "今天没有新人参赛";
    private static final String EMPTY_PLACEHOLDER = "—";
    private static final String ITEM_SEPARATOR = "、";
    private static final String UNFINISHED_SUFFIX = " (未完赛)";
    private static final String CRASHED_TEXT = "撞车";
    private static final String SPACE_SEPARATOR = " ";

    /**
     * 组装榜单汇总文本。
     *
     * @param result 榜单结果
     * @return 含最快圈、参赛率、Crash名单、抽奖与新人奖结果的文本
     */
    public String assembleSummary(PcRaceResultBO result) {
        return buildTitle(result)
                + "\n最快圈：" + formatFastestLap(result.fastestLap())
                + "\n参赛率：" + result.allianceCount() + "/" + result.totalCount()
                + " = " + result.allianceRate().toPlainString() + "%"
                + "\n💥 Crash：" + formatCrashedList(result.crashedList())
                + "\n🎲 抽奖：" + formatParticipant(result.drawWinner())
                + "\n🎁 新人奖：" + formatNewcomer(result.newcomerWinner());
    }

    /**
     * 构建汇总文本标题行，形如{@code 🏁 SMTHPC-Docks 2026-09-14}；赛道缺失时只保留赛事名。
     *
     * @param result 榜单结果
     * @return 标题行文本
     */
    private String buildTitle(PcRaceResultBO result) {
        StringBuilder title = new StringBuilder("🏁 ").append(RacingConstants.RACE_TITLE);
        if (StringUtils.hasText(result.trackName())) {
            title.append('-').append(result.trackName());
        }
        return title.append(SPACE_SEPARATOR)
                .append(DateTimeUtils.convertToString(result.businessDate())).toString();
    }

    /**
     * 组装个人成绩文本。
     *
     * @param score 个人成绩
     * @return 逐场成绩文本
     */
    public String assembleScore(PcRaceScoreBO score) {
        StringBuilder text = new StringBuilder();
        text.append("🏎 PC成绩 ").append(score.nickname())
                .append("(近").append(score.items().size()).append("场)");
        if (CollectionUtils.isEmpty(score.items())) {
            return text.append("\n").append(NO_DATA_TEXT).toString();
        }

        for (PcRaceScoreBO.Item item : score.items()) {
            text.append("\n").append(formatScoreItem(item));
        }
        return text.toString();
    }

    /**
     * 组装单场成绩条目文本。
     *
     * @param item 单场成绩条目
     * @return 单场成绩文本
     */
    private String formatScoreItem(PcRaceScoreBO.Item item) {
        String dateText = item.businessDate().format(MONTH_DAY_FORMATTER);
        PcRaceParticipantVO participant = item.participant();
        if (participant.crashed()) {
            return dateText + SPACE_SEPARATOR + CRASHED_TEXT;
        }

        return dateText + " 比赛第" + formatValue(participant.position())
                + " SMTH第" + formatValue(participant.smthRank())
                + SPACE_SEPARATOR + formatValue(participant.raceTimeText());
    }

    /**
     * 组装最快圈文本。
     *
     * @param fastestLap 最快圈选手
     * @return 最快圈文本；无有效圈速时返回“无”
     */
    private String formatFastestLap(PcRaceParticipantVO fastestLap) {
        if (fastestLap == null || fastestLap.bestLapTimeText() == null) {
            return NO_DATA_TEXT;
        }

        return formatParticipant(fastestLap) + SPACE_SEPARATOR + fastestLap.bestLapTimeText();
    }

    /**
     * 组装Crash名单文本。
     *
     * @param crashedList 撞车选手
     * @return Crash名单文本；无撞车选手时返回“无”
     */
    private String formatCrashedList(List<PcRaceParticipantVO> crashedList) {
        if (CollectionUtils.isEmpty(crashedList)) {
            return NO_DATA_TEXT;
        }

        return crashedList.stream().map(this::formatParticipant).collect(Collectors.joining(ITEM_SEPARATOR));
    }

    /**
     * 组装新人奖文本，新人池为空时输出固定文案。
     *
     * @param newcomerWinner 新人奖中奖选手
     * @return 新人奖文本；池为空时输出“今天没有新人参赛”
     */
    private String formatNewcomer(PcRaceParticipantVO newcomerWinner) {
        return newcomerWinner == null ? NO_NEWCOMER_TEXT : formatParticipant(newcomerWinner);
    }

    /**
     * 组装单个选手文本，名次缺失时标记未完赛。
     *
     * @param participant 选手展示模型
     * @return 选手文本；选手为null时返回“无”
     */
    private String formatParticipant(PcRaceParticipantVO participant) {
        if (participant == null) {
            return NO_DATA_TEXT;
        }

        String nickname = StringUtils.hasText(participant.nickname())
                ? participant.nickname() : String.valueOf(participant.userId());
        String participantText = nickname + " [" + participant.userId() + "]";
        if (participant.position() == null) {
            return participantText + UNFINISHED_SUFFIX;
        }
        return participantText + " (第" + participant.position() + "名)";
    }

    /**
     * 将可空值渲染为占位符文本。
     *
     * @param value 值
     * @return 文本；值为null时返回占位符
     */
    private String formatValue(Object value) {
        return value == null ? EMPTY_PLACEHOLDER : String.valueOf(value);
    }
}
