package pn.torn.goldeneye.torn.service.faction.oc.image;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.model.faction.oc.image.OcImageSlotStatusEnum;
import pn.torn.goldeneye.torn.model.faction.oc.image.OcImageTimeStatusEnum;
import pn.torn.goldeneye.utils.DateTimeUtils;
import pn.torn.goldeneye.utils.image.document.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 将当前OC及推荐/分配数据组装为声明式表格文档。
 * <p>
 * 该组件只处理已经批量查询好的数据，不访问DAO、Torn API或渲染器，也不拼接HTML或布局空格。
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
    private static final String STATUS_LEGEND = "状态说明：💤 空转 ｜ ⏳ 准备中 ｜ ✅ 准备完成 ｜ ⚠️ 缺少道具";
    private static final String FOOTER_SEPARATOR = " ｜ ";
    private final OcImageStatusResolver statusResolver;
    private final OcImageTitleFormatter titleFormatter;
    private final OcRecommendBadgeResolver recommendBadgeResolver;

    /**
     * 组装普通当前OC表格。
     *
     * @param title      图片标题
     * @param ocList     已按业务顺序排列的OC
     * @param slotByOcId 按OC ID分组的槽位
     * @param userMap    按用户ID索引的用户
     * @param footer     既有更新时间页脚原料，可为空
     * @param now        图片构建时的固定当前时间
     * @return 表格文档
     */
    public TableDocument assemble(String title, List<TornFactionOcDO> ocList,
                                  Map<Long, List<TornFactionOcSlotDO>> slotByOcId,
                                  Map<Long, TornUserDO> userMap, String footer,
                                  LocalDateTime now) {
        List<Block> blocks = ocList.stream()
                .map(oc -> new Block(oc, slotByOcId.getOrDefault(oc.getId(), List.of()),
                        oc.getName(), null, null, null))
                .toList();
        return assembleDocument(title, blocks, userMap, footer, now, DisplayMode.CURRENT);
    }

    /**
     * 组装推荐或分配表格。
     *
     * @param title   图片标题
     * @param blocks  已按推荐/分配顺序排列的OC块
     * @param userMap 按用户ID索引的用户
     * @param now     图片构建时的固定当前时间
     * @return 表格文档
     */
    public TableDocument assemble(String title, List<Block> blocks,
                                  Map<Long, TornUserDO> userMap, LocalDateTime now) {
        return assembleDocument(title, blocks, userMap, null, now, DisplayMode.RECOMMENDATION);
    }

    /**
     * 按展示模式统一组装表格文档。
     *
     * @param title            图片标题
     * @param blocks           OC表格块
     * @param userMap          按用户ID索引的用户
     * @param updateTimeFooter 当前OC查询的既有更新时间页脚原料，推荐/分配不适用
     * @param now              图片构建时的固定当前时间
     * @param mode             展示模式
     * @return 表格文档
     */
    private TableDocument assembleDocument(String title, List<Block> blocks, Map<Long, TornUserDO> userMap,
                                           String updateTimeFooter, LocalDateTime now, DisplayMode mode) {
        int maxSlotCount = blocks.stream().mapToInt(block -> block.slots().size()).max().orElse(0);
        int columnCount = maxSlotCount + 1;
        List<TableRow> rows = new ArrayList<>();
        rows.add(new TableRow(List.of(TableCell.plainText(title, TableCellStyleEnum.TITLE, 1, columnCount,
                TableTextOverflowEnum.WRAP))));
        for (Block block : blocks) {
            rows.addAll(buildBlockRows(block, userMap, now, columnCount, mode));
        }
        if (rows.size() == 1) {
            rows.add(new TableRow(List.of(TableCell.plainText("暂无记录", TableCellStyleEnum.FOOTER,
                    1, columnCount, TableTextOverflowEnum.WRAP))));
        }
        rows.add(new TableRow(List.of(TableCell.plainText(buildFooter(mode, updateTimeFooter),
                TableCellStyleEnum.FOOTER, 1, columnCount, TableTextOverflowEnum.WRAP))));
        return new TableDocument(title, rows, DOCUMENT_WIDTH, DOCUMENT_TYPE);
    }

    /**
     * 组装单个OC块的表格行。
     *
     * @param block       OC表格块
     * @param userMap     按用户ID索引的用户
     * @param now         图片构建时的固定当前时间
     * @param columnCount 表格列数
     * @param mode        展示模式
     * @return OC块对应的表格行
     */
    private List<TableRow> buildBlockRows(Block block, Map<Long, TornUserDO> userMap,
                                          LocalDateTime now, int columnCount, DisplayMode mode) {
        TornFactionOcDO oc = block.oc();
        List<TornFactionOcSlotDO> slots = block.slots().stream()
                .sorted(Comparator.comparing((TornFactionOcSlotDO slot) -> slot.getUserId() == null ? 1 : 0)
                        .thenComparing(TornFactionOcSlotDO::getPosition, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        List<TableCell> positionCells = new ArrayList<>();
        positionCells.add(TableCell.plainText(teamText(oc), teamStyle(oc, now), 2, 1,
                TableTextOverflowEnum.CLIP));
        List<TableCell> memberCells = new ArrayList<>();
        for (TornFactionOcSlotDO slot : slots) {
            boolean recommended = slot.getUserId() == null && isRecommendedPosition(block, slot);
            positionCells.add(positionCell(slot, recommended, mode));
            memberCells.add(memberCell(slot, recommended, userMap, mode));
        }
        fillCells(positionCells, columnCount);
        fillCells(memberCells, Math.max(0, columnCount - 1), TableCellStyleEnum.MEMBER_EMPTY);
        if (memberCells.isEmpty()) {
            memberCells.add(TableCell.plainText("", TableCellStyleEnum.MEMBER_EMPTY, 1, 1,
                    TableTextOverflowEnum.WRAP));
        }
        return List.of(new TableRow(List.of(sectionCell(block, oc, now, columnCount, mode))),
                new TableRow(positionCells), new TableRow(memberCells));
    }

    /**
     * 按展示模式组装OC块副标题单元格。
     *
     * @param block       OC表格块
     * @param oc          OC数据
     * @param now         图片构建时的固定当前时间
     * @param columnCount 表格列数
     * @param mode        展示模式
     * @return 副标题单元格
     */
    private TableCell sectionCell(Block block, TornFactionOcDO oc, LocalDateTime now,
                                  int columnCount, DisplayMode mode) {
        if (mode == DisplayMode.RECOMMENDATION) {
            return recommendSectionCell(block, columnCount);
        }
        return currentSectionCell(block, oc, now, columnCount);
    }

    /**
     * 组装当前OC副标题：OC名称为主文本，非空时间文案渲染为状态徽章。
     *
     * @param block       OC表格块
     * @param oc          OC数据
     * @param now         图片构建时的固定当前时间
     * @param columnCount 表格列数
     * @return 副标题单元格
     */
    private TableCell currentSectionCell(Block block, TornFactionOcDO oc, LocalDateTime now, int columnCount) {
        OcImageTitleFormatter.Description description = titleFormatter.describe(
                oc.getStatus(), oc.getReadyTime(), now);
        if (description.text().isEmpty()) {
            return TableCell.plainText(block.sectionText(), TableCellStyleEnum.SECTION,
                    1, columnCount, TableTextOverflowEnum.WRAP);
        }
        return TableCell.badgeText(block.sectionText(), description.text(),
                badgeTone(description.timeStatus()), TableCellStyleEnum.SECTION,
                1, columnCount, TableTextOverflowEnum.WRAP);
    }

    /**
     * 组装推荐/分配副标题：不使用OC查询时间胶囊，评分与理由经解析器生成推荐自己的徽章。
     *
     * @param block       OC表格块
     * @param columnCount 表格列数
     * @return 副标题单元格
     */
    private TableCell recommendSectionCell(Block block, int columnCount) {
        List<TableCellContent.Badge> badges = recommendBadgeResolver.buildBadges(
                block.recommendScore(), block.reason());
        if (badges.isEmpty()) {
            return TableCell.plainText(block.sectionText(), TableCellStyleEnum.SECTION,
                    1, columnCount, TableTextOverflowEnum.WRAP);
        }
        return TableCell.badgeText(block.sectionText(), badges, TableCellStyleEnum.SECTION,
                1, columnCount, TableTextOverflowEnum.WRAP);
    }

    /**
     * 将标题时间状态映射为受控徽章色调。
     *
     * @param timeStatus 标题时间状态
     * @return 徽章色调
     */
    private TableCellBadgeToneEnum badgeTone(OcImageTimeStatusEnum timeStatus) {
        return switch (timeStatus) {
            case PLANNED -> TableCellBadgeToneEnum.SUCCESS;
            case IDLE -> TableCellBadgeToneEnum.INFO;
            case STOP_COUNTDOWN -> TableCellBadgeToneEnum.WARNING;
            case STOPPED -> TableCellBadgeToneEnum.DANGER;
            case NONE -> throw new IllegalStateException("无时间状态不渲染徽章");
        };
    }

    /**
     * 组装岗位行单元格：成员状态Emoji居左、岗位名居中、成功率居右。
     *
     * @param slot        OC岗位槽位
     * @param recommended 是否推荐目标岗位
     * @param mode        展示模式
     * @return 岗位行单元格
     */
    private TableCell positionCell(TornFactionOcSlotDO slot, boolean recommended, DisplayMode mode) {
        boolean occupied = slot.getUserId() != null;
        OcImageSlotStatusEnum status = statusResolver.resolve(slot.getUserId(), slot.getProgress(),
                slot.getRequiredItemId(), slot.getRequiredItemAvailable());
        String passRate = occupied && slot.getPassRate() != null ? slot.getPassRate().toString() : "";
        return TableCell.threePartText(status.getEmoji(), normalize(slot.getPosition()), passRate,
                positionStyle(occupied, recommended, mode), 1, 1, TableTextOverflowEnum.CLIP);
    }

    /**
     * 组装人员行单元格：仅展示昵称[ID]或空缺，状态Emoji不在人员行重复展示。
     *
     * @param slot        OC岗位槽位
     * @param recommended 是否推荐目标岗位
     * @param userMap     按用户ID索引的用户
     * @param mode        展示模式
     * @return 人员行单元格
     */
    private TableCell memberCell(TornFactionOcSlotDO slot, boolean recommended,
                                 Map<Long, TornUserDO> userMap, DisplayMode mode) {
        if (slot.getUserId() == null) {
            return TableCell.plainText("空缺", emptyMemberStyle(recommended, mode),
                    1, 1, TableTextOverflowEnum.ELLIPSIS);
        }
        TornUserDO user = userMap.get(slot.getUserId());
        String name = user == null ? String.valueOf(slot.getUserId()) : user.getNickname();
        return TableCell.plainText(name + "[" + slot.getUserId() + "]",
                TableCellStyleEnum.MEMBER_FILLED, 1, 1, TableTextOverflowEnum.ELLIPSIS);
    }

    /**
     * 选择空缺人员格样式：当前表为暖橙告警；推荐表推荐目标列与岗位格同为青绿，普通空缺保持中性灰。
     *
     * @param recommended 是否推荐目标岗位
     * @param mode        展示模式
     * @return 空缺人员格样式
     */
    private TableCellStyleEnum emptyMemberStyle(boolean recommended, DisplayMode mode) {
        if (mode == DisplayMode.CURRENT) {
            return TableCellStyleEnum.CURRENT_MEMBER_EMPTY;
        }
        return recommended ? TableCellStyleEnum.SLOT_RECOMMENDED : TableCellStyleEnum.MEMBER_EMPTY;
    }

    /**
     * 判断空槽是否为本块推荐目标岗位。
     *
     * @param block OC表格块
     * @param slot  OC岗位槽位
     * @return 是推荐目标岗位时返回true
     */
    private boolean isRecommendedPosition(Block block, TornFactionOcSlotDO slot) {
        return block.recommendedPosition() != null
                && normalize(block.recommendedPosition()).equals(normalize(slot.getPosition()));
    }

    /**
     * 根据岗位是否有人、是否推荐岗位和展示模式选择样式。
     *
     * @param occupied    岗位是否有人
     * @param recommended 是否推荐目标岗位
     * @param mode        展示模式
     * @return 岗位单元格样式
     */
    private TableCellStyleEnum positionStyle(boolean occupied, boolean recommended, DisplayMode mode) {
        if (occupied) {
            return TableCellStyleEnum.SLOT_FILLED;
        }
        if (mode == DisplayMode.CURRENT) {
            return TableCellStyleEnum.CURRENT_SLOT_EMPTY;
        }
        return recommended ? TableCellStyleEnum.SLOT_RECOMMENDED : TableCellStyleEnum.SLOT_IDLE;
    }

    /**
     * 统一生成图例页脚：当前表为图例加既有更新时间，推荐/分配仅图例。
     *
     * @param mode             展示模式
     * @param updateTimeFooter 既有更新时间页脚原料
     * @return 页脚文本
     */
    private String buildFooter(DisplayMode mode, String updateTimeFooter) {
        if (mode == DisplayMode.RECOMMENDATION || updateTimeFooter == null || updateTimeFooter.isBlank()) {
            return STATUS_LEGEND;
        }
        return STATUS_LEGEND + FOOTER_SEPARATOR + updateTimeFooter;
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
            cells.add(TableCell.plainText("", style, 1, 1, TableTextOverflowEnum.WRAP));
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
     * 表格展示模式，只表达视觉上下文，不暴露到Strategy、DAO、VO或持久化层。
     */
    private enum DisplayMode {
        /**
         * 当前OC查询。
         */
        CURRENT,
        /**
         * 推荐或分配。
         */
        RECOMMENDATION
    }

    /**
     * 一个OC图片表格块。
     *
     * @param oc                  OC数据
     * @param slots               当前OC槽位
     * @param sectionText         分隔行文本
     * @param recommendedPosition 本块推荐岗位，可为空
     * @param recommendScore      推荐度评分，仅推荐/分配使用，可为空
     * @param reason              推荐理由原文，仅推荐/分配使用，可为空
     */
    public record Block(
            TornFactionOcDO oc,
            List<TornFactionOcSlotDO> slots,
            String sectionText,
            String recommendedPosition,
            BigDecimal recommendScore,
            String reason) {
    }
}
