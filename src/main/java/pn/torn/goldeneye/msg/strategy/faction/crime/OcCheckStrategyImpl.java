package pn.torn.goldeneye.msg.strategy.faction.crime;

import jakarta.annotation.PostConstruct;
import lombok.AllArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.base.bot.Bot;
import pn.torn.goldeneye.base.torn.TornApi;
import pn.torn.goldeneye.configuration.DynamicTaskService;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.msg.send.GroupMsgHttpBuilder;
import pn.torn.goldeneye.msg.send.param.GroupMsgParam;
import pn.torn.goldeneye.msg.send.param.TextGroupMsg;
import pn.torn.goldeneye.msg.strategy.BaseMsgStrategy;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.torn.model.faction.crime.TornFactionOcDTO;
import pn.torn.goldeneye.torn.model.faction.crime.TornFactionOcVO;
import pn.torn.goldeneye.torn.service.faction.oc.TornFactionOcService;
import pn.torn.goldeneye.utils.DateTimeUtils;

import java.time.LocalDateTime;
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
public class OcCheckStrategyImpl extends BaseMsgStrategy {
    private final Bot bot;
    private final DynamicTaskService taskService;
    private final TornApi tornApi;
    private final TornFactionOcService ocService;
    private final TornFactionOcDAO ocDao;
    private static final String TASK_ID = "oc-reload";

    @Override
    public String getCommand() {
        return BotCommands.OC_CHECK;
    }

    @Override
    public List<? extends GroupMsgParam<?>> handle(String msg) {
        new TornOcRequest().run();
        return super.buildTextMsg("OC数据校准完成");
    }

    @PostConstruct
    public void init() {
        handle("");
        List<TornFactionOcDO> ocList = ocDao.queryRotationExecList();

        GroupMsgHttpBuilder builder = new GroupMsgHttpBuilder()
                .setGroupId(testProperty.getGroupId())
                .addMsg(new TextGroupMsg("OC轮转队加载完成"));
        if (CollectionUtils.isEmpty(ocList)) {
            builder.addMsg(new TextGroupMsg("\n当前没有轮转队"));
        } else {
            for (TornFactionOcDO oc : ocList) {
                builder.addMsg(new TextGroupMsg("\n" + oc.getRank() + "级: 抢车位时间为" +
                        DateTimeUtils.convertToString(oc.getReadyTime())));
            }
        }

        bot.sendRequest(builder.build(), String.class);
    }

    @AllArgsConstructor
    private class TornOcRequest implements Runnable {
        @Override
        public void run() {
            TornFactionOcVO oc = tornApi.sendRequest(new TornFactionOcDTO(), TornFactionOcVO.class);
            if (oc == null) {
                taskService.updateTask(TASK_ID, this,
                        DateTimeUtils.convertToInstant(LocalDateTime.now().plusMinutes(1)), null);
            } else {
                ocService.updateOc(oc.getCrimes());
                TornFactionOcService.reloadLastSyncTime();
                taskService.updateTask(TASK_ID, this,
                        DateTimeUtils.convertToInstant(LocalDateTime.now().plusHours(1)), null);
            }
        }
    }
}