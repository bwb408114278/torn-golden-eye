package pn.torn.goldeneye.msg.strategy.faction.crime;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.msg.send.param.GroupMsgParam;
import pn.torn.goldeneye.msg.strategy.BaseMsgStrategy;
import pn.torn.goldeneye.torn.service.faction.oc.TornFactionOcService;

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
    private final TornFactionOcService ocService;

    @Override
    public String getCommand() {
        return BotCommands.OC_CHECK;
    }

    @Override
    public List<? extends GroupMsgParam<?>> handle(String msg) {
        ocService.scheduleOcTask();
        return super.buildTextMsg("OC数据校准完成");
    }
}