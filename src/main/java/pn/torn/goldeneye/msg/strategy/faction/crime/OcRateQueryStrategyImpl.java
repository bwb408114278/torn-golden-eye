package pn.torn.goldeneye.msg.strategy.faction.crime;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.msg.receive.QqRecMsgSender;
import pn.torn.goldeneye.msg.send.param.QqMsgParam;
import pn.torn.goldeneye.msg.strategy.PnMsgStrategy;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcUserDAO;
import pn.torn.goldeneye.repository.dao.setting.TornApiKeyDAO;
import pn.torn.goldeneye.repository.dao.setting.TornSettingOcDAO;
import pn.torn.goldeneye.repository.dao.setting.TornSettingOcSlotDAO;
import pn.torn.goldeneye.repository.dao.user.TornUserDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcUserDO;
import pn.torn.goldeneye.repository.model.setting.TornApiKeyDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcSlotDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.manager.faction.oc.msg.TornFactionOcMsgTableManager;
import pn.torn.goldeneye.utils.NumberUtils;
import pn.torn.goldeneye.utils.TableImageUtils;
import pn.torn.goldeneye.utils.torn.TornUserUtils;

import java.awt.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * OC成功率查询实现类
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.08.20
 */
@Component
@RequiredArgsConstructor
public class OcRateQueryStrategyImpl extends PnMsgStrategy {
    private final TornFactionOcMsgTableManager msgTableManager;
    private final TornApiKeyDAO keyDao;
    private final TornUserDAO userDao;
    private final TornFactionOcUserDAO ocUserDao;
    private final TornSettingOcDAO settingOcDao;
    private final TornSettingOcSlotDAO settingOcSlotDao;
    private static final TableImageUtils.CellStyle TITLE_STYLE =
            new TableImageUtils.CellStyle().setBgColor(new Color(242, 242, 242))
                    .setFont(new Font("微软雅黑", Font.BOLD, 16));
    private static final TableImageUtils.CellStyle CONTENT_STYLE =
            new TableImageUtils.CellStyle().setBgColor(Color.WHITE);

    @Override
    public String getCommand() {
        return BotCommands.OC_PASS_RATE;
    }

    @Override
    public String getCommandDescription() {
        return "获取OC成功率，例g#" + BotCommands.OC_PASS_RATE + "(#用户ID)";
    }

    @Override
    public List<? extends QqMsgParam<?>> handle(long groupId, QqRecMsgSender sender, String msg) {
        long userId;
        if (StringUtils.hasText(msg)) {
            String[] msgArray = msg.split("#");
            if (msgArray.length < 1 || !NumberUtils.isLong(msgArray[0])) {
                return super.sendErrorFormatMsg();
            }

            userId = Long.parseLong(msgArray[0]);
        } else {
            userId = TornUserUtils.getUserIdFromSender(sender);
        }

        if (userId == 0L) {
            return super.buildTextMsg("金蝶不认识TA哦");
        }

        TornUserDO user = userDao.getById(userId);
        if (user == null) {
            return super.buildTextMsg("未找到该用户");
        }

        TornApiKeyDO key = keyDao.lambdaQuery()
                .eq(TornApiKeyDO::getUserId, userId)
                .eq(TornApiKeyDO::getUseDate, LocalDate.now())
                .one();
        if (key == null) {
            return super.buildTextMsg("这个人还没有绑定Key哦");
        }

        List<TornFactionOcUserDO> ocUserList = ocUserDao.lambdaQuery()
                .eq(TornFactionOcUserDO::getUserId, userId)
                .orderByDesc(TornFactionOcUserDO::getRank)
                .orderByAsc(TornFactionOcUserDO::getOcName)
                .orderByAsc(TornFactionOcUserDO::getPosition)
                .list();
        if (ocUserList.isEmpty()) {
            return super.buildTextMsg("暂未查询到记录的OC成功率");
        }

        return super.buildImageMsg(buildPassRateMsg(user, ocUserList));
    }

