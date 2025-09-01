package pn.torn.goldeneye.torn.manager.faction.oc;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.base.bot.Bot;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.msg.send.GroupMsgHttpBuilder;
import pn.torn.goldeneye.msg.send.param.QqMsgParam;
import pn.torn.goldeneye.msg.send.param.TextQqMsg;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcSlotDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.torn.manager.faction.oc.msg.TornFactionOcMsgManager;
import pn.torn.goldeneye.utils.DateTimeUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * OC校验公共逻辑层
 *
 * @author Bai
 * @version 0.2.0
 * @since 2025.08.29
 */
@Component
@RequiredArgsConstructor
public class TornFactionOcValidManager {
    private final Bot bot;
    private final TornFactionOcMsgManager msgManager;
    private final TornFactionOcSlotDAO slotDao;
    private final ProjectProperty projectProperty;

    /**
     * 校验多个人同时进入了轮转队
     */
    public boolean validMoreMember(List<TornFactionOcDO> recList) {
        // 如果迟于明天完成的队伍，判定为大于一人加入
        List<TornFactionOcDO> moreMemberTeam = recList.stream()
                .filter(o -> o.getReadyTime().toLocalDate().isAfter(LocalDate.now().plusDays(1)))
                .toList();
        if (CollectionUtils.isEmpty(moreMemberTeam)) {
            return true;
        }

        Map<Long, List<TornFactionOcSlotDO>> slotMap = slotDao.queryMapByOc(moreMemberTeam);
        GroupMsgHttpBuilder msgBuilder;
        for (TornFactionOcDO oc : moreMemberTeam) {
            List<TornFactionOcSlotDO> slotList = slotMap.get(oc.getId());
            List<TornFactionOcSlotDO> noticeList = slotList.stream()
                    .filter(s -> s.getUserId() != null && s.getJoinTime().toLocalDate().equals(LocalDate.now()))
                    .toList();

            List<QqMsgParam<?>> atMsgList = msgManager.buildSlotMsg(noticeList);
            msgBuilder = new GroupMsgHttpBuilder()
                    .setGroupId(projectProperty.getGroupId())
                    .addMsg(new TextQqMsg("轮转队进入重复啦\n"));
            for (int i = 0; i < noticeList.size(); i++) {
                msgBuilder.addMsg(atMsgList.get(i))
                        .addMsg(new TextQqMsg("在" + DateTimeUtils.convertToString(noticeList.get(i).getJoinTime()) + "加入\n"));
            }
            bot.sendRequest(msgBuilder.build(), String.class);
        }

        return false;
    }
}