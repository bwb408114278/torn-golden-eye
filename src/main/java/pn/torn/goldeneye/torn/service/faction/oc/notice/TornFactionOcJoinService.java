package pn.torn.goldeneye.torn.service.faction.oc.notice;

import lombok.AllArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.base.bot.Bot;
import pn.torn.goldeneye.base.bot.BotHttpReqParam;
import pn.torn.goldeneye.base.model.TableDataBO;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.constants.torn.TornConstants;
import pn.torn.goldeneye.msg.send.GroupMsgHttpBuilder;
import pn.torn.goldeneye.msg.send.param.ImageQqMsg;
import pn.torn.goldeneye.msg.send.param.TextQqMsg;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.torn.manager.faction.oc.msg.TornFactionOcMsgManager;
import pn.torn.goldeneye.torn.manager.faction.oc.msg.TornFactionOcMsgTableManager;
import pn.torn.goldeneye.utils.TableImageUtils;

import java.util.List;
import java.util.Map;

/**
 * OC可加入提示逻辑
 *
 * @author Bai
 * @version 0.2.0
 * @since 2025.08.06
 */
@Component
@RequiredArgsConstructor
public class TornFactionOcJoinService extends BaseTornFactionOcNoticeService {
    private final Bot bot;
    private final TornFactionOcMsgManager msgManager;
    private final TornFactionOcMsgTableManager msgTableManager;
    private final ProjectProperty projectProperty;

    /**
     * 构建提醒
     */
    public Runnable buildNotice(TornFactionOcNoticeBO param) {
        return new JoinNotice(param);
    }

    @AllArgsConstructor
    private class JoinNotice implements Runnable {
        /**
         * OC级别
         */
        private final TornFactionOcNoticeBO param;

        @Override
        public void run() {
            List<TornFactionOcDO> recList = findRecList(param);
            Map<TornFactionOcDO, List<TornFactionOcSlotDO>> lackMap = buildLackMap(recList);

            String rankDesc = buildRankDesc(param);
            TableDataBO table = msgTableManager.buildOcTable(rankDesc + "级OC缺人队伍（不包含新队）", lackMap);

            BotHttpReqParam botParam = new GroupMsgHttpBuilder()
                    .setGroupId(projectProperty.getGroupId())
                    .addMsg(new TextQqMsg(rankDesc + "级可以进了\n"))
                    .addMsg(msgManager.buildSlotMsg(param.planId(), TornConstants.FACTION_PN_ID, param.enableRank()))
                    .addMsg(new ImageQqMsg(TableImageUtils.renderTableToBase64(table)))
                    .build();
            bot.sendRequest(botParam, String.class);
        }
    }
}