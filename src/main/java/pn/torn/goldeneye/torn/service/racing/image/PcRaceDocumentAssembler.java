package pn.torn.goldeneye.torn.service.racing.image;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import pn.torn.goldeneye.constants.torn.RacingConstants;
import pn.torn.goldeneye.torn.model.racing.view.PcRaceParticipantVO;
import pn.torn.goldeneye.torn.model.racing.view.PcRaceResultBO;
import pn.torn.goldeneye.utils.DateTimeUtils;
import pn.torn.goldeneye.utils.image.document.*;

import java.util.ArrayList;
import java.util.List;

/**
 * 将PC赛车榜单结果组装为PC主题表格文档。
 *
 * <p>该组件只处理已经计算完成的展示模型，不访问DAO、Torn API与渲染器，也不拼接HTML或布局空格。
 * 前三名以奖牌emoji标记SMTH名次，不做整行配色高亮；撞车标记一律使用文字。</p>
 *
 * @author Bai
 * @version 1.6.3
 * @since 2026.09.15
 */
@Component
public class PcRaceDocumentAssembler {
    private static final int DOCUMENT_WIDTH = 1600;
    private static final int COLUMN_COUNT = 8;
    private static final String EMPTY_PLACEHOLDER = "—";
    private static final String CRASHED_TEXT = "撞车";
    private static final String EMPTY_RANK_TEXT = "暂无成绩记录";
    private static final String FOOTER_SEPARATOR = " ｜ ";
    private static final String MEDAL_SEPARATOR = " ";
    private static final String GOLD_MEDAL = "🥇";
    private static final String SILVER_MEDAL = "🥈";
    private static final String BRONZE_MEDAL = "🥉";
    private static final String[] COLUMN_HEADERS =
            {"SMTH名次", "选手ID", "昵称", "帮派", "用时", "最快圈", "创了", "全场名次"};

    /**
     * 组装PC赛车榜单表格文档。
     *
     * @param result 榜单结果
     * @return 表格文档
     */
    public TableDocument assemble(PcRaceResultBO result) {
        String title = buildTitle(result);
        List<TableRow> rows = new ArrayList<>();
        rows.add(new TableRow(List.of(TableCell.plainText(title, TableCellStyleEnum.TITLE,
                1, COLUMN_COUNT, TableTextOverflowEnum.WRAP))));
        rows.add(buildHeaderRow());
        if (result.participants().isEmpty()) {
            rows.add(new TableRow(List.of(TableCell.plainText(EMPTY_RANK_TEXT, TableCellStyleEnum.FOOTER,
                    1, COLUMN_COUNT, TableTextOverflowEnum.WRAP))));
        } else {
            result.participants().forEach(participant -> rows.add(buildParticipantRow(participant)));
        }
        rows.add(new TableRow(List.of(TableCell.plainText(buildFooter(result), TableCellStyleEnum.FOOTER,
                1, COLUMN_COUNT, TableTextOverflowEnum.WRAP))));
        return new TableDocument(title, rows, DOCUMENT_WIDTH, TableThemeEnum.PC_RACE.getDocumentType());
    }

    /**
     * 构建图片标题，形如{@code 每日PC大赛成绩-Docks (2026-09-14)}；赛道缺失时省略赛道段。
     *
     * @param result 榜单结果
     * @return 图片标题
     */
    private String buildTitle(PcRaceResultBO result) {
        StringBuilder title = new StringBuilder(RacingConstants.RACE_TITLE_PREFIX);
        if (StringUtils.hasText(result.trackName())) {
            title.append('-').append(result.trackName());
        }
        return title.append(" (").append(DateTimeUtils.convertToString(result.businessDate())).append(')')
                .toString();
    }

    /**
     * 构建8列表头行。
     *
     * @return 表头行
     */
    private TableRow buildHeaderRow() {
        List<TableCell> cells = new ArrayList<>(COLUMN_COUNT);
        for (String header : COLUMN_HEADERS) {
            cells.add(TableCell.plainText(header, TableCellStyleEnum.HEADER, 1, 1, TableTextOverflowEnum.WRAP));
        }
        return new TableRow(cells);
    }

