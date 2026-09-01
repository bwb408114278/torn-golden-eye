package pn.torn.goldeneye.torn.service.faction.oc.image;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.model.faction.oc.image.OcImageSlotStatusEnum;
import pn.torn.goldeneye.utils.DateTimeUtils;
import pn.torn.goldeneye.utils.image.document.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 将当前OC及推荐/分配数据组装为声明式表格文档。
 * <p>
 * 该组件只处理已经批量查询好的数据，不访问DAO、Torn API或渲染器。
 *
 * @author Bai
 * @version 1.6.0
 * @since 2026.08.31
 */
@Component
@RequiredArgsConstructor
public class OcTableDocumentAssembler {
    private static final int DOCUMENT_WIDTH = 1600;
    private static final String DOCUMENT_TYPE = "oc-table";
    private final OcImageStatusResolver statusResolver;
    private final OcImageTitleFormatter titleFormatter;

    /**
     * 组装普通当前OC表格。
     *
     * @param title      图片标题
     * @param ocList     已按业务顺序排列的OC
     * @param slotByOcId 按OC ID分组的槽位
     * @param userMap    按用户ID索引的用户
     * @param footer     页脚文本，可为空
     * @param now        图片构建时的固定当前时间
     * @return 表格文档
     */
    public TableDocument assemble(String title, List<TornFactionOcDO> ocList,
                                  Map<Long, List<TornFactionOcSlotDO>> slotByOcId,
                                  Map<Long, TornUserDO> userMap, String footer,
                                  LocalDateTime now) {
        List<Block> blocks = ocList.stream()
                .map(oc -> new Block(oc, slotByOcId.getOrDefault(oc.getId(), List.of()),
                        oc.getName(), null))
                .toList();
        return assemble(title, blocks, userMap, footer, now);
    }

    /**
     * 组装推荐或分配表格。
     *
     * @param title   图片标题
     * @param blocks  已按推荐/分配顺序排列的OC块
     * @param userMap 按用户ID索引的用户
     * @param footer  页脚文本，可为空
     * @param now     图片构建时的固定当前时间
     * @return 表格文档
     */
    public TableDocument assemble(String title, List<Block> blocks,
                                  Map<Long, TornUserDO> userMap, String footer,
                                  LocalDateTime now) {
        int maxSlotCount = blocks.stream().mapToInt(block -> block.slots().size()).max().orElse(0);
        int columnCount = maxSlotCount + 1;
        List<TableRow> rows = new ArrayList<>();
        rows.add(new TableRow(List.of(cell(title, TableCellStyleEnum.TITLE, 1, columnCount))));
        for (Block block : blocks) {
            rows.addAll(buildBlockRows(block, userMap, now, columnCount));
        }
        if (rows.size() == 1) {
            rows.add(new TableRow(List.of(cell("暂无记录", TableCellStyleEnum.FOOTER, 1, columnCount))));
        }
        if (footer != null && !footer.isBlank()) {
            rows.add(new TableRow(List.of(cell(footer, TableCellStyleEnum.FOOTER, 1, columnCount))));
        }
        return new TableDocument(title, rows, DOCUMENT_WIDTH, DOCUMENT_TYPE);
    }

