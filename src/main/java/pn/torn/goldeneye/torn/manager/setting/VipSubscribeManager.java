package pn.torn.goldeneye.torn.manager.setting;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.base.bot.Bot;
import pn.torn.goldeneye.base.bot.BotHttpReqParam;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.constants.torn.TornConstants;
import pn.torn.goldeneye.msg.receive.apply.GroupSysMsgJoinRec;
import pn.torn.goldeneye.msg.receive.apply.GroupSysMsgRec;
import pn.torn.goldeneye.msg.send.AuditJoinGroupReqParam;
import pn.torn.goldeneye.msg.send.GroupMsgHttpBuilder;
import pn.torn.goldeneye.msg.send.GroupSysMsgReqParam;
import pn.torn.goldeneye.msg.send.param.AtQqMsg;
import pn.torn.goldeneye.msg.send.param.KickGroupMemberReqParam;
import pn.torn.goldeneye.msg.send.param.TextQqMsg;
import pn.torn.goldeneye.repository.dao.setting.VipSubscribeDAO;
import pn.torn.goldeneye.repository.model.setting.VipSubscribeDO;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * VIP订阅公共逻辑层
 *
 * @author Bai
 * @version 0.5.0
 * @since 2026.01.29
 */
@Component
@RequiredArgsConstructor
public class VipSubscribeManager {
    private final Bot bot;
    private final VipSubscribeDAO subscribeDao;
    private final ProjectProperty projectProperty;

    /**
     * 批准加群申请
     */
    @Scheduled(cron = "0 */5 * * * ?")
    private void applyJoin() {
        ResponseEntity<GroupSysMsgRec> resp = bot.sendRequest(new GroupSysMsgReqParam(), GroupSysMsgRec.class);
        List<GroupSysMsgJoinRec> applyList = resp.getBody().getData().getJoinRequests().stream()
                .filter(m -> projectProperty.getVipGroupId() == m.getGroupId())
                .filter(m -> !m.isChecked())
                .toList();
        if (CollectionUtils.isEmpty(applyList)) {
            return;
        }

        List<Long> qqIdList = applyList.stream().map(GroupSysMsgJoinRec::getInvitorUin).toList();
        List<VipSubscribeDO> subscribeList = subscribeDao.lambdaQuery().in(VipSubscribeDO::getQqId, qqIdList).list();
        for (GroupSysMsgJoinRec apply : applyList) {
            VipSubscribeDO subscribe = subscribeList.stream()
                    .filter(s -> s.getQqId().equals(apply.getInvitorUin())).findAny().orElse(null);
            if (subscribe == null) {
                continue;
            }

            bot.sendRequest(new AuditJoinGroupReqParam(apply.getRequestId()), String.class);
            subscribe.setStartDate(LocalDate.now());
            subscribe.setEndDate(LocalDate.now().plusDays(subscribe.getSubscribeLength()));
            subscribeDao.updateById(subscribe);
        }
    }

    /**
     * 警告和踢出VIP成员
     */
    @Scheduled(cron = "0 0 8 */1 * ?")
    public void warnAndKickVipGroupMember() {
        List<VipSubscribeDO> limitList = subscribeDao.lambdaQuery()
                .lt(VipSubscribeDO::getEndDate, LocalDate.now().plusDays(5L))
                .list();
        if (CollectionUtils.isEmpty(limitList)) {
            return;
        }

        List<Long> warningQqList = new ArrayList<>();
        for (VipSubscribeDO subscribe : limitList) {
            if (subscribe.getEndDate().isBefore(LocalDate.now())) {
                bot.sendRequest(new KickGroupMemberReqParam(
                        projectProperty.getVipGroupId(), subscribe.getQqId()), String.class);
                subscribeDao.removeById(subscribe.getId());
            } else {
                warningQqList.add(subscribe.getQqId());
            }
        }

        if (!warningQqList.isEmpty()) {
            List<AtQqMsg> atList = warningQqList.stream().map(AtQqMsg::new).toList();
            TextQqMsg warningMsg = new TextQqMsg("\n大佬们的订阅即将在5天内到期, 如果还满意请发送2Xan到3312605并备注"
                    + TornConstants.REMARK_SUBSCRIBE + "进行续费\n如不需要续费到期后机器人会自动将大佬移出群聊, 欢迎留下您宝贵的改进意见");
            BotHttpReqParam param = new GroupMsgHttpBuilder()
                    .setGroupId(projectProperty.getVipGroupId())
                    .addMsg(atList).addMsg(warningMsg).build();
            bot.sendRequest(param, String.class);
        }
    }
}