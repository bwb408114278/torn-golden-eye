package pn.torn.goldeneye.torn.manager.faction.oc;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.base.bot.Bot;
import pn.torn.goldeneye.base.model.TableDataBO;
import pn.torn.goldeneye.constants.bot.BotConstants;
import pn.torn.goldeneye.constants.torn.TornConstants;
import pn.torn.goldeneye.msg.receive.member.GroupMemberDataRec;
import pn.torn.goldeneye.msg.receive.member.GroupMemberRec;
import pn.torn.goldeneye.msg.send.GroupMemberReqParam;
import pn.torn.goldeneye.msg.send.param.AtGroupMsg;
import pn.torn.goldeneye.msg.send.param.GroupMsgParam;
import pn.torn.goldeneye.msg.send.param.TextGroupMsg;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcSkipDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcSlotDAO;
import pn.torn.goldeneye.repository.dao.user.TornUserDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSkipDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.utils.DateTimeUtils;
import pn.torn.goldeneye.utils.TableImageUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * OC消息公共逻辑
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.08.06
 */
@Component
@RequiredArgsConstructor
public class TornFactionOcMsgManager {
    private final Bot bot;
    private final TornFactionOcUserManager ocUserManager;
    private final TornFactionOcSlotDAO slotDao;
    private final TornUserDAO userDao;
    private final TornFactionOcSkipDAO skipDao;

    /**
     * 构建岗位详细消息
     */
    public List<GroupMsgParam<?>> buildSlotMsg(TornFactionOcDO oc) {
        List<TornFactionOcSlotDO> slotList = slotDao.lambdaQuery().eq(TornFactionOcSlotDO::getOcId, oc.getId()).list();
        return buildSlotMsg(slotList, () -> ocUserManager.findRotationUser(oc.getRank()));
    }

    /**
     * 构建岗位详细消息
     */
    public List<GroupMsgParam<?>> buildSlotMsg(List<TornFactionOcSlotDO> slotList, SlotMsgCallback callback) {
        List<Long> userIdList = new ArrayList<>();
        slotList.forEach(s -> {
            if (s.getUserId() != null) {
                userIdList.add(s.getUserId());
            }
        });

        if (callback != null) {
            userIdList.addAll(callback.getOtherUser());
        }

        return buildAtMsg(userIdList);
    }

    /**
     * 绘制OC表格
     *
     * @return 表格数据，第一层为行，第二层为单元格
     */
    public TableDataBO buildOcTable(Map<TornFactionOcDO, List<TornFactionOcSlotDO>> ocMap) {
        List<Long> userIdList = new ArrayList<>();
        ocMap.values().forEach(v -> userIdList.addAll(v.stream()
                .map(TornFactionOcSlotDO::getUserId)
                .filter(Objects::nonNull)
                .toList()));
        List<TornUserDO> userList = userDao.lambdaQuery().in(TornUserDO::getId, userIdList).list();
        List<TornFactionOcSkipDO> skipList = skipDao.list();

        TableImageUtils.TableConfig tableConfig = new TableImageUtils.TableConfig();
        List<List<String>> tableData = new ArrayList<>();
        int rowIndex = 0;
        for (Map.Entry<TornFactionOcDO, List<TornFactionOcSlotDO>> entry : ocMap.entrySet()) {
            TornFactionOcDO oc = entry.getKey();
            List<TornFactionOcSlotDO> slotList = entry.getValue();
            tableData.add(List.of(
                    "ID: " + oc.getId() +
                            "     " + oc.getStatus() +
                            "     " + getTeamFlag(oc, slotList, skipList) +
                            "     完成时间: " + DateTimeUtils.convertToString(oc.getReadyTime()),
                    "", "", "", "", ""));

            List<String> positionRow = new ArrayList<>();
            List<String> memberRow = new ArrayList<>();
            for (TornFactionOcSlotDO slot : slotList) {
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

        return new TableDataBO(tableData, tableConfig);
    }

    /**
     * 构建At消息
     *
     * @param userIdList 用户ID列表
     */
    public List<GroupMsgParam<?>> buildAtMsg(List<Long> userIdList) {
        ResponseEntity<GroupMemberRec> memberList = bot.sendRequest(
                new GroupMemberReqParam(BotConstants.PN_GROUP_ID), GroupMemberRec.class);
        List<GroupMsgParam<?>> resultList = new ArrayList<>();

        for (Long userId : userIdList) {
            String card = "[" + userId + "]";
            GroupMemberDataRec member = memberList.getBody().getData().stream().filter(m ->
                    m.getCard().contains(card)).findAny().orElse(null);
            if (member == null) {
                TornUserDO user = userDao.getById(userId);
                resultList.add(new TextGroupMsg((user == null ?
                        userId :
                        user.getNickname()) + card + " "));
            } else {
                resultList.add(new AtGroupMsg(member.getUserId()));
            }
        }
        return resultList;
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

    public interface SlotMsgCallback {
        /**
         * 获取Slot人员之外的用户
         *
         * @return 获取其他用户
         */
        List<Long> getOtherUser();
    }
}