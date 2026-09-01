package pn.torn.goldeneye.torn.service.faction.oc.image;

import org.springframework.stereotype.Component;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcUserDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcSlotDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.utils.image.document.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 组装历史成功率和候选成员表格图片文档。
 *
 * <p>该组装器只转换调用方已经查询、筛选和排序后的领域数据，不解释当前OC状态，
 * 不添加状态Emoji、readyTime或道具快照。</p>
 *
 * @author Bai
 * @version 1.6.0
 * @since 2026.08.31
 */
@Component
public class OcHistoryTableDocumentAssembler {
    private static final int DOCUMENT_WIDTH = 1600;
    private static final String DOCUMENT_TYPE = "oc-history";

    /**
     * 组装可加入OC成员六列表。
     *
     * @param ocUserList 已按业务规则排序的候选成员
     * @param userMap    用户ID到用户信息的映射
     * @return 可加入OC成员表格文档
     */
    public TableDocument buildFreeMemberDocument(List<TornFactionOcUserDO> ocUserList,
                                                 Map<Long, TornUserDO> userMap) {
        List<TableRow> rows = new ArrayList<>();
        rows.add(mergedRow("可加入OC成员", 6, TableCellStyleEnum.TITLE));
        rows.add(row(List.of("Rank", "ID", "Name", "OC名称", "岗位", "成功率"), TableCellStyleEnum.SECTION));
        for (int i = 0; i < ocUserList.size(); i++) {
            TornFactionOcUserDO ocUser = ocUserList.get(i);
            TornUserDO user = userMap.get(ocUser.getUserId());
            rows.add(row(List.of(
                    String.valueOf(i + 1),
                    safeText(ocUser.getUserId() == null ? null : ocUser.getUserId().toString()),
                    user == null ? "未知" : safeText(user.getNickname()),
                    safeText(ocUser.getOcName()),
                    safeText(ocUser.getPosition()),
                    safeText(ocUser.getPassRate() == null ? null : ocUser.getPassRate().toString())), TableCellStyleEnum.MEMBER_FILLED));
        }
        return new TableDocument("可加入OC成员", rows, DOCUMENT_WIDTH, DOCUMENT_TYPE);
    }

    /**
     * 组装用户OC成功率历史表。
     *
     * @param user        查询目标用户
     * @param ocList      已按业务规则排序的OC列表
     * @param allSlotList 全部OC岗位配置
     * @param ocUserList  查询目标用户的成功率数据
     * @return 用户OC成功率表格文档
     */
    public TableDocument buildPassRateDocument(TornUserDO user, List<TornSettingOcDO> ocList,
                                               List<TornSettingOcSlotDO> allSlotList,
                                               List<TornFactionOcUserDO> ocUserList) {
        Map<String, List<TornFactionOcUserDO>> usersByOcName = ocUserList.stream()
                .collect(Collectors.groupingBy(ocUser -> safeText(ocUser.getOcName())));
        Map<String, List<TornSettingOcSlotDO>> slotsByOcName = allSlotList.stream()
                .collect(Collectors.groupingBy(slot -> safeText(slot.getOcName())));
        int columnCount = findMaxColumnCount(ocList, usersByOcName, slotsByOcName);

        List<TableRow> rows = new ArrayList<>();
        rows.add(mergedRow(user.getNickname() + "的OC成功率", columnCount + 1, TableCellStyleEnum.TITLE));
        for (TornSettingOcDO oc : ocList) {
            List<TornFactionOcUserDO> userList = usersByOcName.get(safeText(oc.getOcName()));
            if (userList == null || userList.isEmpty()) {
                continue;
            }
            List<TornSettingOcSlotDO> slotList = new ArrayList<>(
                    slotsByOcName.getOrDefault(safeText(oc.getOcName()), List.of()));
            slotList.sort(Comparator.comparing(TornSettingOcSlotDO::getSlotCode));
            rows.add(sectionRow(safeText(oc.getOcName()), columnCount + 1));
            rows.add(positionRow(oc.getRank(), slotList, columnCount));
            rows.add(passRateRow(slotList, userList, columnCount));
        }
        if (rows.size() == 1) {
            rows.add(row(List.of("暂无记录"), TableCellStyleEnum.FOOTER));
        }
        return new TableDocument(user.getNickname() + "的OC成功率", rows, DOCUMENT_WIDTH, DOCUMENT_TYPE);
    }

