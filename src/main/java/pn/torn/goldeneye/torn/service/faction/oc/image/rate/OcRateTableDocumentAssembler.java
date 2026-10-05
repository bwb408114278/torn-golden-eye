package pn.torn.goldeneye.torn.service.faction.oc.image.rate;

import org.springframework.stereotype.Component;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcUserDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionOcSlotDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcSlotDO;
import pn.torn.goldeneye.utils.image.document.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 将成员OC成功率数据组装为声明式表格文档，每个OC占一行。
 * <p>
 * 只处理已经查询、过滤并排序好的数据：不访问DAO或Torn API、不排序、不渲染、不拼接HTML与颜色。
 *
 * @author Bai
 * @version 1.9.0
 * @since 2026.10.05
 */
@Component
public class OcRateTableDocumentAssembler {
    /**
     * 文档宽度：表格内容1176px加左右留白
     */
    private static final int DOCUMENT_WIDTH = 1204;
    private static final String DOCUMENT_TYPE = TableThemeEnum.OC_RATE.getDocumentType();
    private static final String TITLE_TEMPLATE = "%s的OC成功率";
    private static final String EMPTY_TEXT = "";
    private static final String EMPTY_RATE_TEXT = "无记录";
    private static final String REQUIRED_RATE_PREFIX = "要求";
    private static final String STAR_SEPARATOR = " ";
    private static final String LINE_SEPARATOR = "\n";
    /**
     * 链式前置OC的级别，该级别的连锁分组内需再区分前置与普通连锁
     */
    private static final int CHAIN_PREREQUISITE_RANK = 8;
    private static final String FOOTER_TEXT = "色阶：超出要求≥10 ｜ 达到要求 ｜ 低于要求·差距<10 ｜ 低于要求·差距≥10 ｜ 无记录"
            + LINE_SEPARATOR
            + "级别分组：7级及以下·入门 ｜ 8级·核心 ｜ 8级·连锁前置与9~10级·连锁\u3000\u3000⚔️ 影响成功率 ｜ 💰 影响大成功收益（各1~5级，未配置不显示；连锁前置岗位仅展示⚔️）"
            + LINE_SEPARATOR
            + "要求＝目标成员所在帮派的岗位要求（各帮派不同）";

    /**
     * 组装用户OC成功率表文档。
     *
     * @param data 已查询、过滤与排序的组装输入
     * @return 表格文档
     */
    public TableDocument assemble(OcRateTableData data) {
        int columnCount = maxSlotCount(data) + 1;
        String title = String.format(TITLE_TEMPLATE, data.user().getNickname());
        List<TableRow> rows = new ArrayList<>();
        rows.add(new TableRow(List.of(TableCell.plainText(title, TableCellStyleEnum.TITLE,
                1, columnCount, TableTextOverflowEnum.WRAP))));
        for (TornSettingOcDO oc : data.ocList()) {
            List<TornFactionOcUserDO> ocUserList = filterUserRecords(data.ocUserList(), oc.getOcName());
            if (ocUserList.isEmpty()) {
                continue;
            }
            rows.add(buildOcRow(oc, ocUserList, slotsOf(data.allSlotList(), oc.getOcName()),
                    data.factionSlots(), columnCount));
        }
        rows.add(new TableRow(List.of(TableCell.plainText(FOOTER_TEXT, TableCellStyleEnum.FOOTER,
                1, columnCount, TableTextOverflowEnum.WRAP))));
        return new TableDocument(title, rows, DOCUMENT_WIDTH, DOCUMENT_TYPE);
    }

    /**
     * 计算最大岗位列数，只统计有成功率记录、因此会实际成行的OC。
     *
     * @param data 组装输入
     * @return 最大岗位列数，无展示OC时为0
     */
    private int maxSlotCount(OcRateTableData data) {
        int maxSlotCount = 0;
        for (TornSettingOcDO oc : data.ocList()) {
            if (filterUserRecords(data.ocUserList(), oc.getOcName()).isEmpty()) {
                continue;
            }
            maxSlotCount = Math.max(slotsOf(data.allSlotList(), oc.getOcName()).size(), maxSlotCount);
        }
        return maxSlotCount;
    }

