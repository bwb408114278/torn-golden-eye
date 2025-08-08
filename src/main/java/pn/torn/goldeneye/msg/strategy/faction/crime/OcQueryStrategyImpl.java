package pn.torn.goldeneye.msg.strategy.faction.crime;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.constants.torn.TornConstants;
import pn.torn.goldeneye.constants.torn.enums.TornOcStatusEnum;
import pn.torn.goldeneye.msg.send.param.GroupMsgParam;
import pn.torn.goldeneye.msg.strategy.PnMsgStrategy;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcSkipDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcSlotDAO;
import pn.torn.goldeneye.repository.dao.setting.SysSettingDAO;
import pn.torn.goldeneye.repository.dao.user.TornUserDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSkipDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.utils.DateTimeUtils;
import pn.torn.goldeneye.utils.NumberUtils;
import pn.torn.goldeneye.utils.TableImageUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 获取Oc策略实现类
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.07.24
 */
@Component
@RequiredArgsConstructor
public class OcQueryStrategyImpl extends PnMsgStrategy {
    private final TornFactionOcDAO ocDao;
    private final TornFactionOcSlotDAO slotDao;
    private final TornUserDAO userDao;
    private final TornFactionOcSkipDAO skipDao;
    private final SysSettingDAO settingDao;

    @Override
    public String getCommand() {
        return BotCommands.OC_QUERY;
    }

    @Override
    public List<? extends GroupMsgParam<?>> handle(String msg) {
        String[] msgArray = msg.split("#");
        if (msgArray.length < 1 || !NumberUtils.isInt(msgArray[0])) {
            return super.sendErrorFormatMsg();
        }

        List<TornFactionOcDO> ocList = ocDao.lambdaQuery()
                .eq(TornFactionOcDO::getRank, Integer.parseInt(msgArray[0]))
                .in(TornFactionOcDO::getStatus, TornOcStatusEnum.RECRUITING.getCode(), TornOcStatusEnum.PLANNING.getCode())
                .orderByAsc(TornFactionOcDO::getName)
                .orderByAsc(TornFactionOcDO::getStatus)
                .orderByDesc(TornFactionOcDO::getReadyTime)
                .list();
        if (CollectionUtils.isEmpty(ocList)) {
            return super.buildTextMsg("未查询到对应OC");
        }

        List<Long> ocIdList = ocList.stream().map(TornFactionOcDO::getId).toList();
        List<TornFactionOcSlotDO> slotList = slotDao.lambdaQuery().in(TornFactionOcSlotDO::getOcId, ocIdList).list();
        return super.buildImageMsg(buildOcListMsg(ocList, slotList));
    }

    /**
     * 构建OC列表消息
     *
     * @return 消息内容
     */
    private String buildOcListMsg(List<TornFactionOcDO> ocList, List<TornFactionOcSlotDO> slotList) {
        List<Long> userIdList = slotList.stream().map(TornFactionOcSlotDO::getUserId).filter(Objects::nonNull).toList();
        List<TornUserDO> userList = userDao.lambdaQuery().in(TornUserDO::getId, userIdList).list();
        List<TornFactionOcSkipDO> skipList = skipDao.list();

        TableImageUtils.TableConfig tableConfig = new TableImageUtils.TableConfig();
        List<List<String>> tableData = new ArrayList<>();
        int rowIndex = 0;
        for (TornFactionOcDO oc : ocList) {
            List<TornFactionOcSlotDO> currenSlotList = slotList.stream()
                    .filter(s -> s.getOcId().equals(oc.getId()))
                    .sorted(Comparator.comparing(TornFactionOcSlotDO::getPosition))
                    .toList();

            tableData.add(List.of(
                    "ID: " + oc.getId() +
                            "     " + oc.getStatus() +
                            "     " + getTeamFlag(oc, currenSlotList, skipList) +
                            "     完成时间: " + DateTimeUtils.convertToString(oc.getReadyTime()),
                    "", "", "", "", ""));

            List<String> positionRow = new ArrayList<>();
            List<String> memberRow = new ArrayList<>();
            for (TornFactionOcSlotDO slot : currenSlotList) {
                positionRow.add(slot.getPosition() + (slot.getPassRate() == null ? "" : " " + slot.getPassRate()));
                TornUserDO user = slot.getUserId() == null ?
                        null :
                        userList.stream().filter(u -> u.getId().equals(slot.getUserId())).findAny().orElse(null);
                memberRow.add(user == null ?
                        "空缺" :
                        user.getNickname() + "[" + user.getId() + "] ");
            }

            tableData.add(positionRow);
            tableData.add(memberRow);

            tableConfig = tableConfig
                    .addMergedRow(rowIndex)
                    .setRowAlignment(rowIndex + 1, TableImageUtils.TextAlignment.DISPERSED);
            tableData.add(List.of("", "", "", "", "", ""));
            rowIndex += 4;
        }

        String lastRefreshTime = settingDao.querySettingValue(TornConstants.SETTING_KEY_OC_LOAD);
        tableData.add(List.of("上次更新时间: " + lastRefreshTime,
                "", "", "", "", ""));
        return TableImageUtils.renderTableToBase64(tableData, tableConfig);
    }

    /**
     * 获取队伍标识
     *
     * @return 队伍标识
     */
    private String getTeamFlag(TornFactionOcDO oc, List<TornFactionOcSlotDO> slotList,
                               List<TornFactionOcSkipDO> skipList) {
        boolean notRotationRank = !oc.getRank().equals(8) && !oc.getRank().equals(7);
        if (notRotationRank) {
            return "";
        }

        boolean isChainOc = oc.getRank().equals(8) && oc.getName().equals(TornConstants.OC_RANK_8_CHAIN);
        if (isChainOc) {
            return "9级前置";
        }

        for (TornFactionOcSlotDO slot : slotList) {
            if (slot.getUserId() == null) {
                continue;
            }

            if (skipList.stream().anyMatch(s ->
                    s.getUserId().equals(slot.getUserId()) && s.getRank().equals(oc.getRank()))) {
                return "咸鱼队";
            }
        }

        return "轮转队";
    }
}