package pn.torn.goldeneye.torn.manager.faction.crime.msg;

import com.google.common.collect.Multimap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.base.model.TableDataBO;
import pn.torn.goldeneye.repository.dao.user.TornUserDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.utils.DateTimeUtils;
import pn.torn.goldeneye.utils.TableImageUtils;

import java.awt.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.List;
import java.util.Queue;

/**
 * OC表格消息公共逻辑
 *
 * @author Bai
 * @version 0.3.0
 * @since 2025.08.15
 */
@Component
@RequiredArgsConstructor
public class TornFactionOcMsgTableManager {
    private final TornUserDAO userDao;
    private static final Color MEMBER_FULL_COLOR = new Color(122, 167, 56);
    private static final Color MEMBER_EMPTY_COLOR = new Color(230, 119, 0);

    /**
     * 绘制OC表格
     *
     * @return 表格数据，第一层为行，第二层为单元格
     */
    public TableDataBO buildOcTable(String title, Multimap<TornFactionOcDO, List<TornFactionOcSlotDO>> ocMap,
                                    Queue<String> splitLineQueue) {
        List<Long> userIdList = new ArrayList<>();
        ocMap.values().forEach(v -> userIdList.addAll(v != null ?
                v.stream().map(TornFactionOcSlotDO::getUserId).filter(Objects::nonNull).toList() : List.of()));
        Map<Long, TornUserDO> userMap = userDao.queryUserMap(userIdList);

        TableImageUtils.TableConfig tableConfig = new TableImageUtils.TableConfig();
        List<List<String>> tableData = new ArrayList<>();
        int columnCount = ocMap.values().stream().max(
                        Comparator.comparingInt(s -> s != null ? s.size() : 0))
                .orElse(List.of()).size();

        List<String> titleRow = new ArrayList<>();
        titleRow.add(title);
        fillEmptyColumn(titleRow, 1, columnCount + 1);
        tableConfig.addMerge(0, 0, 1, columnCount + 1)
                .setCellStyle(0, 0, new TableImageUtils.CellStyle()
                        .setAlignment(TableImageUtils.TextAlignment.CENTER)
                        .setFont(new Font("微软雅黑", Font.BOLD, 30)));
        tableData.add(titleRow);

        int rowIndex = 1;
        for (Map.Entry<TornFactionOcDO, List<TornFactionOcSlotDO>> entry : ocMap.entries()) {
            List<String> splitLine = new ArrayList<>();
            String splitLineString = CollectionUtils.isEmpty(splitLineQueue) ? "" : splitLineQueue.poll();
            splitLine.add(splitLineString);
            fillEmptyColumn(splitLine, 1, columnCount);
            tableData.add(splitLine);

            tableConfig.addMerge(rowIndex, 0, 1, columnCount + 1);
            tableConfig.setCellStyle(rowIndex, 0, new TableImageUtils.CellStyle()
                    .setBgColor(new Color(242, 242, 242))
                    .setFont(new Font("微软雅黑", Font.BOLD, 14)));

            TornFactionOcDO oc = entry.getKey();
            List<TornFactionOcSlotDO> slotList = entry.getValue();

            slotList.sort((o1, o2) -> {
                if (o1.getUserId() != null && o2.getUserId() == null) {
                    return -1;
                } else if (o1.getUserId() == null && o2.getUserId() != null) {
                    return 1;
                } else {
                    return o1.getPosition().compareTo(o2.getPosition());
                }
            });

            tableData.add(buildPositionRow(oc, slotList, rowIndex + 1, columnCount, tableConfig));
            tableData.add(buildMemberRow(slotList, userMap, rowIndex + 1, columnCount, tableConfig));

            rowIndex += 3;
        }

        return new TableDataBO(tableData, tableConfig);
    }

    /**
     * 填充空列
     *
     * @param startIndex  起始列
     * @param columnCount 最大列数
     */
    public void fillEmptyColumn(List<String> rowList, int startIndex, int columnCount) {
        fillEmptyColumn(rowList, startIndex, columnCount, null);
    }