    /**
     * 组装单个OC行：级别与名称合并格，其后每岗位一格，尾部以空白格补齐。
     *
     * @param oc           OC设置
     * @param ocUserList   该OC下成员的全部成功率记录
     * @param slotList     该OC的岗位，已按岗位编码排序
     * @param factionSlots 目标帮派的岗位要求覆盖
     * @param columnCount  表格列数
     * @return OC行
     */
    private TableRow buildOcRow(TornSettingOcDO oc, List<TornFactionOcUserDO> ocUserList,
                                List<TornSettingOcSlotDO> slotList,
                                List<TornSettingFactionOcSlotDO> factionSlots, int columnCount) {
        OcRateTierResolver.OcRateGroup group = OcRateTierResolver.group(oc.getRank(), oc.getOcName());
        boolean chainPrerequisite = group == OcRateTierResolver.OcRateGroup.CHAIN
                && oc.getRank() == CHAIN_PREREQUISITE_RANK;
        List<TableCell> cells = new ArrayList<>();
        cells.add(buildOcNameCell(oc, group));
        for (TornSettingOcSlotDO slot : slotList) {
            cells.add(buildSlotCell(slot, ocUserList, factionSlots, chainPrerequisite));
        }
        while (cells.size() < columnCount) {
            cells.add(TableCell.plainText(EMPTY_TEXT, TableCellStyleEnum.SLOT_EMPTY, 1, 1,
                    TableTextOverflowEnum.WRAP));
        }
        return new TableRow(cells);
    }

    /**
     * 组装级别与名称合并格：小字级别分组在上，OC名称在下。
     *
     * @param oc    OC设置
     * @param group 级别分组
     * @return 合并格
     */
    private TableCell buildOcNameCell(TornSettingOcDO oc, OcRateTierResolver.OcRateGroup group) {
        List<TableCellContent.Line> lines = List.of(
                new TableCellContent.Line(OcRateTierResolver.groupLabel(oc.getRank(), group),
                        TableCellContent.LineEmphasis.SUB),
                new TableCellContent.Line(oc.getOcName(), TableCellContent.LineEmphasis.MAIN));
        return new TableCell(new TableCellContent.StackedText(lines), groupStyle(group), 1, 1,
                TableTextOverflowEnum.ELLIPSIS);
    }

    /**
     * 组装岗位格：岗位编码、星级行、成功率数值或"无记录"、帮派要求值自上而下堆叠。
     *
     * @param slot              岗位设置
     * @param ocUserList        该OC下成员的全部成功率记录
     * @param factionSlots      目标帮派的岗位要求覆盖
     * @param chainPrerequisite 是否为链式前置OC的岗位，前置只关心成功不展示收益星级
     * @return 岗位格
     */
    private TableCell buildSlotCell(TornSettingOcSlotDO slot, List<TornFactionOcUserDO> ocUserList,
                                    List<TornSettingFactionOcSlotDO> factionSlots, boolean chainPrerequisite) {
        TornFactionOcUserDO userRecord = ocUserList.stream()
                .filter(userOcRecord -> userOcRecord.getOcName().equals(slot.getOcName()))
                .filter(userOcRecord -> userOcRecord.getPosition().equals(slot.getSlotShortCode()))
                .findAny().orElse(null);
        int requiredPassRate = OcRateTierResolver.requiredPassRate(slot, factionSlots);
        OcRateTierResolver.OcRateValueTier tier = OcRateTierResolver.valueTier(
                userRecord == null ? null : userRecord.getPassRate(), requiredPassRate);
        List<TableCellContent.Line> lines = new ArrayList<>();
        lines.add(new TableCellContent.Line(slot.getSlotCode(), TableCellContent.LineEmphasis.MAIN));
        String stars = buildStars(slot, chainPrerequisite);
        if (!stars.isEmpty()) {
            lines.add(new TableCellContent.Line(stars, TableCellContent.LineEmphasis.NOTE));
        }
        lines.add(buildRateLine(userRecord));
        lines.add(new TableCellContent.Line(REQUIRED_RATE_PREFIX + requiredPassRate,
                TableCellContent.LineEmphasis.NOTE));
        return new TableCell(new TableCellContent.StackedText(lines), rateStyle(tier), 1, 1,
                TableTextOverflowEnum.ELLIPSIS);
    }

