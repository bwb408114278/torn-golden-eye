package pn.torn.goldeneye.msg.strategy.manage;

import lombok.RequiredArgsConstructor;
import org.apache.commons.collections4.MapUtils;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.configuration.DynamicTaskService;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.msg.receive.QqRecMsgSender;
import pn.torn.goldeneye.msg.send.param.QqMsgParam;
import pn.torn.goldeneye.msg.strategy.base.BaseGroupMsgStrategy;
import pn.torn.goldeneye.utils.DateTimeUtils;
import pn.torn.goldeneye.utils.TableImageUtils;

import java.awt.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 获取当前任务策略实现类
 *
 * @author Bai
 * @version 0.3.0
 * @since 2025.08.06
 */
@Component
@RequiredArgsConstructor
public class CurrentTaskStrategyImpl extends BaseGroupMsgStrategy {
    private final DynamicTaskService taskService;

    @Override
    public boolean isNeedSa() {
        return true;
    }

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

        return super.buildImageMsg(buildTaskMsg(taskMap));
    }

    /**
     * 构建定时任务表格
     */
    private String buildTaskMsg(Map<String, LocalDateTime> taskMap) {
        List<List<String>> tableData = new ArrayList<>();
        TableImageUtils.TableConfig tableConfig = new TableImageUtils.TableConfig();

        tableData.add(List.of("当前待执行任务", ""));
        tableConfig.addMerge(0, 0, 1, 2);
        tableConfig.setCellStyle(0, 0, new TableImageUtils.CellStyle()
                .setBgColor(Color.WHITE)
                .setPadding(25)
                .setFont(new Font("微软雅黑", Font.BOLD, 25)));

        tableData.add(List.of("Task Id ", "下次执行时间"));
        tableConfig.setSubTitle(1, 2);

        List<Map.Entry<String, LocalDateTime>> taskList = taskMap.entrySet().stream()
                .sorted(Map.Entry.comparingByValue()).toList();
        for (Map.Entry<String, LocalDateTime> entry : taskList) {
            tableData.add(List.of(entry.getKey(), DateTimeUtils.convertToString(entry.getValue())));
        }

        return TableImageUtils.renderTableToBase64(tableData, tableConfig);
    }
}