    /**
     * 构建成功率消息
     */
    private String buildPassRateMsg(TornUserDO user, List<TornFactionOcUserDO> ocUserList) {
        List<TornSettingOcDO> ocList = settingOcDao.lambdaQuery()
                .orderByDesc(TornSettingOcDO::getRank)
                .orderByAsc(TornSettingOcDO::getOcName).list();
        List<TornSettingOcSlotDO> allSlotList = settingOcSlotDao.list();

        List<List<String>> tableData = new ArrayList<>();
        TableImageUtils.TableConfig tableConfig = new TableImageUtils.TableConfig();
        int columnSize = calcMaxColumnSize(ocList, allSlotList, ocUserList);

        List<String> titleRow = new ArrayList<>();
        titleRow.add(user.getNickname() + "的OC成功率");
        msgTableManager.fillEmptyColumn(titleRow, 1, columnSize + 1);
        tableConfig.addMerge(0, 0, 1, columnSize + 1)
                .setCellStyle(0, 0, new TableImageUtils.CellStyle()
                        .setAlignment(TableImageUtils.TextAlignment.CENTER)
                        .setFont(new Font("微软雅黑", Font.BOLD, 30)));
        tableData.add(titleRow);

        int rowIndex = 1;
        for (TornSettingOcDO oc : ocList) {
            List<TornFactionOcUserDO> hasDataList = ocUserList.stream()
                    .filter(u -> u.getOcName().equals(oc.getOcName())).toList();
            if (hasDataList.isEmpty()) {
                continue;
            }

            List<String> ocNameLine = new ArrayList<>();
            ocNameLine.add(oc.getOcName());
            msgTableManager.fillEmptyColumn(ocNameLine, 0, columnSize + 1);
            tableData.add(ocNameLine);
            tableConfig.addMerge(rowIndex, 0, 1, columnSize + 1)
                    .setCellStyle(rowIndex, 0, TITLE_STYLE);

            List<TornSettingOcSlotDO> slotList = allSlotList.stream()
                    .filter(s -> s.getOcName().equals(oc.getOcName()))
                    .sorted(Comparator.comparing(TornSettingOcSlotDO::getSlotCode)).toList();

            tableData.add(buildPositionRow(oc.getRank(), slotList, rowIndex + 1, columnSize, tableConfig));
            tableData.add(buildPassRateRow(slotList, ocUserList, rowIndex + 2, columnSize, tableConfig));
            rowIndex += 3;
        }

        return TableImageUtils.renderTableToBase64(tableData, tableConfig);
    }

    /**
     * 构建岗位行
     *
     * @param rank        OC等级
     * @param rowIndex    当前行数
     * @param columnCount 最大列数
     */
    private List<String> buildPositionRow(int rank, List<TornSettingOcSlotDO> slotList,
                                          int rowIndex, int columnCount, TableImageUtils.TableConfig tableConfig) {

        List<String> resultList = new ArrayList<>();
        resultList.add((rank + "级"));
        tableConfig.addMerge(rowIndex, 0, 2, 1);
        tableConfig.setCellStyle(rowIndex, 0, new TableImageUtils.CellStyle().setBgColor(Color.WHITE)
                .setFont(new Font("微软雅黑", Font.BOLD, 16)));

        for (int i = 0; i < slotList.size(); i++) {
            TornSettingOcSlotDO slot = slotList.get(i);
            resultList.add(slot.getSlotCode());
            tableConfig.setCellStyle(rowIndex, i + 1, TITLE_STYLE);
        }

        msgTableManager.fillEmptyColumn(resultList, slotList.size(), columnCount,
                columnIndex -> tableConfig.setCellStyle(rowIndex, columnIndex + 1, TITLE_STYLE));
        return resultList;
    }

    /**
     * 构建成功率行
     *
     * @param columnCount 最大列数
     */
    private List<String> buildPassRateRow(List<TornSettingOcSlotDO> slotList, List<TornFactionOcUserDO> ocUserList,
                                          int rowIndex, int columnCount, TableImageUtils.TableConfig tableConfig) {
        List<String> resultList = new ArrayList<>();
        resultList.add("");

        for (int i = 0; i < slotList.size(); i++) {
            TornSettingOcSlotDO slot = slotList.get(i);
            TornFactionOcUserDO ocUser = ocUserList.stream()
                    .filter(u -> u.getPosition().equals(slot.getSlotShortCode()) &&
                            u.getOcName().equals(slot.getOcName()))
                    .findAny().orElse(null);
            resultList.add(ocUser == null ? "暂无" : ocUser.getPassRate().toString());
            tableConfig.setCellStyle(rowIndex, i + 1, CONTENT_STYLE);
        }

        msgTableManager.fillEmptyColumn(resultList, slotList.size(), columnCount,
                columnIndex -> tableConfig.setCellStyle(rowIndex, columnIndex + 1, TITLE_STYLE));
        return resultList;
    }

    /**
     * 计算最大列数
     */
    private int calcMaxColumnSize(List<TornSettingOcDO> ocList, List<TornSettingOcSlotDO> allSlotList,
                                  List<TornFactionOcUserDO> ocUserList) {
        int columnSize = 0;
        for (TornSettingOcDO oc : ocList) {
            List<TornFactionOcUserDO> hasDataList = ocUserList.stream()
                    .filter(u -> u.getOcName().equals(oc.getOcName())).toList();
            if (hasDataList.isEmpty()) {
                continue;
            }

            List<TornSettingOcSlotDO> slotList = allSlotList.stream()
                    .filter(s -> s.getOcName().equals(oc.getOcName())).toList();
            columnSize = Math.max(slotList.size(), columnSize);
        }

        return columnSize;
    }
}