    /**
     * 查找参与当前历史表的OC中最大岗位数量。
     *
     * @param ocList        OC列表
     * @param usersByOcName 按OC名称分组的用户记录
     * @param slotsByOcName 按OC名称分组的岗位配置
     * @return 最大岗位数量
     */
    private int findMaxColumnCount(List<TornSettingOcDO> ocList,
                                   Map<String, List<TornFactionOcUserDO>> usersByOcName,
                                   Map<String, List<TornSettingOcSlotDO>> slotsByOcName) {
        return ocList.stream()
                .filter(oc -> usersByOcName.containsKey(safeText(oc.getOcName())))
                .mapToInt(oc -> slotsByOcName.getOrDefault(safeText(oc.getOcName()), List.of()).size())
                .max()
                .orElse(0);
    }

    /**
     * 创建OC名称分隔行。
     *
     * @param ocName      OC名称
     * @param columnCount 表格列数
     * @return 分隔行
     */
    private TableRow sectionRow(String ocName, int columnCount) {
        return new TableRow(List.of(cell(ocName, TableCellStyleEnum.SECTION, 1, columnCount)));
    }

    /**
     * 创建指定列数的合并行。
     *
     * @param text        行文本
     * @param columnCount 合并列数
     * @param style       单元格样式
     * @return 合并行
     */
    private TableRow mergedRow(String text, int columnCount, TableCellStyleEnum style) {
        return new TableRow(List.of(cell(text, style, 1, columnCount)));
    }

    /**
     * 创建岗位名称行，并补齐空岗位单元格。
     *
     * @param rank        OC等级
     * @param slotList    岗位配置
     * @param columnCount 表格列数
     * @return 岗位名称行
     */
    private TableRow positionRow(int rank, List<TornSettingOcSlotDO> slotList, int columnCount) {
        List<TableCell> cells = new ArrayList<>();
        cells.add(cell(rank + "级", TableCellStyleEnum.SECTION, 2, 1));
        for (TornSettingOcSlotDO slot : slotList) {
            cells.add(cell(safeText(slot.getSlotCode()), TableCellStyleEnum.SECTION));
        }
        addEmptyCells(cells, slotList.size(), columnCount, TableCellStyleEnum.SLOT_EMPTY);
        return new TableRow(cells);
    }

    /**
     * 创建岗位成功率行，并补齐空岗位单元格。
     *
     * @param slotList    岗位配置
     * @param userList    用户成功率记录
     * @param columnCount 表格列数
     * @return 成功率行
     */
    private TableRow passRateRow(List<TornSettingOcSlotDO> slotList,
                                 List<TornFactionOcUserDO> userList, int columnCount) {
        Map<String, TornFactionOcUserDO> userByPosition = userList.stream()
                .collect(Collectors.toMap(TornFactionOcUserDO::getPosition, Function.identity(), (first, ignored) -> first));
        List<TableCell> cells = new ArrayList<>();
        cells.add(cell("", TableCellStyleEnum.SLOT_EMPTY));
        for (TornSettingOcSlotDO slot : slotList) {
            TornFactionOcUserDO ocUser = userByPosition.get(slot.getSlotShortCode());
            cells.add(cell(ocUser == null || ocUser.getPassRate() == null ? "暂无" : ocUser.getPassRate().toString(),
                    ocUser == null ? TableCellStyleEnum.SLOT_EMPTY : TableCellStyleEnum.SLOT_FILLED));
        }
        addEmptyCells(cells, slotList.size(), columnCount, TableCellStyleEnum.SLOT_EMPTY);
        return new TableRow(cells);
    }

    /**
     * 补齐表格行中的空单元格。
     *
     * @param cells        当前单元格列表
     * @param currentCount 当前已有单元格数量
     * @param columnCount  目标列数
     * @param style        空单元格样式
     */
    private void addEmptyCells(List<TableCell> cells, int currentCount, int columnCount,
                               TableCellStyleEnum style) {
        for (int i = currentCount; i < columnCount; i++) {
            cells.add(cell("", style));
        }
    }

    /**
     * 使用统一样式创建文本行。
     *
     * @param texts 文本列表
     * @param style 行内单元格样式
     * @return 表格行
     */
    private TableRow row(List<String> texts, TableCellStyleEnum style) {
        return new TableRow(texts.stream().map(text -> cell(text, style)).toList());
    }

    private String safeText(String text) {
        return text == null ? "" : text;
    }

    /**
     * 使用默认跨度创建表格单元格。
     *
     * @param text  单元格文本
     * @param style 单元格样式
     * @return 表格单元格
     */
    private TableCell cell(String text, TableCellStyleEnum style) {
        return cell(text, style, 1, 1);
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
        return new TableCell(text, style, rowSpan, colSpan, TableTextOverflowEnum.WRAP);
    }
}