    /**
     * 构建岗位行
     *
     * @param rowIndex    当前行数
     * @param columnCount 最大列数
     */
    public List<String> buildPositionRow(TornFactionOcDO oc, List<TornFactionOcSlotDO> slotList,
                                         int rowIndex, int columnCount,
                                         TableImageUtils.TableConfig tableConfig) {
        List<String> resultList = new ArrayList<>();
        resultList.add(oc.getStatus() +
                (oc.getReadyTime() == null ? "" : "\n" + DateTimeUtils.convertToString(oc.getReadyTime())));
        tableConfig.addMerge(rowIndex, 0, 2, 1);

        TableImageUtils.CellStyle teamStyle = new TableImageUtils.CellStyle()
                .setFont(new Font("微软雅黑", Font.BOLD, 14));
        if (oc.getReadyTime() == null || LocalDateTime.now().isAfter(oc.getReadyTime())) {
            teamStyle.setBgColor(Color.YELLOW);
        } else {
            teamStyle.setBgColor(new Color(14, 133, 49)).setTextColor(Color.WHITE);
        }
        tableConfig.setCellStyle(rowIndex, 0, teamStyle);

        for (int i = 0; i < slotList.size(); i++) {
            TornFactionOcSlotDO slot = slotList.get(i);
            resultList.add(slot.getPosition().replace(" ", "") +
                    (slot.getPassRate() == null ? "" : " " + slot.getPassRate()));

            boolean isLack = slot.getUserId() == null;
            tableConfig.setCellStyle(rowIndex, i + 1, new TableImageUtils.CellStyle()
                    .setBgColor(isLack ? MEMBER_EMPTY_COLOR : MEMBER_FULL_COLOR)
                    .setFont(new Font("微软雅黑", Font.BOLD, 14))
                    .setAlignment(TableImageUtils.TextAlignment.DISPERSED));
        }

        fillEmptyColumn(resultList, slotList.size(), columnCount, columnIndex ->
                tableConfig.setCellStyle(rowIndex, columnIndex + 1,
                        new TableImageUtils.CellStyle().setBgColor(new Color(242, 242, 242))));
        return resultList;
    }

    /**
     * 构建成员行
     *
     * @param rowIndex    当前行数
     * @param columnCount 最大列数
     */
    public List<String> buildMemberRow(List<TornFactionOcSlotDO> slotList, Map<Long, TornUserDO> userMap,
                                       int rowIndex, int columnCount, TableImageUtils.TableConfig tableConfig) {
        List<String> resultList = new ArrayList<>();
        resultList.add("");

        for (int i = 0; i < slotList.size(); i++) {
            TornFactionOcSlotDO slot = slotList.get(i);
            boolean isLack = slot.getUserId() == null;
            TornUserDO user = isLack ? null : userMap.get(slot.getUserId());
            resultList.add(user == null ?
                    "空缺" :
                    user.getNickname() + "[" + user.getId() + "] ");
            tableConfig.setCellStyle(rowIndex + 1, i + 1, new TableImageUtils.CellStyle()
                    .setAlignment(TableImageUtils.TextAlignment.LEFT)
                    .setBgColor(isLack ? MEMBER_EMPTY_COLOR : MEMBER_FULL_COLOR));
        }

        fillEmptyColumn(resultList, slotList.size(), columnCount, columnIndex ->
                tableConfig.setCellStyle(rowIndex + 1, columnIndex + 1,
                        new TableImageUtils.CellStyle().setBgColor(new Color(242, 242, 242))));
        return resultList;
    }

    /**
     * 填充空列
     *
     * @param startIndex  起始列
     * @param columnCount 最大列数
     */
    public void fillEmptyColumn(List<String> rowList, int startIndex, int columnCount, FillEmptyColumnCallback callback) {
        if (startIndex < columnCount) {
            for (int i = startIndex; i < columnCount; i++) {
                rowList.add("");

                if (callback != null) {
                    callback.handle(i);
                }
            }
        }
    }

    public interface FillEmptyColumnCallback {
        /**
         * 处理其他操作
         *
         * @param columnIndex 列序号
         */
        void handle(int columnIndex);
    }
}