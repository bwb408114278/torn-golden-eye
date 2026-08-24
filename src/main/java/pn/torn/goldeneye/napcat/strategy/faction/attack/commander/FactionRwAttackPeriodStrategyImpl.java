package pn.torn.goldeneye.napcat.strategy.faction.attack.commander;

import org.springframework.stereotype.Component;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.constants.torn.enums.TornFactionRoleTypeEnum;
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
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * RW对冲窗口攻击频率统计策略。
 *
 * @author Bai
 * @version 1.4.4
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
    public TornFactionRoleTypeEnum getRoleType() {
        return TornFactionRoleTypeEnum.WAR_COMMANDER;
    }

    @Override
    public List<? extends QqMsgParam<?>> handle(long groupId, QqRecMsgSender sender, String msg) {
        RwStatWindowContext context = resolveStatWindowContext(sender, msg);
        if (context.errorMessage() != null) {
            return super.buildTextMsg(context.errorMessage());
        }

        RwStatWindowQuery query = context.query();
        TornFactionRwDO rw = context.rw();
        RwStatWindowVO window = resolveWindow(rw, query);
        if (window == null) {
            return super.buildTextMsg(query.windowCode() == null
                    ? "未查询到已确认对冲窗口" : "未查询到对冲窗口");
        }

        RwAttackFrequencySummaryVO summary = queryFrequency(rw, window);
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
        addSummaryRows(tableData, tableConfig, rw, summary);
        addUserRows(tableData, tableConfig, rw.getFactionName() + " 出手用户统计", summary.getSelfUsers());
        addUserRows(tableData, tableConfig, rw.getOpponentFactionName() + " 出手用户统计", summary.getOpponentUsers());
        return TableImageUtils.renderTableToBase64(tableData, tableConfig);
    }

    /**
     * 添加窗口摘要区块。
     *
     * @param tableData   表格数据
     * @param tableConfig 表格配置
     * @param rw          RW对象
     * @param summary     双方窗口统计摘要
     */
    private void addSummaryRows(List<List<String>> tableData, TableImageUtils.TableConfig tableConfig,
                                TornFactionRwDO rw, RwAttackFrequencySummaryVO summary) {
        RwStatWindowVO window = summary.getWindow();
        tableData.add(List.of("RW攻击频率", "", "", "", "", "", "", ""));
        tableConfig.addMerge(0, 0, 1, 8);
        tableConfig.setCellStyle(0, 0, titleStyle());
        tableData.add(List.of("RWID", "窗口", "时间范围", "对冲时长", "己方总出手", "己方人数", "对方总出手", "对方人数"));
        tableConfig.setSubTitle(1, 8);
        tableData.add(List.of(String.valueOf(rw.getId()), formatWindowLabel(window),
                formatRange(window.getStartTime(), window.getEndTime()),
                formatDuration(window.getStartTime(), window.getEndTime()),
                String.valueOf(summary.getSelfAttackCount()), String.valueOf(summary.getSelfUserCount()),
                String.valueOf(summary.getOpponentAttackCount()), String.valueOf(summary.getOpponentUserCount())));
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
                             String title, List<RwUserAttackStatVO> users) {
        int titleRow = tableData.size();
        tableData.add(List.of(title, "", "", "", "", "", "", ""));
        tableConfig.addMerge(titleRow, 0, 1, 8);
        tableConfig.setCellStyle(titleRow, 0, sectionStyle());

        int headerRow = tableData.size();
        tableData.add(List.of("Rank", "ID", "昵称", "出手次数", "出手频率(次/分钟)", "", "", ""));
        tableConfig.setSubTitle(headerRow, 8);
        if (users.isEmpty()) {
            tableData.add(List.of("", "", "无有效出手记录", "", "", "", "", ""));
            return;
        }
        for (int i = 0; i < users.size(); i++) {
            RwUserAttackStatVO user = users.get(i);
            tableData.add(List.of(String.valueOf(i + 1), String.valueOf(user.getUserId()), user.getNickname(),
                    String.valueOf(user.getAttackCount()), formatRate(user.getAttackRatePerMinute()), "", "", ""));
        }
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
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @return 分秒格式的持续时长
     */
    private String formatDuration(LocalDateTime startTime, LocalDateTime endTime) {
        long seconds = Math.max(0, Duration.between(startTime, endTime).getSeconds());
        return (seconds / 60) + "分" + (seconds % 60) + "秒";
    }

    /**
     * 格式化每分钟出手频率。
     *
     * @param rate 每分钟出手频率
     * @return 保留两位小数的频率文本
     */
    private String formatRate(BigDecimal rate) {
        return rate == null ? "0.00" : rate.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }
}
