package pn.torn.goldeneye.torn.manager.setting;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.base.bot.Bot;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.msg.receive.apply.GroupSysMsgJoinRec;
import pn.torn.goldeneye.msg.receive.apply.GroupSysMsgRec;
import pn.torn.goldeneye.msg.send.AuditJoinGroupReqParam;
import pn.torn.goldeneye.msg.send.GroupSysMsgReqParam;
import pn.torn.goldeneye.repository.dao.setting.VipSubscribeDAO;
import pn.torn.goldeneye.repository.model.setting.VipSubscribeDO;

import java.time.LocalDate;
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
     * 处理VIP群成员
     */
    @Scheduled(cron = "0 */5 * * * ?")
    public void handleVipGroupMember() {
        applyJoin();
    }

    /**
     * 批准加群申请
     */
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
}