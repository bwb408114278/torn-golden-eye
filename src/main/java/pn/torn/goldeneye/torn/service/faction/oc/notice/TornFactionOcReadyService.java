package pn.torn.goldeneye.torn.service.faction.oc.notice;

import lombok.AllArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.base.bot.Bot;
import pn.torn.goldeneye.base.bot.BotHttpReqParam;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.msg.send.GroupMsgHttpBuilder;
import pn.torn.goldeneye.msg.send.param.TextQqMsg;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.torn.manager.faction.oc.TornFactionOcMsgManager;
import pn.torn.goldeneye.utils.DateTimeUtils;

/**
 * OC准备加入提示逻辑
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.07.24
 */
@Component
@RequiredArgsConstructor
public class TornFactionOcReadyService extends BaseTornFactionOcNoticeService {
    private final Bot bot;
    private final TornFactionOcMsgManager msgManager;
    private final TornFactionOcDAO ocDao;
    private final ProjectProperty projectProperty;

    /**
     * 构建提醒
     */
    public Runnable buildNotice(TornFactionOcNoticeBO param) {
        return new Notice(param);
    }

    @AllArgsConstructor
    private class Notice implements Runnable {
        /**
         * OC级别
         */
        private final TornFactionOcNoticeBO param;

        @Override
        public void run() {
            TornFactionOcDO oc = ocDao.getById(param.planId());

            BotHttpReqParam botParam = new GroupMsgHttpBuilder()
                    .setGroupId(projectProperty.getGroupId())
                    .addMsg(new TextQqMsg("5分钟后" + buildRankDesc(param) + "级OC准备抢车位" +
                            "\n开始加入时间: " + DateTimeUtils.convertToString(oc.getReadyTime()) + "\n"))
                    .addMsg(msgManager.buildSlotMsg(param.planId(), param.rank()))
                    .build();
            bot.sendRequest(botParam, String.class);
        }
    }
}