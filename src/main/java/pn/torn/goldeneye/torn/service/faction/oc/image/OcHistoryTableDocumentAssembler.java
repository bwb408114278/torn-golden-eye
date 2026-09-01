package pn.torn.goldeneye.torn.service.faction.oc.image;

import org.springframework.stereotype.Component;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcUserDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcSlotDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.utils.image.document.TableCell;
import pn.torn.goldeneye.utils.image.document.TableCellStyleEnum;
import pn.torn.goldeneye.utils.image.document.TableDocument;
import pn.torn.goldeneye.utils.image.document.TableRow;
import pn.torn.goldeneye.utils.image.document.TableTextOverflowEnum;

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
     * @param userMap 用户ID到用户信息的映射
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
                    ocUser.getUserId().toString(),
                    user == null ? "未知" : user.getNickname(),
                    ocUser.getOcName(),
                    ocUser.getPosition(),
                    ocUser.getPassRate().toString()), TableCellStyleEnum.MEMBER_FILLED));
        }
        return new TableDocument("可加入OC成员", rows, DOCUMENT_WIDTH, DOCUMENT_TYPE);
    }

    /**
     * 组装用户OC成功率历史表。
     *
     * @param user 查询目标用户
     * @param ocList 已按业务规则排序的OC列表
     * @param allSlotList 全部OC岗位配置
     * @param ocUserList 查询目标用户的成功率数据
     * @return 用户OC成功率表格文档
     */
    public TableDocument buildPassRateDocument(TornUserDO user, List<TornSettingOcDO> ocList,
                                               List<TornSettingOcSlotDO> allSlotList,
                                               List<TornFactionOcUserDO> ocUserList) {
        Map<String, List<TornFactionOcUserDO>> usersByOcName = ocUserList.stream()
                .collect(Collectors.groupingBy(TornFactionOcUserDO::getOcName));
        Map<String, List<TornSettingOcSlotDO>> slotsByOcName = allSlotList.stream()
                .collect(Collectors.groupingBy(TornSettingOcSlotDO::getOcName));
        int columnCount = findMaxColumnCount(ocList, usersByOcName, slotsByOcName);

        List<TableRow> rows = new ArrayList<>();
        rows.add(mergedRow(user.getNickname() + "的OC成功率", columnCount + 1, TableCellStyleEnum.TITLE));
        for (TornSettingOcDO oc : ocList) {
            List<TornFactionOcUserDO> userList = usersByOcName.get(oc.getOcName());
            if (userList == null || userList.isEmpty()) {
                continue;
            }
            List<TornSettingOcSlotDO> slotList = new ArrayList<>(
                    slotsByOcName.getOrDefault(oc.getOcName(), List.of()));
            slotList.sort(Comparator.comparing(TornSettingOcSlotDO::getSlotCode));
            rows.add(sectionRow(oc.getOcName(), columnCount + 1));
            rows.add(positionRow(oc.getRank(), slotList, columnCount));
            rows.add(passRateRow(slotList, userList, columnCount));
        }
        if (rows.size() == 1) {
            rows.add(row(List.of("暂无记录"), TableCellStyleEnum.FOOTER));
        }
        return new TableDocument(user.getNickname() + "的OC成功率", rows, DOCUMENT_WIDTH, DOCUMENT_TYPE);
    }

    private int findMaxColumnCount(List<TornSettingOcDO> ocList,
                                   Map<String, List<TornFactionOcUserDO>> usersByOcName,
                                   Map<String, List<TornSettingOcSlotDO>> slotsByOcName) {
        return ocList.stream()
                .filter(oc -> usersByOcName.containsKey(oc.getOcName()))
                .mapToInt(oc -> slotsByOcName.getOrDefault(oc.getOcName(), List.of()).size())
                .max()
                .orElse(0);
    }

    private TableRow sectionRow(String ocName, int columnCount) {
        return new TableRow(List.of(cell(ocName, TableCellStyleEnum.SECTION, 1, columnCount)));
    }

    private TableRow mergedRow(String text, int columnCount, TableCellStyleEnum style) {
        return new TableRow(List.of(cell(text, style, 1, columnCount)));
    }

    private TableRow positionRow(int rank, List<TornSettingOcSlotDO> slotList, int columnCount) {
        List<TableCell> cells = new ArrayList<>();
        cells.add(cell(rank + "级", TableCellStyleEnum.SECTION, 2, 1));
        cells.addAll(slotList.stream()
                .map(slot -> cell(slot.getSlotCode(), TableCellStyleEnum.SECTION))
                .toList());
        addEmptyCells(cells, slotList.size(), columnCount, TableCellStyleEnum.SLOT_EMPTY);
        return new TableRow(cells);
    }

    private TableRow passRateRow(List<TornSettingOcSlotDO> slotList,
                                 List<TornFactionOcUserDO> userList, int columnCount) {
        Map<String, TornFactionOcUserDO> userByPosition = userList.stream()
                .collect(Collectors.toMap(TornFactionOcUserDO::getPosition, Function.identity(), (first, ignored) -> first));
        List<TableCell> cells = new ArrayList<>();
        cells.add(cell("", TableCellStyleEnum.SLOT_EMPTY));
        for (TornSettingOcSlotDO slot : slotList) {
            TornFactionOcUserDO ocUser = userByPosition.get(slot.getSlotShortCode());
            cells.add(cell(ocUser == null ? "暂无" : ocUser.getPassRate().toString(),
                    ocUser == null ? TableCellStyleEnum.SLOT_EMPTY : TableCellStyleEnum.SLOT_FILLED));
        }
        addEmptyCells(cells, slotList.size(), columnCount, TableCellStyleEnum.SLOT_EMPTY);
        return new TableRow(cells);
    }

    private void addEmptyCells(List<TableCell> cells, int currentCount, int columnCount,
                               TableCellStyleEnum style) {
        for (int i = currentCount; i < columnCount; i++) {
            cells.add(cell("", style));
        }
    }

    private TableRow row(List<String> texts, TableCellStyleEnum style) {
        return new TableRow(texts.stream().map(text -> cell(text, style)).toList());
    }

    private TableCell cell(String text, TableCellStyleEnum style) {
        return cell(text, style, 1, 1);
    }

    private TableCell cell(String text, TableCellStyleEnum style, int rowSpan, int colSpan) {
        return new TableCell(text, style, rowSpan, colSpan, TableTextOverflowEnum.WRAP);
    }
}
