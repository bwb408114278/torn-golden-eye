package pn.torn.goldeneye.torn.service.faction.oc.notice;

import lombok.AllArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.base.bot.Bot;
import pn.torn.goldeneye.base.bot.BotHttpReqParam;
import pn.torn.goldeneye.constants.bot.BotConstants;
import pn.torn.goldeneye.msg.send.GroupMsgHttpBuilder;
import pn.torn.goldeneye.msg.send.param.TextGroupMsg;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.torn.manager.faction.oc.TornFactionOcMsgManager;
import pn.torn.goldeneye.utils.DateTimeUtils;

import java.time.LocalDateTime;

/**
 * OC准备加入提示逻辑
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.07.24
 */
@Component
@RequiredArgsConstructor
public class TornFactionOcReadyService {
    private final Bot bot;
    private final TornFactionOcMsgManager msgManager;
    private final TornFactionOcDAO ocDao;

    /**
     * 构建提醒
     *
     * @param id oc ID
     */
    public Runnable buildNotice(long id) {
        return new Notice(id);
    }

    @AllArgsConstructor
    private class Notice implements Runnable {
        /**
         * OC级别
         */
        private final long id;

        @Override
        public void run() {
            TornFactionOcDO oc = ocDao.getById(id);
            if (oc == null || LocalDateTime.now().isAfter(oc.getReadyTime())) {
                return;
            }

            BotHttpReqParam param = new GroupMsgHttpBuilder()
                    .setGroupId(BotConstants.PN_GROUP_ID)
                    .addMsg(new TextGroupMsg("5分钟后" + oc.getRank() + "级OC准备抢车位" +
                            "\n开始加入时间: " + DateTimeUtils.convertToString(oc.getReadyTime()) + "\n"))
                    .addMsg(msgManager.buildSlotMsg(oc))
                    .build();
            bot.sendRequest(param, String.class);
        }
    }
}