    /**
     * 组装单个OC块的表格行。
     *
     * @param block       OC表格块
     * @param userMap     按用户ID索引的用户
     * @param now         图片构建时的固定当前时间
     * @param columnCount 表格列数
     * @return OC块对应的表格行
     */
    private List<TableRow> buildBlockRows(Block block, Map<Long, TornUserDO> userMap,
                                          LocalDateTime now, int columnCount) {
        TornFactionOcDO oc = block.oc();
        List<TornFactionOcSlotDO> slots = block.slots().stream()
                .sorted(Comparator.comparing((TornFactionOcSlotDO slot) -> slot.getUserId() == null ? 1 : 0)
                        .thenComparing(TornFactionOcSlotDO::getPosition, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        String timeText = titleFormatter.format(oc.getStatus(), oc.getReadyTime(), now);
        String sectionText = block.sectionText() + (timeText.isEmpty() ? "" : " " + timeText);
        TableCell section = cell(sectionText, TableCellStyleEnum.SECTION, 1, columnCount);

        List<TableCell> positionCells = new ArrayList<>();
        positionCells.add(cell(teamText(oc), teamStyle(oc, now), 2, 1));
        List<TableCell> memberCells = new ArrayList<>();
        for (TornFactionOcSlotDO slot : slots) {
            boolean occupied = slot.getUserId() != null;
            boolean recommended = !occupied && block.recommendedPosition() != null
                    && normalize(block.recommendedPosition()).equals(normalize(slot.getPosition()));
            positionCells.add(cell(positionText(slot), positionStyle(occupied, recommended), 1, 1));
            memberCells.add(cell(memberText(slot, userMap), occupied
                    ? TableCellStyleEnum.MEMBER_FILLED : TableCellStyleEnum.MEMBER_EMPTY, 1, 1));
        }
        fillCells(positionCells, columnCount);
        fillCells(memberCells, Math.max(0, columnCount - 1), TableCellStyleEnum.MEMBER_EMPTY);
        if (memberCells.isEmpty()) {
            memberCells.add(cell("", TableCellStyleEnum.MEMBER_EMPTY, 1, 1));
        }
        return List.of(new TableRow(List.of(section)), new TableRow(positionCells), new TableRow(memberCells));
    }

    /**
     * 生成团队状态和准备时间文本。
     *
     * @param oc OC数据
     * @return 团队状态文本
     */
    private String teamText(TornFactionOcDO oc) {
        return oc.getStatus() + (oc.getReadyTime() == null ? "" : "\n" + DateTimeUtils.convertToString(oc.getReadyTime()));
    }

    /**
     * 生成岗位名称和成功率文本。
     *
     * @param slot OC岗位槽位
     * @return 岗位文本
     */
    private String positionText(TornFactionOcSlotDO slot) {
        return normalize(slot.getPosition()) + (slot.getPassRate() == null ? "" : " " + slot.getPassRate());
    }

    /**
     * 生成成员展示文本和状态Emoji。
     *
     * @param slot    OC岗位槽位
     * @param userMap 按用户ID索引的用户
     * @return 成员展示文本
     */
    private String memberText(TornFactionOcSlotDO slot, Map<Long, TornUserDO> userMap) {
        if (slot.getUserId() == null) {
            return "空缺";
        }
        TornUserDO user = userMap.get(slot.getUserId());
        String name = user == null ? String.valueOf(slot.getUserId()) : user.getNickname();
        OcImageSlotStatusEnum status = statusResolver.resolve(slot.getUserId(), slot.getProgress(),
                slot.getRequiredItemId(), slot.getRequiredItemAvailable());
        String emoji = status.getEmoji();
        return name + "[" + slot.getUserId() + "]" + (emoji.isEmpty() ? "" : " " + emoji);
    }

    /**
     * 根据OC是否已到准备时间选择团队单元格样式。
     *
     * @param oc  OC数据
     * @param now 图片构建时的固定当前时间
     * @return 团队单元格样式
     */
    private TableCellStyleEnum teamStyle(TornFactionOcDO oc, LocalDateTime now) {
        return oc.getReadyTime() != null && !now.isAfter(oc.getReadyTime())
                ? TableCellStyleEnum.TEAM_READY : TableCellStyleEnum.TEAM_WARNING;
    }

    /**
     * 根据岗位是否有人和是否为推荐岗位选择样式。
     *
     * @param occupied    岗位是否有人
     * @param recommended 岗位是否为推荐岗位
     * @return 岗位单元格样式
     */
    private TableCellStyleEnum positionStyle(boolean occupied, boolean recommended) {
        if (occupied) {
            return TableCellStyleEnum.SLOT_FILLED;
        }
        return recommended ? TableCellStyleEnum.SLOT_RECOMMENDED : TableCellStyleEnum.SLOT_IDLE;
    }

    /**
     * 使用默认空槽样式补齐单元格。
     *
     * @param cells        当前单元格列表
     * @param expectedSize 目标单元格数量
     */
    private void fillCells(List<TableCell> cells, int expectedSize) {
        fillCells(cells, expectedSize, TableCellStyleEnum.SLOT_EMPTY);
    }

    /**
     * 使用指定样式补齐单元格。
     *
     * @param cells        当前单元格列表
     * @param expectedSize 目标单元格数量
     * @param style        补齐单元格样式
     */
    private void fillCells(List<TableCell> cells, int expectedSize, TableCellStyleEnum style) {
        while (cells.size() < expectedSize) {
            cells.add(cell("", style, 1, 1));
        }
    }

    /**
     * 移除岗位名称中的空格以便比较和展示。
     *
     * @param position 岗位名称
     * @return 规范化后的岗位名称
     */
    private String normalize(String position) {
        return position == null ? "" : position.replace(" ", "");
    }

    /**
     * 创建指定跨度和换行策略的表格单元格。
     *
     * @param text    单元格文本
     * @param style   单元格样式
     * @param rowSpan 行跨度
     * @param colSpan 列跨度
     * @return 表格单元格
     */
    private TableCell cell(String text, TableCellStyleEnum style, int rowSpan, int colSpan) {
        return new TableCell(text == null ? "" : text, style, rowSpan, colSpan, TableTextOverflowEnum.WRAP);
    }

    /**
     * 一个OC图片表格块。
     *
     * @param oc                  OC数据
     * @param slots               当前OC槽位
     * @param sectionText         分隔行文本
     * @param recommendedPosition 本块推荐岗位，可为空
     */
    public record Block(
            TornFactionOcDO oc,
            List<TornFactionOcSlotDO> slots,
            String sectionText,
            String recommendedPosition) {
    }
}
