package pn.torn.goldeneye.msg.strategy.manage;

import lombok.RequiredArgsConstructor;
import org.apache.commons.collections4.MapUtils;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.configuration.DynamicTaskService;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.msg.receive.QqRecMsgSender;
import pn.torn.goldeneye.msg.send.param.QqMsgParam;
import pn.torn.goldeneye.msg.strategy.BaseGroupMsgStrategy;
import pn.torn.goldeneye.utils.DateTimeUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
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
public class CurrentTaskStrategyImpl extends BaseGroupMsgStrategy {
    private final DynamicTaskService taskService;

    @Override
    public String getCommand() {
        return BotCommands.CURRENT_TASK;
    }

    @Override
    public String getCommandDescription() {
        return "获取当前待运行的定时任务";
    }

    @Override
    public List<? extends QqMsgParam<?>> handle(long groupId, QqRecMsgSender sender, String msg) {
        Map<String, LocalDateTime> taskMap = taskService.getScheduledTask();
        if (MapUtils.isEmpty(taskMap)) {
            return buildTextMsg("当前没有待执行的任务");
        }

        List<String> sortedKeys = new ArrayList<>(taskMap.keySet());
        Collections.sort(sortedKeys);

        StringBuilder builder = new StringBuilder();
        for (String key : sortedKeys) {
            builder.append("\nID: ")
                    .append(key)
                    .append(" 下次执行时间: ")
                    .append(DateTimeUtils.convertToString(taskMap.get(key)));
        }
        return super.buildTextMsg(builder.toString().replaceFirst("\n", ""));
    }
}