    /**
     * 组装成功率数值行，无记录时以次要文本占位。
     *
     * @param userRecord 成员在该岗位的成功率记录，可为null
     * @return 数值行
     */
    private TableCellContent.Line buildRateLine(TornFactionOcUserDO userRecord) {
        if (userRecord == null) {
            return new TableCellContent.Line(EMPTY_RATE_TEXT, TableCellContent.LineEmphasis.SUB);
        }
        return new TableCellContent.Line(String.valueOf(userRecord.getPassRate()),
                TableCellContent.LineEmphasis.EMPHASIS);
    }

    /**
     * 拼装星级行：两段星级均为空时整行省略，链式前置只保留影响成功率的星级段。
     *
     * @param slot              岗位设置
     * @param chainPrerequisite 是否为链式前置OC的岗位
     * @return 星级行文本，无任何星级时返回空串
     */
    private String buildStars(TornSettingOcSlotDO slot, boolean chainPrerequisite) {
        String successStars = OcRateTierResolver.successStars(slot.getPriority());
        if (chainPrerequisite) {
            return successStars;
        }
        String fortuneStars = OcRateTierResolver.fortuneStars(slot.getBestSuccess());
        if (successStars.isEmpty()) {
            return fortuneStars;
        }
        if (fortuneStars.isEmpty()) {
            return successStars;
        }
        return successStars + STAR_SEPARATOR + fortuneStars;
    }

    /**
     * 将级别分组映射为合并格样式。
     *
     * @param group 级别分组
     * @return 合并格样式
     */
    private TableCellStyleEnum groupStyle(OcRateTierResolver.OcRateGroup group) {
        return switch (group) {
            case ENTRY -> TableCellStyleEnum.OC_GROUP_ENTRY;
            case CORE -> TableCellStyleEnum.OC_GROUP_CORE;
            case CHAIN -> TableCellStyleEnum.OC_GROUP_CHAIN;
        };
    }

    /**
     * 将成功率数值档映射为数值格样式。
     *
     * @param tier 成功率数值档
     * @return 数值格样式
     */
    private TableCellStyleEnum rateStyle(OcRateTierResolver.OcRateValueTier tier) {
        return switch (tier) {
            case EXCEED -> TableCellStyleEnum.RATE_EXCEED;
            case PASS -> TableCellStyleEnum.RATE_PASS;
            case FAIL_NEAR -> TableCellStyleEnum.RATE_FAIL_NEAR;
            case FAIL_FAR -> TableCellStyleEnum.RATE_FAIL_FAR;
            case NONE -> TableCellStyleEnum.RATE_NONE;
        };
    }

    /**
     * 取出指定OC的成员成功率记录。
     *
     * @param ocUserList 成员全量成功率记录
     * @param ocName     OC名称
     * @return 该OC下的记录
     */
    private List<TornFactionOcUserDO> filterUserRecords(List<TornFactionOcUserDO> ocUserList, String ocName) {
        return ocUserList.stream()
                .filter(userOcRecord -> userOcRecord.getOcName().equals(ocName))
                .toList();
    }

    /**
     * 取出指定OC的岗位并按岗位编码排序，保证列顺序稳定。
     *
     * @param allSlotList 全量岗位设置
     * @param ocName      OC名称
     * @return 该OC的岗位
     */
    private List<TornSettingOcSlotDO> slotsOf(List<TornSettingOcSlotDO> allSlotList, String ocName) {
        return allSlotList.stream()
                .filter(slot -> slot.getOcName().equals(ocName))
                .sorted(Comparator.comparing(TornSettingOcSlotDO::getSlotCode))
                .toList();
    }
}
