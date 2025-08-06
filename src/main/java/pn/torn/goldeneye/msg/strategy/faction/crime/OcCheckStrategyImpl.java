package pn.torn.goldeneye.msg.strategy.faction.crime;

import lombok.AllArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.base.torn.TornApi;
import pn.torn.goldeneye.configuration.DynamicTaskService;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.constants.torn.enums.TornOcStatusEnum;
import pn.torn.goldeneye.msg.send.param.GroupMsgParam;
import pn.torn.goldeneye.msg.strategy.BaseMsgStrategy;
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
    private final DynamicTaskService taskService;
    private final TornApi tornApi;
    private final TornFactionOcService ocService;

    @Override
    public String getCommand() {
        return BotCommands.OC_CHECK;
    }

    @Override
    public List<? extends GroupMsgParam<?>> handle(String msg) {
        new TornOcRequest(TornOcStatusEnum.PLANNING).run();
        new TornOcRequest(TornOcStatusEnum.RECRUITING).run();
        return super.buildTextMsg("OC数据校准完成");
    }

    @AllArgsConstructor
    private class TornOcRequest implements Runnable {
        /**
         * 要同步的状态
         */
        private TornOcStatusEnum status;

        @Override
        public void run() {
            TornFactionOcVO oc = tornApi.sendRequest(new TornFactionOcDTO(status), TornFactionOcVO.class);
            if (oc == null) {
                taskService.updateTask("request-retry-oc-" + status.getCode(),
                        new TornOcRequest(status),
                        DateTimeUtils.convertToInstant(LocalDateTime.now().plusMinutes(2)), null);
            } else {
                ocService.updateOc(oc.getCrimes());
            }
        }
    }
}