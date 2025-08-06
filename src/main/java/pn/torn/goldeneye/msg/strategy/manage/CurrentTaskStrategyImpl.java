package pn.torn.goldeneye.msg.strategy.manage;

import lombok.RequiredArgsConstructor;
import org.apache.commons.collections4.MapUtils;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.configuration.DynamicTaskService;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.msg.send.param.GroupMsgParam;
import pn.torn.goldeneye.msg.strategy.BaseMsgStrategy;
import pn.torn.goldeneye.utils.DateTimeUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 获取当前任务策略实现类
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.08.06
 */
@Component
@RequiredArgsConstructor
public class CurrentTaskStrategyImpl extends BaseMsgStrategy {
    private final DynamicTaskService taskService;

    @Override
    public String getCommand() {
        return BotCommands.CURRENT_TASK;
    }

    @Override
    public List<? extends GroupMsgParam<?>> handle(String msg) {
        Map<String, LocalDateTime> taskMap = taskService.getScheduledTask();
        if (MapUtils.isEmpty(taskMap)) {
            return buildTextMsg("当前没有待执行的任务");
        }

        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, LocalDateTime> entry : taskMap.entrySet()) {
            builder.append("\nID: ")
                    .append(entry.getKey())
                    .append(" 下次执行时间: ")
                    .append(DateTimeUtils.convertToString(entry.getValue()));
        }
        return super.buildTextMsg(builder.toString().replaceFirst("\n", ""));
    }
}