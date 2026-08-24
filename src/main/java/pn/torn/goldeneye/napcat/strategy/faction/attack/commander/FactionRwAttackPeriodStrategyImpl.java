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
import pn.torn.goldeneye.utils.image.TableImageUtils;

import java.awt.Color;
import java.awt.Font;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
    private static final DateTimeFormatter DISPLAY_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DISPLAY_TIME_ONLY_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");
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
        RwStatWindowQuery query;
        try {
            query = parseStatWindowQuery(msg);
        } catch (IllegalArgumentException e) {
            return super.buildTextMsg(e.getMessage());
        }

        TornFactionRwDO rw = getStatWindowRw(sender, query);
        if (rw == null) {
            return super.buildTextMsg("暂无RW真赛数据");
        }

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

    private RwStatWindowVO resolveWindow(TornFactionRwDO rw, RwStatWindowQuery query) {
        if (query.windowCode() != null) {
            return getExplicitStatWindow(rw, query.windowCode());
        }
        return getLatestConfirmedStatWindow(rw);
    }

    private String buildAttackMsg(TornFactionRwDO rw, RwAttackFrequencySummaryVO summary) {
        List<List<String>> tableData = new ArrayList<>();
        TableImageUtils.TableConfig tableConfig = new TableImageUtils.TableConfig();
        addSummaryRows(tableData, tableConfig, rw, summary);
        addUserRows(tableData, tableConfig, rw.getFactionName() + " 出手用户统计", summary.getSelfUsers());
        addUserRows(tableData, tableConfig, rw.getOpponentFactionName() + " 出手用户统计", summary.getOpponentUsers());
        return TableImageUtils.renderTableToBase64(tableData, tableConfig);
    }

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

    private TableImageUtils.CellStyle titleStyle() {
        return new TableImageUtils.CellStyle()
                .setBgColor(Color.WHITE)
                .setPadding(20)
                .setFont(new Font("微软雅黑", Font.BOLD, 26));
    }

    private TableImageUtils.CellStyle sectionStyle() {
        return new TableImageUtils.CellStyle()
                .setBgColor(Color.WHITE)
                .setPadding(12)
                .setFont(new Font("微软雅黑", Font.BOLD, 18));
    }

    private String formatRange(LocalDateTime startTime, LocalDateTime endTime) {
        if (startTime.toLocalDate().equals(endTime.toLocalDate())) {
            return startTime.format(DISPLAY_TIME_FORMATTER) + " ~ "
                    + endTime.toLocalTime().format(DISPLAY_TIME_ONLY_FORMATTER);
        }
        return startTime.format(DISPLAY_TIME_FORMATTER) + " ~ " + endTime.format(DISPLAY_TIME_FORMATTER);
    }

    private String formatWindowLabel(RwStatWindowVO window) {
        return Boolean.TRUE.equals(window.getConfirmed()) ? window.getWindowCode()
                : window.getWindowCode() + " (进行中)";
    }

    private String formatDuration(LocalDateTime startTime, LocalDateTime endTime) {
        long seconds = Math.max(0, Duration.between(startTime, endTime).getSeconds());
        return (seconds / 60) + "分" + (seconds % 60) + "秒";
    }

    private String formatRate(BigDecimal rate) {
        return rate == null ? "0.00" : rate.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }
}
