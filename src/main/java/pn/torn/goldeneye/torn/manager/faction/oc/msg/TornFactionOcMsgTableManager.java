package pn.torn.goldeneye.torn.manager.faction.oc.msg;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.base.model.TableDataBO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcNoticeDAO;
import pn.torn.goldeneye.repository.dao.user.TornUserDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcNoticeDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.utils.DateTimeUtils;
import pn.torn.goldeneye.utils.TableImageUtils;
import pn.torn.goldeneye.utils.torn.TornOcUtils;

import java.awt.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.*;

/**
 * OC表格消息公共逻辑
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.08.15
 */
@Component
@RequiredArgsConstructor
public class TornFactionOcMsgTableManager {
    private final TornUserDAO userDao;
    private final TornFactionOcNoticeDAO noticeDao;
    private static final Color MEMBER_FULL_COLOR = new Color(122, 167, 56);
    private static final Color MEMBER_EMPTY_COLOR = new Color(230, 119, 0);
    private static final String PRE_TEAM = "9级前置";
    private static final String ROTATION_TEAM = "轮转队";

    /**
     * 绘制OC表格
     *
     * @return 表格数据，第一层为行，第二层为单元格
     */
    public TableDataBO buildOcTable(String title, Map<TornFactionOcDO, List<TornFactionOcSlotDO>> ocMap) {
        List<Long> userIdList = new ArrayList<>();
        ocMap.values().forEach(v -> userIdList.addAll(v.stream()
                .map(TornFactionOcSlotDO::getUserId)
                .filter(Objects::nonNull)
                .toList()));
        Map<Long, TornUserDO> userMap = userDao.queryUserMap(userIdList);
        List<TornFactionOcNoticeDO> skipList = noticeDao.querySkipList();

        TableImageUtils.TableConfig tableConfig = new TableImageUtils.TableConfig();
        List<List<String>> tableData = new ArrayList<>();
        int columnCount = ocMap.values().stream().max(Comparator.comparingInt(List::size)).orElse(List.of()).size();

        List<String> titleRow = new ArrayList<>();
        titleRow.add(title);
        fillEmptyColumn(titleRow, 1, columnCount + 1);
        tableConfig.addMerge(0, 0, 1, columnCount + 1)
                .setCellStyle(0, 0, new TableImageUtils.CellStyle()
                        .setAlignment(TableImageUtils.TextAlignment.CENTER)
                        .setFont(new Font("微软雅黑", Font.BOLD, 30)));
        tableData.add(titleRow);

        int rowIndex = 1;
        for (Map.Entry<TornFactionOcDO, List<TornFactionOcSlotDO>> entry : ocMap.entrySet()) {
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

            tableData.add(buildPositionRow(oc, slotList, skipList, rowIndex, columnCount, tableConfig));
            tableData.add(buildMemberRow(slotList, userMap, rowIndex, columnCount, tableConfig));

            List<String> splitLine = new ArrayList<>();
            fillEmptyColumn(splitLine, 0, columnCount + 1);
            tableData.add(splitLine);

            tableConfig.addMerge(rowIndex + 2, 0, 1, columnCount + 1);
            tableConfig.setCellStyle(rowIndex + 2, 0,
                    new TableImageUtils.CellStyle().setBgColor(new Color(242, 242, 242)));

            rowIndex += 3;
        }

        // 移除最后一行分隔行
        tableData.remove(rowIndex - 1);
        return new TableDataBO(tableData, tableConfig);
    }

    /**
     * 构建岗位行
     *
     * @param rowIndex    当前行数
     * @param columnCount 最大列数
     */
    private List<String> buildPositionRow(TornFactionOcDO oc, List<TornFactionOcSlotDO> slotList,
                                          List<TornFactionOcNoticeDO> noticeList,
                                          int rowIndex, int columnCount, TableImageUtils.TableConfig tableConfig) {
        List<String> resultList = new ArrayList<>();
        String teamFlag = getTeamFlag(oc, slotList, noticeList);
        resultList.add((teamFlag.isEmpty() ? teamFlag : teamFlag + "   ") + oc.getStatus() +
                "\n" + DateTimeUtils.convertToString(oc.getReadyTime()));
        tableConfig.addMerge(rowIndex, 0, 2, 1);

        TableImageUtils.CellStyle teamStyle = new TableImageUtils.CellStyle()
                .setFont(new Font("微软雅黑", Font.BOLD, 14));
        if (LocalDateTime.now().isAfter(oc.getReadyTime())) {
            teamStyle.setBgColor(Color.YELLOW);
        } else if (PRE_TEAM.equals(teamFlag)) {
            teamStyle.setBgColor(new Color(201, 119, 221));
        } else if (ROTATION_TEAM.equals(teamFlag)) {
            teamStyle.setBgColor(new Color(119, 199, 221));
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
    private List<String> buildMemberRow(List<TornFactionOcSlotDO> slotList, Map<Long, TornUserDO> userMap,
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
    private void fillEmptyColumn(List<String> rowList, int startIndex, int columnCount) {
        fillEmptyColumn(rowList, startIndex, columnCount, null);
    }

    /**
     * 填充空列
     *
     * @param startIndex  起始列
     * @param columnCount 最大列数
     */
    private void fillEmptyColumn(List<String> rowList, int startIndex, int columnCount, FillEmptyColumnCallback callback) {
        if (startIndex < columnCount) {
            for (int i = startIndex; i < columnCount; i++) {
                rowList.add("");

                if (callback != null) {
                    callback.handle(i);
                }
            }
        }
    }

    /**
     * 获取队伍标识
     *
     * @return 队伍标识
     */
    private String getTeamFlag(TornFactionOcDO oc, List<TornFactionOcSlotDO> slotList,
                               List<TornFactionOcNoticeDO> noticeList) {
        boolean notRotationRank = !oc.getRank().equals(8) && !oc.getRank().equals(7);
        if (notRotationRank) {
            return "";
        }

        if (TornOcUtils.isChainOc(oc)) {
            return PRE_TEAM;
        }

        for (TornFactionOcSlotDO slot : slotList) {
            if (slot.getUserId() == null) {
                continue;
            }

            if (noticeList.stream().anyMatch(notice ->
                    notice.getUserId().equals(slot.getUserId()) && notice.getRank().equals(oc.getRank()))) {
                return "咸鱼队";
            }
        }

        return ROTATION_TEAM;
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