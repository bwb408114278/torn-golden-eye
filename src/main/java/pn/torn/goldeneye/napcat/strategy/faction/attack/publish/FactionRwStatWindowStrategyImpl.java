package pn.torn.goldeneye.napcat.strategy.faction.attack.publish;

import org.springframework.stereotype.Component;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.napcat.receive.msg.QqRecMsgSender;
import pn.torn.goldeneye.napcat.send.msg.param.QqMsgParam;
import pn.torn.goldeneye.napcat.strategy.faction.attack.BaseRwStrategy;
import pn.torn.goldeneye.repository.model.faction.attack.TornFactionRwDO;
import pn.torn.goldeneye.torn.model.faction.attack.RwStatWindowQuery;
import pn.torn.goldeneye.torn.model.faction.attack.RwStatWindowVO;
import pn.torn.goldeneye.utils.DateTimeUtils;
import pn.torn.goldeneye.utils.image.TableImageUtils;

import java.awt.*;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * RW对冲窗口目录指令策略。
 *
 * @author Bai
 * @version 1.4.4
 * @since 2026.08.24
 */
@Component
public class FactionRwStatWindowStrategyImpl extends BaseRwStrategy {
    @Override
    public String getCommand() {
        return BotCommands.RW_STAT_WINDOW;
    }

    @Override
    public String getCommandDescription() {
        return "查询RW对冲窗口";
    }

    @Override
    public List<? extends QqMsgParam<?>> handle(long groupId, QqRecMsgSender sender, String msg) {
        RwStatWindowContext context = resolveStatWindowContext(sender, msg);
        if (context.errorMessage() != null) {
            return super.buildTextMsg(context.errorMessage());
        }
        RwStatWindowQuery query = context.query();
        if (query.windowCode() != null) {
            return super.buildTextMsg("参数有误");
        }

        TornFactionRwDO rw = context.rw();
        List<RwStatWindowVO> windows = getStatWindowCatalog(rw);
        if (windows.isEmpty()) {
            return super.buildTextMsg("未查询到对冲窗口");
        }
        return super.buildImageMsg(buildCatalogImage(rw, windows));
    }

    /**
     * 构建RW对冲窗口目录图片。
     *
     * @param rw      RW对象
     * @param windows 窗口目录
     * @return 图片Base64内容
     */
    private String buildCatalogImage(TornFactionRwDO rw, List<RwStatWindowVO> windows) {
        List<List<String>> tableData = new ArrayList<>();
        TableImageUtils.TableConfig tableConfig = new TableImageUtils.TableConfig();
        tableData.add(List.of(rw.getFactionName() + " VS " + rw.getOpponentFactionName()
                + " RW " + rw.getId() + " 对冲窗口", "", "", "", "", ""));
        tableConfig.addMerge(0, 0, 1, 6);
        tableConfig.setCellStyle(0, 0, new TableImageUtils.CellStyle()
                .setBgColor(Color.WHITE)
                .setPadding(20)
                .setFont(new Font("微软雅黑", Font.BOLD, 24)));
        tableData.add(List.of("窗口", "时间范围", "时长", "状态", "己方出手", "对方出手"));
        tableConfig.setSubTitle(1, 6);
        for (RwStatWindowVO window : windows) {
            tableData.add(List.of(window.getWindowCode(), formatRange(window.getStartTime(), window.getEndTime()),
                    formatDuration(window.getStartTime(), window.getEndTime()),
                    Boolean.TRUE.equals(window.getConfirmed()) ? "已确认" : "进行中",
                    String.valueOf(window.getSelfAttackCount()), String.valueOf(window.getOpponentAttackCount())));
        }
        return TableImageUtils.renderTableToBase64(tableData, tableConfig);
    }

    /**
     * 格式化窗口时间范围。
     *
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @return 格式化后的时间范围
     */
    private String formatRange(LocalDateTime startTime, LocalDateTime endTime) {
        if (startTime.toLocalDate().equals(endTime.toLocalDate())) {
            return DateTimeUtils.convertToString(startTime) + " ~ "
                    + DateTimeUtils.convertToString(endTime.toLocalTime());
        }
        return DateTimeUtils.convertToString(startTime) + " ~ " + DateTimeUtils.convertToString(endTime);
    }

    /**
     * 格式化窗口持续时长。
     *
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @return 分秒格式的持续时长
     */
    private String formatDuration(LocalDateTime startTime, LocalDateTime endTime) {
        long seconds = Math.max(0, Duration.between(startTime, endTime).getSeconds());
        return (seconds / 60) + "分" + (seconds % 60) + "秒";
    }
}