    /**
     * 构建单行榜单数据，前三名整行使用名次样式。
     *
     * @param participant 选手展示模型
     * @return 榜单行
     */
    private TableRow buildParticipantRow(PcRaceParticipantVO participant) {
        TableCellStyleEnum style = resolveRowStyle(participant);
        List<TableCell> cells = new ArrayList<>(COLUMN_COUNT);
        cells.add(buildCell(buildRankText(participant.smthRank()), style));
        cells.add(buildCell(String.valueOf(participant.userId()), style));
        cells.add(buildCell(formatValue(participant.nickname()), style, TableTextOverflowEnum.ELLIPSIS));
        cells.add(buildCell(formatValue(participant.factionShortName()), style));
        cells.add(buildCell(formatValue(participant.raceTimeText()), style));
        cells.add(buildCell(formatValue(participant.bestLapTimeText()), style));
        cells.add(buildCell(participant.crashed() ? CRASHED_TEXT : "", style));
        cells.add(buildCell(formatValue(participant.position()), style));
        return new TableRow(cells);
    }

    /**
     * 构建SMTH名次文本，前三名前置奖牌emoji。
     *
     * @param smthRank SMTH内部名次
     * @return 名次文本；名次缺失时返回占位符
     */
    private String buildRankText(Integer smthRank) {
        if (smthRank == null) {
            return EMPTY_PLACEHOLDER;
        }

        String medal = switch (smthRank) {
            case 1 -> GOLD_MEDAL;
            case 2 -> SILVER_MEDAL;
            case 3 -> BRONZE_MEDAL;
            default -> "";
        };
        return medal.isEmpty() ? String.valueOf(smthRank) : medal + MEDAL_SEPARATOR + smthRank;
    }

    /**
     * 按SMTH名次解析整行样式，撞车行统一使用普通数据行样式。
     *
     * @param participant 选手展示模型
     * @return 行样式
     */
    private TableCellStyleEnum resolveRowStyle(PcRaceParticipantVO participant) {
        if (participant.crashed() || participant.smthRank() == null) {
            return TableCellStyleEnum.BODY;
        }

        return switch (participant.smthRank()) {
            case 1 -> TableCellStyleEnum.RANK_FIRST;
            case 2 -> TableCellStyleEnum.RANK_SECOND;
            case 3 -> TableCellStyleEnum.RANK_THIRD;
            default -> TableCellStyleEnum.BODY;
        };
    }

    /**
     * 以固定溢出策略构建数据单元格。
     *
     * @param text  单元格文本
     * @param style 行样式
     * @return 数据单元格
     */
    private TableCell buildCell(String text, TableCellStyleEnum style) {
        return buildCell(text, style, TableTextOverflowEnum.WRAP);
    }

    /**
     * 以指定溢出策略构建数据单元格。
     *
     * @param text     单元格文本
     * @param style    行样式
     * @param overflow 文字溢出策略
     * @return 数据单元格
     */
    private TableCell buildCell(String text, TableCellStyleEnum style, TableTextOverflowEnum overflow) {
        return TableCell.plainText(text, style, 1, 1, overflow);
    }

    /**
     * 将可空值渲染为占位符文本。
     *
     * @param value 值
     * @return 文本；值为null或空白时返回占位符
     */
    private String formatValue(Object value) {
        if (value == null) {
            return EMPTY_PLACEHOLDER;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? EMPTY_PLACEHOLDER : text;
    }

    /**
     * 构建页脚，展示抓取时间与联盟参赛人数。
     *
     * @param result 榜单结果
     * @return 页脚文本
     */
    private String buildFooter(PcRaceResultBO result) {
        String capturedTimeText = result.capturedTime() == null
                ? EMPTY_PLACEHOLDER : DateTimeUtils.convertToString(result.capturedTime());
        return "抓取时间：" + capturedTimeText + FOOTER_SEPARATOR + "联盟参赛人数：" + result.allianceCount();
    }
}
