package pn.torn.goldeneye.torn.manager.faction.oc;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.base.bot.Bot;
import pn.torn.goldeneye.base.model.TableDataBO;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.constants.torn.TornConstants;
import pn.torn.goldeneye.msg.receive.member.GroupMemberDataRec;
import pn.torn.goldeneye.msg.receive.member.GroupMemberRec;
import pn.torn.goldeneye.msg.send.GroupMemberReqParam;
import pn.torn.goldeneye.msg.send.param.AtQqMsg;
import pn.torn.goldeneye.msg.send.param.QqMsgParam;
import pn.torn.goldeneye.msg.send.param.TextQqMsg;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcSlotDAO;
import pn.torn.goldeneye.repository.dao.setting.SysSettingDAO;
import pn.torn.goldeneye.repository.dao.user.TornUserDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.manager.faction.oc.msg.TornFactionOcMsgTableManager;
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
    private final TornFactionOcMsgTableManager msgTableManager;
    private final TornFactionOcSlotDAO slotDao;
    private final TornUserDAO userDao;
    private final SysSettingDAO settingDao;
    private final ProjectProperty projectProperty;

    /**
     * 构建岗位详细消息
     */
    public List<QqMsgParam<?>> buildSlotMsg(long ocId, long factionId, int... rank) {
        List<TornFactionOcSlotDO> slotList = slotDao.lambdaQuery().eq(TornFactionOcSlotDO::getOcId, ocId).list();
        return buildSlotMsg(slotList, () -> ocUserManager.findRotationUser(factionId, rank));
    }

    /**
     * 构建岗位详细消息
     */
    public List<QqMsgParam<?>> buildSlotMsg(List<TornFactionOcSlotDO> slotList) {
        return buildSlotMsg(slotList, null);
    }

    /**
     * 构建岗位详细消息
     */
    public List<QqMsgParam<?>> buildSlotMsg(List<TornFactionOcSlotDO> slotList, SlotMsgCallback callback) {
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
     * 构建At消息
     *
     * @param userIdList 用户ID列表
     */
    public List<QqMsgParam<?>> buildAtMsg(Collection<Long> userIdList) {
        ResponseEntity<GroupMemberRec> memberList = bot.sendRequest(
                new GroupMemberReqParam(projectProperty.getGroupId()), GroupMemberRec.class);
        List<QqMsgParam<?>> resultList = new ArrayList<>();

        for (Long userId : userIdList) {
            String card = "[" + userId + "]";
            GroupMemberDataRec member = memberList.getBody().getData().stream().filter(m ->
                    m.getCard().contains(card)).findAny().orElse(null);
            if (member == null) {
                TornUserDO user = userDao.getById(userId);
                resultList.add(new TextQqMsg((user == null ?
                        userId :
                        user.getNickname()) + card + " "));
            } else {
                resultList.add(new AtQqMsg(member.getUserId()));
            }
        }
        return resultList;
    }

    /**
     * 构建OC表格
     *
     * @param title 标题
     * @return 表格图片的Base64
     */
    public String buildOcTable(String title, List<TornFactionOcDO> ocList) {
        List<TornFactionOcSlotDO> slotList = slotDao.queryListByOc(ocList);
        Map<TornFactionOcDO, List<TornFactionOcSlotDO>> ocMap = LinkedHashMap.newLinkedHashMap(ocList.size());
        for (TornFactionOcDO oc : ocList) {
            List<TornFactionOcSlotDO> currentSlotList = new ArrayList<>(slotList.stream()
                    .filter(s -> s.getOcId().equals(oc.getId())).toList());
            ocMap.put(oc, currentSlotList);
        }

        TableDataBO table = msgTableManager.buildOcTable(title, ocMap);

        String lastRefreshTime = settingDao.querySettingValue(TornConstants.SETTING_KEY_OC_LOAD);
        table.getTableData().add(List.of("上次更新时间: " + lastRefreshTime,
                "", "", "", "", ""));

        int row = ocList.size() * 3;
        table.getTableConfig().addMerge(row, 0, 1, 7)
                .setCellStyle(row, 0, new TableImageUtils.CellStyle()
                        .setFont(new Font("微软雅黑", Font.BOLD, 14))
                        .setAlignment(TableImageUtils.TextAlignment.LEFT));
        return TableImageUtils.renderTableToBase64(table);
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