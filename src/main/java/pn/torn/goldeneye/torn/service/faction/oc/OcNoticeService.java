package pn.torn.goldeneye.torn.service.faction.oc;

import lombok.AllArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.base.bot.BotSocketReqParam;
import pn.torn.goldeneye.configuration.BotSocketClient;
import pn.torn.goldeneye.configuration.property.TestProperty;
import pn.torn.goldeneye.constants.bot.BotConstants;
import pn.torn.goldeneye.constants.torn.enums.TornOcStatusEnum;
import pn.torn.goldeneye.msg.send.GroupMsgSocketBuilder;
import pn.torn.goldeneye.msg.send.param.TextGroupMsg;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcSlotDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.utils.DateTimeUtils;

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
    private final TornFactionOcDAO ocDao;
    private final TornFactionOcSlotDAO slotDao;
    private final ApplicationContext applicationContext;
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
                    .addMsg(new TextGroupMsg("当前级别Planning的OC详情如下:" +
                            "\n执行时间: " + DateTimeUtils.convertToString(oc.getReadyTime()) +
                            "\n岗位列表: " + buildSlotMsg(slotList)))
                    .build();
            BotSocketClient socketClient = applicationContext.getBean(BotSocketClient.class);
            socketClient.sendMessage(param);
        }

        /**
         * 构建岗位详细消息
         *
         * @return 岗位消息消息
         */
        private String buildSlotMsg(List<TornFactionOcSlotDO> slots) {
            StringBuilder builder = new StringBuilder();
            for (TornFactionOcSlotDO slot : slots) {
                builder.append("岗位: ").append(slot.getPosition())
                        .append(", 人员: ").append(slot.getUserId())
                        .append(", 成功率: ").append(slot.getPassRate())
                        .append(", 加入时间: ").append(DateTimeUtils.convertToString(slot.getJoinTime()))
                        .append("\n");
            }
            return builder.toString();
        }
    }
}