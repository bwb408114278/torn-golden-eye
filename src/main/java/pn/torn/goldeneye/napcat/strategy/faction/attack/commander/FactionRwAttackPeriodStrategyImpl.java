package pn.torn.goldeneye.napcat.strategy.faction.attack.commander;

import org.springframework.stereotype.Component;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.napcat.receive.msg.QqRecMsgSender;
import pn.torn.goldeneye.napcat.send.msg.param.QqMsgParam;
import pn.torn.goldeneye.napcat.strategy.faction.attack.BaseRwStrategy;
import pn.torn.goldeneye.repository.model.faction.attack.TornFactionRwDO;
import pn.torn.goldeneye.torn.model.faction.attack.RwAttackFrequencySummaryVO;
import pn.torn.goldeneye.torn.model.faction.attack.RwStatWindowQuery;
import pn.torn.goldeneye.torn.model.faction.attack.RwStatWindowVO;
import pn.torn.goldeneye.torn.model.faction.attack.RwUserAttackStatVO;
import pn.torn.goldeneye.utils.DateTimeUtils;
import pn.torn.goldeneye.utils.image.TableImageUtils;

import java.awt.*;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * RW对冲窗口攻击频率统计策略。
 *
 * @author Bai
 * @version 1.4.8
 * @since 2026.08.24
 */
@Component
public class FactionRwAttackPeriodStrategyImpl extends BaseRwStrategy {
    @Override
    public String getCommand() {
        return BotCommands.RW_ATTACK_PERIOD;
    }

    @Override
    public String getCommandDescription() {
        return "RW对冲窗口攻击频率分析";
    }

    @Override
    public List<? extends QqMsgParam<?>> handle(long groupId, QqRecMsgSender sender, String msg) {
        RwStatWindowContext context = resolveStatWindowContext(sender, msg);
        if (context.errorMessage() != null) {
            return super.buildTextMsg(context.errorMessage());
        }

        RwStatWindowQuery query = context.query();
        TornFactionRwDO rw = context.rw();
        RwAttackFrequencySummaryVO summary;
        if (query.allWindows()) {
            List<RwStatWindowVO> windows = getStatWindowCatalog(rw);
            if (windows.isEmpty()) {
                return super.buildTextMsg("未查询到对冲窗口");
            }
            summary = queryFrequencyForAllWindows(rw, windows);
        } else {
            RwStatWindowVO window = resolveWindow(rw, query);
            if (window == null) {
                return super.buildTextMsg(query.windowCode() == null
                        ? "未查询到已确认对冲窗口" : "未查询到对冲窗口");
            }
            summary = queryFrequency(rw, window);
        }
        if (summary.getSelfAttackCount() == 0 && summary.getOpponentAttackCount() == 0) {
            return super.buildTextMsg("未查询到战斗记录");
        }
        return super.buildImageMsg(buildAttackMsg(rw, summary));
    }

    /**
     * 根据查询参数解析显式窗口或默认窗口。
     *
     * @param rw    RW对象
     * @param query RW统计窗口查询参数
     * @return 目标窗口，不存在时返回null
     */
    private RwStatWindowVO resolveWindow(TornFactionRwDO rw, RwStatWindowQuery query) {
        if (query.windowCode() != null) {
            return getExplicitStatWindow(rw, query.windowCode());
        }
        return getLatestConfirmedStatWindow(rw);
    }

    /**
     * 构建攻击频率统计图片。
     *
     * @param rw      RW对象
     * @param summary 双方窗口统计摘要
     * @return 图片Base64内容
     */
    private String buildAttackMsg(TornFactionRwDO rw, RwAttackFrequencySummaryVO summary) {
        List<List<String>> tableData = new ArrayList<>();
        TableImageUtils.TableConfig tableConfig = new TableImageUtils.TableConfig();
        boolean showAttackPeriod = summary.getWindow() != null;
        int columnCount = showAttackPeriod ? 6 : 5;
        tableData.add(emptyCells("RW攻击频率", columnCount));
        tableConfig.addMerge(0, 0, 1, columnCount);
        tableConfig.setCellStyle(0, 0, titleStyle());

        int summaryRow = tableData.size();
        tableData.add(emptyCells(buildSummaryText(rw, summary), columnCount));
        tableConfig.addMerge(summaryRow, 0, 1, columnCount);
        tableConfig.setCellStyle(summaryRow, 0, summaryStyle());

        addUserRows(tableData, tableConfig, rw.getFactionName() + " 出手用户统计", summary.getSelfUsers(), showAttackPeriod);
        addUserRows(tableData, tableConfig, rw.getOpponentFactionName() + " 出手用户统计",
                summary.getOpponentUsers(), showAttackPeriod);
        return TableImageUtils.renderTableToBase64(tableData, tableConfig);
    }

