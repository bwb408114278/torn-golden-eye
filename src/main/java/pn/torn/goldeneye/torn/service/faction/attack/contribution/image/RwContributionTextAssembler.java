package pn.torn.goldeneye.torn.service.faction.attack.contribution.image;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import pn.torn.goldeneye.torn.model.faction.attack.contribution.RwContributionReportBO;
import pn.torn.goldeneye.torn.model.faction.attack.contribution.RwContributionRowBO;
import pn.torn.goldeneye.torn.model.faction.attack.contribution.RwContributionWarBO;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 将RW真赛贡献榜报告组装为群文本消息
 *
 * <p>文本与图片共用同一份报告模型，只取前若干名，超出时在尾行注明全榜行数，
 * 便于群内直接核对名次而不必打开图片。</p>
 *
 * @author Bai
 * @version 1.6.4
 * @since 2026.09.16
 */
@Component
public class RwContributionTextAssembler {
    /**
     * 文本展示的名次上限，超出部分只在图片中呈现
     */
    private static final int TOP_LIMIT = 20;
    private static final String EMPTY_PLACEHOLDER = "—";
    private static final String UNSETTLED_TEXT = "未结算";
    private static final String WAR_LINE_PREFIX = "▍";
    private static final String TITLE_PREFIX = "【";
    private static final String TITLE_MIDDLE = " RW真赛贡献榜】最近3场真赛";
    private static final String WAR_SEPARATOR = "｜";
    private static final String COEFFICIENT_PREFIX = "系数";
    private static final String RANK_SEPARATOR = "/";
    private static final String RANK_SUFFIX = "名）";
    private static final String RANK_DETAIL_PREFIX = "（";
    private static final String TAIL_PREFIX = "…（全榜";
    private static final String TAIL_MIDDLE = "行，此为前";
    private static final String TAIL_SUFFIX = "）";
    private static final String SPACE = " ";
    private static final String RANK_LINE_SEPARATOR = ". ";

    /**
     * 组装贡献榜群文本消息。
     *
     * @param factionShortName 本帮派简称，取自帮派设置
     * @param report           贡献榜报告
     * @return 逐行文本
     */
    public String assemble(String factionShortName, RwContributionReportBO report) {
        StringBuilder text = new StringBuilder();
        text.append(TITLE_PREFIX).append(factionShortName).append(TITLE_MIDDLE);
        for (RwContributionWarBO war : report.wars()) {
            text.append('\n').append(buildWarLine(war));
        }

        List<RwContributionRowBO> rows = report.rows();
        int limit = Math.min(TOP_LIMIT, rows.size());
        for (int index = 0; index < limit; index++) {
            text.append('\n').append(buildRankLine(index + 1, report.wars(), rows.get(index)));
        }
        if (rows.size() > TOP_LIMIT) {
            text.append('\n').append(TAIL_PREFIX).append(rows.size()).append(TAIL_MIDDLE).append(TOP_LIMIT)
                    .append(TAIL_SUFFIX);
        }

        return text.toString();
    }

    /**
     * 构建单场场次行。
     *
     * @param war 入选场次
     * @return 形如“▍48522 DA｜22625 系数1.2”的文本
     */
    private String buildWarLine(RwContributionWarBO war) {
        return WAR_LINE_PREFIX + war.rwId() + SPACE + resolveOpponentName(war) + WAR_SEPARATOR
                + war.opponentScore() + SPACE + COEFFICIENT_PREFIX + war.coefficient().toPlainString();
    }

    /**
     * 构建单行名次文本。
     *
     * @param rank 榜单名次，从1开始
     * @param wars 入选场次，按结束时间倒序
     * @param row  榜单行
     * @return 形如“1. Cinderine 280.0（1/1/1名）”的文本
     */
    private String buildRankLine(int rank, List<RwContributionWarBO> wars, RwContributionRowBO row) {
        String rankDetail = wars.stream()
                .map(war -> resolveWarRank(war, row))
                .collect(Collectors.joining(RANK_SEPARATOR));
        return rank + RANK_LINE_SEPARATOR + formatText(row.nickname()) + SPACE + row.totalScore().toPlainString()
                + RANK_DETAIL_PREFIX + rankDetail + RANK_SUFFIX;
    }

    /**
     * 解析成员在单场的名次展示值。
     *
     * @param war 入选场次
     * @param row 榜单行
     * @return 名次数字；该场未结算返回“未结算”，未上榜返回占位符
     */
    private String resolveWarRank(RwContributionWarBO war, RwContributionRowBO row) {
        if (!war.settled()) {
            return UNSETTLED_TEXT;
        }

        Integer rank = row.rankByRwId().get(war.rwId());
        return rank == null ? EMPTY_PLACEHOLDER : String.valueOf(rank);
    }

    /**
     * 解析对手展示名称，简称缺失时回退全名。
     *
     * @param war 入选场次
     * @return 对手展示名称；简称与全名均缺失时返回占位符
     */
    private String resolveOpponentName(RwContributionWarBO war) {
        if (StringUtils.hasText(war.opponentShortName())) {
            return war.opponentShortName();
        }
        return formatText(war.opponentName());
    }

    /**
     * 将可空值渲染为占位符文本。
     *
     * @param value 值
     * @return 文本；值为null或空白时返回占位符
     */
    private String formatText(String value) {
        return StringUtils.hasText(value) ? value : EMPTY_PLACEHOLDER;
    }
}
