package pn.torn.goldeneye.torn.service.faction.attack.contribution.image;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import pn.torn.goldeneye.torn.model.faction.attack.contribution.RwContributionReportBO;
import pn.torn.goldeneye.torn.model.faction.attack.contribution.RwContributionRowBO;
import pn.torn.goldeneye.torn.model.faction.attack.contribution.RwContributionWarBO;
import pn.torn.goldeneye.torn.service.faction.attack.contribution.RwContributionCalculator;
import pn.torn.goldeneye.utils.DateTimeUtils;
import pn.torn.goldeneye.utils.image.document.*;

import java.util.ArrayList;
import java.util.List;

/**
 * 将RW真赛贡献榜报告组装为RW贡献榜主题表格文档
 *
 * <p>该组件只处理已经计算完成的展示模型，不访问DAO、Torn API与渲染器。
 * 表格固定为排名/ID/昵称/总分加N个场次列，N为合格场次数（1~3）；
 * 前三名不做特殊底色与奖牌，名次列统一样式。</p>
 *
 * @author Bai
 * @version 1.6.4
 * @since 2026.09.16
 */
@Component
@RequiredArgsConstructor
public class RwContributionDocumentAssembler {
    private static final int DOCUMENT_WIDTH = 1600;
    private static final String TITLE_SUFFIX = "最近RW真赛贡献榜";
    private static final String EMPTY_PLACEHOLDER = "—";
    private static final String UNSETTLED_TEXT = "未结算";
    private static final String FOOTER_SEPARATOR = " ｜ ";
    private static final String LINE_BREAK = "\n";
    private static final String RANK_PREFIX = "第";
    private static final String RANK_SUFFIX = "名";
    private static final String COEFFICIENT_PREFIX = "系数";
    private static final String MULTIPLY_SIGN = "×";
    private static final String EQUALS_SIGN = "=";
    private static final String SPACE = " ";
    private static final String[] FIXED_HEADERS = {"排名", "ID", "昵称", "总分"};

    private final RwContributionCalculator calculator;

    /**
     * 组装RW真赛贡献榜表格文档。
     *
     * @param factionShortName 本帮派简称，取自帮派设置
     * @param report           贡献榜报告，合格场次数为1~3
     * @return 表格文档
     */
    public TableDocument assemble(String factionShortName, RwContributionReportBO report) {
        int columnCount = FIXED_HEADERS.length + report.wars().size();
        String title = factionShortName + TITLE_SUFFIX;
        List<TableRow> rows = new ArrayList<>(report.rows().size() + 3);
        rows.add(new TableRow(List.of(TableCell.plainText(title, TableCellStyleEnum.TITLE,
                1, columnCount, TableTextOverflowEnum.WRAP))));
        rows.add(buildHeaderRow(report.wars()));
        for (int index = 0; index < report.rows().size(); index++) {
            rows.add(buildDataRow(index + 1, report.wars(), report.rows().get(index), columnCount));
        }
        rows.add(new TableRow(List.of(TableCell.plainText(buildFooter(report), TableCellStyleEnum.FOOTER,
                1, columnCount, TableTextOverflowEnum.WRAP))));
        return new TableDocument(title, rows, DOCUMENT_WIDTH, TableThemeEnum.RW_CONTRIBUTION.getDocumentType());
    }

    /**
     * 构建固定列加场次列的表头行，场次列表头为两行式。
     *
     * @param wars 入选场次，按结束时间倒序
     * @return 表头行
     */
    private TableRow buildHeaderRow(List<RwContributionWarBO> wars) {
        List<TableCell> cells = new ArrayList<>(FIXED_HEADERS.length + wars.size());
        for (String header : FIXED_HEADERS) {
            cells.add(buildCell(header, TableCellStyleEnum.HEADER));
        }
        for (RwContributionWarBO war : wars) {
            cells.add(buildCell(buildWarHeader(war), TableCellStyleEnum.HEADER));
        }
        return new TableRow(cells);
    }

    /**
     * 构建场次列表头，第一行为“RW ID 对手简称”，第二行为“对手得分 系数X”。
     *
     * @param war 入选场次
     * @return 两行式表头文本
     */
    private String buildWarHeader(RwContributionWarBO war) {
        return war.rwId() + SPACE + resolveOpponentName(war) + LINE_BREAK
                + war.opponentScore() + SPACE + COEFFICIENT_PREFIX + war.coefficient().toPlainString();
    }

    /**
     * 构建单行榜单数据。
     *
     * @param rank        榜单名次，从1开始
     * @param wars        入选场次，按结束时间倒序
     * @param row         榜单行
     * @param columnCount 表格列数，用于预分配单元格列表
     * @return 数据行
     */
    private TableRow buildDataRow(int rank, List<RwContributionWarBO> wars, RwContributionRowBO row, int columnCount) {
        List<TableCell> cells = new ArrayList<>(columnCount);
        cells.add(buildCell(String.valueOf(rank), TableCellStyleEnum.BODY));
        cells.add(buildCell(String.valueOf(row.userId()), TableCellStyleEnum.BODY));
        cells.add(buildCell(formatText(row.nickname()), TableCellStyleEnum.BODY));
        cells.add(buildCell(row.totalScore().toPlainString(), TableCellStyleEnum.BODY));
        for (RwContributionWarBO war : wars) {
            cells.add(buildCell(buildWarScore(war, row), TableCellStyleEnum.BODY));
        }
        return new TableRow(cells);
    }

    /**
     * 构建单个场次的得分单元格文本。
     *
     * @param war 入选场次
     * @param row 榜单行
     * @return 形如“第9名 92×1.2=110.4”的文本；该场未结算返回“未结算”，未上榜返回占位符
     */
    private String buildWarScore(RwContributionWarBO war, RwContributionRowBO row) {
        if (!war.settled()) {
            return UNSETTLED_TEXT;
        }

        Integer rank = row.rankByRwId().get(war.rwId());
        if (rank == null) {
            return EMPTY_PLACEHOLDER;
        }

        return RANK_PREFIX + rank + RANK_SUFFIX + SPACE + calculator.baseScore(rank)
                + MULTIPLY_SIGN + war.coefficient().toPlainString()
                + EQUALS_SIGN + calculator.warScore(rank, war.opponentScore()).toPlainString();
    }

    /**
     * 构建页脚，标注统计口径、基础分公式与更新时间。
     *
     * @param report 贡献榜报告
     * @return 页脚文本
     */
    private String buildFooter(RwContributionReportBO report) {
        return "统计口径：对手得分>3000的最近3场已结束真赛（当前" + report.wars().size() + "场）"
                + FOOTER_SEPARATOR + "基础分=101−战神榜名次"
                + FOOTER_SEPARATOR + "更新于 " + DateTimeUtils.convertToString(report.buildTime());
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
     * 以固定换行溢出策略构建数据单元格。
     *
     * @param text  单元格文本
     * @param style 单元格语义样式
     * @return 单元格
     */
    private TableCell buildCell(String text, TableCellStyleEnum style) {
        return TableCell.plainText(text, style, 1, 1, TableTextOverflowEnum.WRAP);
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
