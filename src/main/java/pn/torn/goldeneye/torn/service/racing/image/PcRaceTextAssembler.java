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
 * @author Bai
 * @version 1.6.3
 * @since 2026.09.15
 */
@Component
public class PcRaceTextAssembler {
    private static final DateTimeFormatter MONTH_DAY_FORMATTER = DateTimeFormatter.ofPattern("MM-dd");
    private static final String NO_DATA_TEXT = "无数据";
    private static final String EMPTY_PLACEHOLDER = "—";
    private static final String ITEM_SEPARATOR = "、";
    private static final String UNFINISHED_SUFFIX = "(未完赛)";
    private static final String CRASHED_TEXT = "撞车";

    /**
     * 组装榜单汇总文本。
     *
     * @param result 榜单结果
     * @return 含最快圈、参赛率、Crash名单与抽奖结果的文本
     */
    public String assembleSummary(PcRaceResultBO result) {
        return "🏁 " + RacingConstants.RACE_TITLE + " " + DateTimeUtils.convertToString(result.businessDate())
                + "\n最快圈：" + formatFastestLap(result.fastestLap())
                + "\n参赛率：" + result.allianceCount() + "/" + result.totalCount()
                + " = " + result.allianceRate().toPlainString() + "%"
                + "\n💥 Crash：" + formatCrashedList(result.crashedList())
                + "\n🎲 抽奖：" + formatParticipant(result.drawWinner());
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
            return dateText + " " + CRASHED_TEXT;
        }

        return dateText + " 比赛第" + formatValue(participant.position())
                + " SMTH第" + formatValue(participant.smthRank())
                + " " + formatValue(participant.raceTimeText());
    }

    /**
     * 组装最快圈文本。
     *
     * @param fastestLap 最快圈选手
     * @return 最快圈文本；无有效圈速时返回无数据
     */
    private String formatFastestLap(PcRaceParticipantVO fastestLap) {
        if (fastestLap == null || fastestLap.bestLapTimeText() == null) {
            return NO_DATA_TEXT;
        }

        return formatParticipant(fastestLap) + " " + fastestLap.bestLapTimeText();
    }

    /**
     * 组装Crash名单文本。
     *
     * @param crashedList 撞车选手
     * @return Crash名单文本；无撞车选手时返回无数据
     */
    private String formatCrashedList(List<PcRaceParticipantVO> crashedList) {
        if (CollectionUtils.isEmpty(crashedList)) {
            return NO_DATA_TEXT;
        }

        return crashedList.stream().map(this::formatParticipant).collect(Collectors.joining(ITEM_SEPARATOR));
    }

    /**
     * 组装单个选手文本，名次缺失时标记未完赛。
     *
     * @param participant 选手展示模型
     * @return 选手文本；选手为null时返回无数据
     */
    private String formatParticipant(PcRaceParticipantVO participant) {
        if (participant == null) {
            return NO_DATA_TEXT;
        }

        String nickname = StringUtils.hasText(participant.nickname())
                ? participant.nickname() : String.valueOf(participant.userId());
        if (participant.position() == null) {
            return nickname + UNFINISHED_SUFFIX;
        }
        return nickname + "(第" + participant.position() + "名)";
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