    /**
     * 将统计范围与双方汇总沉淀为单行摘要文本。
     *
     * @param rw      RW对象
     * @param summary 双方窗口统计摘要
     * @return 摘要文本
     */
    private String buildSummaryText(TornFactionRwDO rw, RwAttackFrequencySummaryVO summary) {
        StringBuilder text = new StringBuilder("RW ").append(rw.getId());
        if (summary.getWindow() != null) {
            text.append(" 窗口").append(formatWindowLabel(summary.getWindow()))
                    .append(" ").append(formatRange(summary.getWindow().getStartTime(),
                            summary.getWindow().getEndTime()))
                    .append(" 对冲").append(formatDurationSeconds(summary.getTotalWindowSeconds()));
        } else {
            text.append(" 全部").append(summary.getWindowCount()).append("个窗口")
                    .append(" 合计对冲").append(formatDurationSeconds(summary.getTotalWindowSeconds()));
        }
        text.append(" 己方总出手").append(summary.getSelfAttackCount())
                .append("次/").append(summary.getSelfUserCount()).append("人")
                .append(" 对方总出手").append(summary.getOpponentAttackCount())
                .append("次/").append(summary.getOpponentUserCount()).append("人");
        return text.toString();
    }

    /**
     * 添加一方用户出手统计区块。
     *
     * @param tableData   表格数据
     * @param tableConfig 表格配置
     * @param title       区块标题
     * @param users       用户统计
     */
    private void addUserRows(List<List<String>> tableData, TableImageUtils.TableConfig tableConfig,
                             String title, List<RwUserAttackStatVO> users, boolean showAttackPeriod) {
        int columnCount = showAttackPeriod ? 6 : 5;
        int titleRow = tableData.size();
        tableData.add(emptyCells(title, columnCount));
        tableConfig.addMerge(titleRow, 0, 1, columnCount);
        tableConfig.setCellStyle(titleRow, 0, sectionStyle());

        int headerRow = tableData.size();
        List<String> headers = new ArrayList<>(List.of("Rank", "ID", "昵称", "出手次数"));
        headers.add("出手频率(次/分钟)");
        if (showAttackPeriod) {
            headers.add("攻击时间段");
        }
        tableData.add(headers);
        tableConfig.setSubTitle(headerRow, columnCount);
        if (users.isEmpty()) {
            tableData.add(emptyCells("", "", "无有效出手记录", columnCount - 3));
            return;
        }
        for (int i = 0; i < users.size(); i++) {
            RwUserAttackStatVO user = users.get(i);
            List<String> row = new ArrayList<>(List.of(String.valueOf(i + 1), String.valueOf(user.getUserId()),
                    user.getNickname(), String.valueOf(user.getAttackCount())));
            row.add(formatRate(user));
            if (showAttackPeriod) {
                row.add(formatAttackPeriod(user));
            }
            tableData.add(row);
        }
    }

    private List<String> emptyCells(String firstCell, int columnCount) {
        List<String> cells = new ArrayList<>();
        cells.add(firstCell);
        cells.addAll(java.util.Collections.nCopies(columnCount - 1, ""));
        return cells;
    }

    private List<String> emptyCells(String firstCell, String secondCell, String thirdCell, int remainingCount) {
        List<String> cells = new ArrayList<>(List.of(firstCell, secondCell, thirdCell));
        cells.addAll(java.util.Collections.nCopies(remainingCount, ""));
        return cells;
    }

    /**
     * 创建图片主标题样式。
     *
     * @return 主标题样式
     */
    private TableImageUtils.CellStyle titleStyle() {
        return new TableImageUtils.CellStyle()
                .setBgColor(Color.WHITE)
                .setPadding(20)
                .setFont(new Font("微软雅黑", Font.BOLD, 26));
    }

    /**
     * 创建摘要文本样式。
     *
     * @return 摘要文本样式
     */
    private TableImageUtils.CellStyle summaryStyle() {
        return new TableImageUtils.CellStyle()
                .setBgColor(Color.WHITE)
                .setPadding(8)
                .setFont(new Font("微软雅黑", Font.PLAIN, 14));
    }

    /**
     * 创建图片分区标题样式。
     *
     * @return 分区标题样式
     */
    private TableImageUtils.CellStyle sectionStyle() {
        return new TableImageUtils.CellStyle()
                .setBgColor(Color.WHITE)
                .setPadding(12)
                .setFont(new Font("微软雅黑", Font.BOLD, 18));
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

    private String formatWindowLabel(RwStatWindowVO window) {
        return Boolean.TRUE.equals(window.getConfirmed()) ? window.getWindowCode()
                : window.getWindowCode() + " (进行中)";
    }

    /**
     * 格式化窗口持续时长。
     *
     * @param seconds 持续秒数
     * @return 分秒格式的持续时长
     */
    private String formatDurationSeconds(long seconds) {
        seconds = Math.max(0, seconds);
        return (seconds / 60) + "分" + (seconds % 60) + "秒";
    }

    /**
     * 格式化每分钟出手频率。
     * <p>
     * 首末出手同秒（含单次出手）时频率无法定义，降级显示出手次数。
     *
     * @param user 用户出手统计
     * @return 保留两位小数的频率文本，或"N次"形式的降级文本
     */
    private String formatRate(RwUserAttackStatVO user) {
        if (user.getAttackRatePerMinute() == null) {
            return user.getAttackCount().toString();
        }
        return user.getAttackRatePerMinute().setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private String formatAttackPeriod(RwUserAttackStatVO user) {
        if (user.getFirstAttackTime() == null || user.getLastAttackTime() == null) {
            return "";
        }
        return formatRange(user.getFirstAttackTime(), user.getLastAttackTime());
    }
}
