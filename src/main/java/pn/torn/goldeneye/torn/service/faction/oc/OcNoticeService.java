package pn.torn.goldeneye.torn.service.faction.oc;

import lombok.AllArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationContext;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.base.bot.Bot;
import pn.torn.goldeneye.base.bot.BotSocketReqParam;
import pn.torn.goldeneye.configuration.BotSocketClient;
import pn.torn.goldeneye.configuration.DynamicTaskService;
import pn.torn.goldeneye.configuration.property.TestProperty;
import pn.torn.goldeneye.constants.torn.enums.TornOcStatusEnum;
import pn.torn.goldeneye.msg.receive.member.GroupMemberDataRec;
import pn.torn.goldeneye.msg.receive.member.GroupMemberRec;
import pn.torn.goldeneye.msg.send.GroupMemberReqParam;
import pn.torn.goldeneye.msg.send.GroupMsgSocketBuilder;
import pn.torn.goldeneye.msg.send.param.AtGroupMsg;
import pn.torn.goldeneye.msg.send.param.GroupMsgParam;
import pn.torn.goldeneye.msg.send.param.TextGroupMsg;
import pn.torn.goldeneye.msg.strategy.faction.crime.OcCheckStrategyImpl;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcSlotDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.utils.DateTimeUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 获取Oc策略实现类
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.07.24
 */
@Component
@RequiredArgsConstructor
public class OcNoticeService {
    private final ApplicationContext applicationContext;
    private final DynamicTaskService taskService;
    private final Bot bot;
    private final TornFactionOcDAO ocDao;
    private final TornFactionOcSlotDAO slotDao;
    private final TestProperty testProperty;

    /**
     * 构建提醒
     *
     * @param rank oc级别
     */
    public Runnable buildNotice(int rank) {
        return new Notice(rank);
    }

    @AllArgsConstructor
    private class Notice implements Runnable {
        /**
         * OC级别
         */
        private final int rank;

        @Override
        public void run() {
            TornFactionOcDO oc = ocDao.lambdaQuery()
                    .eq(TornFactionOcDO::getStatus, TornOcStatusEnum.PLANNING.getCode())
                    .eq(TornFactionOcDO::getRank, rank)
                    .one();
            if (oc == null) {
                return;
            }

            List<TornFactionOcSlotDO> slotList = slotDao.lambdaQuery().eq(TornFactionOcSlotDO::getOcId, oc.getId()).list();
            BotSocketReqParam param = new GroupMsgSocketBuilder()
                    .setGroupId(testProperty.getGroupId())
                    .addMsg(new TextGroupMsg("5分钟后准备抢车位" +
                            "\n执行时间: " + DateTimeUtils.convertToString(oc.getReadyTime()) + "\n"))
                    .addMsg(buildSlotMsg(slotList))
                    .build();
            BotSocketClient socketClient = applicationContext.getBean(BotSocketClient.class);
            socketClient.sendMessage(param);
            // 2分钟后更新当前OC状态，拉取新的OC
            taskService.updateTask("oc-complete-" + rank,
                    new ReloadOc(oc.getId()),
                    DateTimeUtils.convertToInstant(oc.getReadyTime().plusMinutes(2)), null);
        }

        /**
         * 构建岗位详细消息
         *
         * @return 岗位消息消息
         */
        private List<GroupMsgParam<?>> buildSlotMsg(List<TornFactionOcSlotDO> slots) {
            ResponseEntity<GroupMemberRec> memberList = bot.sendRequest(
                    new GroupMemberReqParam(testProperty.getGroupId()), GroupMemberRec.class);

            List<GroupMsgParam<?>> resultList = new ArrayList<>();
            for (TornFactionOcSlotDO slot : slots) {
                String card = "[" + slot.getUserId() + "]";
                GroupMemberDataRec member = memberList.getBody().getData().stream().filter(m ->
                        m.getCard().contains(card)).findAny().orElse(null);
                if (member == null) {
                    resultList.add(new TextGroupMsg(slot.getUserId() + " "));
                } else {
                    resultList.add(new AtGroupMsg(member.getUserId()));
                }
            }
            return resultList;
        }
    }

    @AllArgsConstructor
    public class ReloadOc implements Runnable {
        /**
         * OC ID
         */
        private final long id;

        @Override
        public void run() {
            ocDao.lambdaUpdate()
                    .set(TornFactionOcDO::getStatus, TornOcStatusEnum.COMPLETED.getCode())
                    .eq(TornFactionOcDO::getId, id)
                    .update();

            OcCheckStrategyImpl check = applicationContext.getBean(OcCheckStrategyImpl.class);
            check.handle("");
        }
    }
}