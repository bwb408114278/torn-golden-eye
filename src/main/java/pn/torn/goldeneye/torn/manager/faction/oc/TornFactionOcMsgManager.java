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

import java.awt.*;
import java.util.List;
import java.util.*;

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
    private final TableImageUtils.CellStyle positionStyle = new TableImageUtils.CellStyle()
            .setAlignment(TableImageUtils.TextAlignment.DISPERSED)
            .setBgColor(Color.BLACK).setTextColor(Color.WHITE);
    private final TableImageUtils.CellStyle memberFullStyle = new TableImageUtils.CellStyle()
            .setBgColor(new Color(122, 167, 56));
    private final TableImageUtils.CellStyle memberEmptyStyle = new TableImageUtils.CellStyle()
            .setBgColor(new Color(230, 119, 0));

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
        int maxSize = ocMap.values().stream().max(Comparator.comparingInt(List::size)).orElse(List.of()).size();

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

            tableData.add(buildPositionRow(oc, slotList, skipList, rowIndex, maxSize, tableConfig));
            tableData.add(buildMemberRow(oc, slotList, userList, rowIndex, maxSize, tableConfig));

            rowIndex += 2;
        }

        return new TableDataBO(tableData, tableConfig);
    }

    /**
     * 构建At消息
     *
     * @param userIdList 用户ID列表
     */
    public List<GroupMsgParam<?>> buildAtMsg(Collection<Long> userIdList) {
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
     * 构建岗位行
     *
     * @param rowIndex 当前行数
     * @param maxSize  最大列数
     */
    private List<String> buildPositionRow(TornFactionOcDO oc, List<TornFactionOcSlotDO> slotList,
                                          List<TornFactionOcSkipDO> skipList,
                                          int rowIndex, int maxSize, TableImageUtils.TableConfig tableConfig) {
        List<String> resultList = new ArrayList<>();
        String teamFlag = getTeamFlag(oc, slotList, skipList);
        resultList.add((teamFlag.isEmpty() ? teamFlag : teamFlag + "   ") + oc.getStatus() +
                "\n" + DateTimeUtils.convertToString(oc.getReadyTime()));
        tableConfig.addMerge(rowIndex, 0, 2, 1);

        for (int i = 0; i < slotList.size(); i++) {
            TornFactionOcSlotDO slot = slotList.get(i);
            resultList.add(slot.getPosition().replace(" ", "") +
                    (slot.getPassRate() == null ? "" : " " + slot.getPassRate()));
            tableConfig.setCellStyle(rowIndex, i + 1, positionStyle);
        }

        if (slotList.size() < maxSize) {
            for (int i = slotList.size(); i < maxSize; i++) {
                resultList.add("");
            }
        }

        return resultList;
    }

    /**
     * 构建成员行
     *
     * @param rowIndex 当前行数
     * @param maxSize  最大列数
     */
    private List<String> buildMemberRow(TornFactionOcDO oc, List<TornFactionOcSlotDO> slotList, List<TornUserDO> userList,
                                        int rowIndex, int maxSize, TableImageUtils.TableConfig tableConfig) {
        List<String> resultList = new ArrayList<>();
        resultList.add("");

        for (int i = 0; i < slotList.size(); i++) {
            TornFactionOcSlotDO slot = slotList.get(i);
            boolean isLack = slot.getUserId() == null;
            TornUserDO user = isLack ?
                    null :
                    userList.stream().filter(u -> u.getId().equals(slot.getUserId())).findAny().orElse(null);
            resultList.add(user == null ?
                    "空缺" :
                    user.getNickname() + "[" + user.getId() + "] ");
            tableConfig.setCellStyle(rowIndex + 1, i + 1, isLack ? memberEmptyStyle : memberFullStyle);
        }

        if (slotList.size() < maxSize) {
            for (int i = slotList.size(); i < maxSize; i++) {
                resultList.add("");
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
        Collection<Long> getOtherUser();
    }
}