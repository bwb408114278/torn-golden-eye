package pn.torn.goldeneye.napcat.strategy.user;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.base.exception.BizException;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.napcat.receive.msg.QqRecMsgSender;
import pn.torn.goldeneye.napcat.send.msg.param.QqMsgParam;
import pn.torn.goldeneye.napcat.strategy.base.SmthMsgStrategy;
import pn.torn.goldeneye.repository.dao.setting.TornApiKeyDAO;
import pn.torn.goldeneye.repository.dao.user.TornUserBsSnapshotDAO;
import pn.torn.goldeneye.repository.model.setting.TornApiKeyDO;
import pn.torn.goldeneye.repository.model.user.TornUserBsSnapshotDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.utils.NumberUtils;
import pn.torn.goldeneye.utils.TableImageUtils;

import java.awt.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * BS增幅实现类
 *
 * @author Bai
 * @version 0.3.0
 * @since 2025.10.27
 */
@Component
@RequiredArgsConstructor
public class TornUserBsSnapshotStrategyImpl extends SmthMsgStrategy {
    private final TornUserBsSnapshotDAO bsSnapshotDao;
    private final TornApiKeyDAO keyDao;

    @Override
    public String getCommand() {
        return BotCommands.BS_IMPROVE;
    }

    @Override
    public String getCommandDescription() {
        return "变强了,也变秃了";
    }

    @Override
    public List<? extends QqMsgParam<?>> handle(long groupId, QqRecMsgSender sender, String msg) {
        TornUserDO user = super.getTornUser(sender, msg);
        TornApiKeyDO key = keyDao.lambdaQuery().eq(TornApiKeyDO::getUserId, user.getId()).one();
        if (key == null) {
            return buildTextMsg("这个人还没有绑定Key哦");
        }

        return super.buildImageMsg(generateBsGrowthTable(user));
    }

    /**
     * 生成用户最近7天BS增幅表格
     */
    public String generateBsGrowthTable(TornUserDO user) {
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(7);

        List<TornUserBsSnapshotDO> snapshots = bsSnapshotDao.lambdaQuery()
                .eq(TornUserBsSnapshotDO::getUserId, user.getId())
                .between(TornUserBsSnapshotDO::getRecordDate, startDate, endDate)
                .orderByAsc(TornUserBsSnapshotDO::getRecordDate)
                .list();

        if (snapshots.isEmpty()) {
            throw new BizException("未查询到用户的BS记录");
        }

        List<List<String>> tableData = buildTableData(user, snapshots);
        TableImageUtils.TableConfig config = buildTableConfig(tableData);
        return TableImageUtils.renderTableToBase64(tableData, config);
    }

    /**
     * 构建表格数据
     */
    private List<List<String>> buildTableData(TornUserDO user, List<TornUserBsSnapshotDO> snapshots) {
        List<List<String>> tableData = new ArrayList<>();
        DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("MM-dd");

        tableData.add(List.of(user.getNickname() + "近7日bs增幅", "", "", "", "", ""));
        List<String> headers = List.of("日期", "str", "def", "spd", "dex", "total");
        tableData.add(headers);

        TornUserBsSnapshotDO first = snapshots.getFirst();

        tableData.add(List.of(first.getRecordDate().minusDays(1).format(dateFormatter),
                NumberUtils.addDelimiters(first.getStrength()),
                NumberUtils.addDelimiters(first.getDefense()),
                NumberUtils.addDelimiters(first.getSpeed()),
                NumberUtils.addDelimiters(first.getDexterity()),
                NumberUtils.addDelimiters(first.getTotal())));

        for (int i = 1; i < snapshots.size(); i++) {
            TornUserBsSnapshotDO current = snapshots.get(i);
            TornUserBsSnapshotDO previous = snapshots.get(i - 1);

            List<String> row = new ArrayList<>();
            row.add(current.getRecordDate().minusDays(1).format(dateFormatter));

            // 计算每日增幅
            long strGrowth = current.getStrength() - previous.getStrength();
            long defGrowth = current.getDefense() - previous.getDefense();
            long spdGrowth = current.getSpeed() - previous.getSpeed();
            long dexGrowth = current.getDexterity() - previous.getDexterity();
            long totalGrowth = current.getTotal() - previous.getTotal();

            row.add(NumberUtils.addDelimiters(current.getStrength()) + "(+" + NumberUtils.addDelimiters(strGrowth) + ")");
            row.add(NumberUtils.addDelimiters(current.getDefense()) + "(+" + NumberUtils.addDelimiters(defGrowth) + ")");
            row.add(NumberUtils.addDelimiters(current.getSpeed()) + "(+" + NumberUtils.addDelimiters(spdGrowth) + ")");
            row.add(NumberUtils.addDelimiters(current.getDexterity()) + "(+" + NumberUtils.addDelimiters(dexGrowth) + ")");
            row.add(NumberUtils.addDelimiters(current.getTotal()) + "(+" + NumberUtils.addDelimiters(totalGrowth) + ")");

            tableData.add(row);
        }

        // 添加累计增幅行（样式弱化）
        List<String> summaryRow = buildSummaryRow(snapshots);
        tableData.add(summaryRow);

        return tableData;
    }

    /**
     * 构建累计增幅行
     */
    private List<String> buildSummaryRow(List<TornUserBsSnapshotDO> snapshots) {
        TornUserBsSnapshotDO first = snapshots.getFirst();
        TornUserBsSnapshotDO last = snapshots.getLast();

        List<String> summaryRow = new ArrayList<>();
        summaryRow.add("累计增加");
        summaryRow.add(NumberUtils.addDelimiters(last.getStrength()) + "(+" + NumberUtils.addDelimiters(last.getStrength() - first.getStrength()) + ")");
        summaryRow.add(NumberUtils.addDelimiters(last.getDefense()) + "(+" + NumberUtils.addDelimiters(last.getDefense() - first.getDefense()) + ")");
        summaryRow.add(NumberUtils.addDelimiters(last.getSpeed()) + "(+" + NumberUtils.addDelimiters(last.getSpeed() - first.getSpeed()) + ")");
        summaryRow.add(NumberUtils.addDelimiters(last.getDexterity()) + "(+" + NumberUtils.addDelimiters(last.getDexterity() - first.getDexterity()) + ")");
        summaryRow.add(NumberUtils.addDelimiters(last.getTotal()) + "(+" + NumberUtils.addDelimiters(last.getTotal() - first.getTotal()) + ")");
        return summaryRow;
    }

    /**
     * 构建表格配置
     */
    private TableImageUtils.TableConfig buildTableConfig(List<List<String>> tableData) {
        TableImageUtils.TableConfig config = new TableImageUtils.TableConfig();

        // 设置默认样式
        config.setDefaultFont(new Font("微软雅黑", Font.PLAIN, 14))
                .setDefaultCellHeight(45)
                .setBorderColor(new Color(200, 200, 200))
                .setDefaultBgColor(Color.WHITE);

        int cols = tableData.getFirst().size();
        int rows = tableData.size();

        // 标题样式
        config.addMerge(0, 0, 1, 6);
        config.setCellStyle(0, 0, new TableImageUtils.CellStyle()
                .setBgColor(Color.WHITE)
                .setPadding(25)
                .setFont(new Font("微软雅黑", Font.BOLD, 30)));

        // 表头样式
        TableImageUtils.CellStyle headerStyle = new TableImageUtils.CellStyle()
                .setFont(new Font("微软雅黑", Font.BOLD, 16))
                .setBgColor(new Color(68, 114, 196))
                .setTextColor(Color.WHITE)
                .setAlignment(TableImageUtils.TextAlignment.CENTER)
                .setPadding(12);

        for (int i = 0; i < cols; i++) {
            config.setCellStyle(1, i, headerStyle);
        }

        // 日期列样式
        TableImageUtils.CellStyle dateStyle = new TableImageUtils.CellStyle()
                .setFont(new Font("微软雅黑", Font.BOLD, 14))
                .setBgColor(new Color(217, 225, 242))
                .setTextColor(new Color(51, 51, 51))
                .setAlignment(TableImageUtils.TextAlignment.CENTER);

        for (int i = 2; i < rows; i++) {
            config.setCellStyle(i, 0, dateStyle);
        }

        // 数据单元格样式
        for (int i = 2; i < rows - 1; i++) {
            Color bgColor = (i % 2 == 0) ? new Color(240, 248, 255) : Color.WHITE;

            TableImageUtils.CellStyle dataStyle = new TableImageUtils.CellStyle()
                    .setFont(new Font("微软雅黑", Font.PLAIN, 14))
                    .setBgColor(bgColor)
                    .setTextColor(new Color(0, 102, 204))  // 蓝色字体，突出增幅数值
                    .setAlignment(TableImageUtils.TextAlignment.CENTER);

            for (int j = 1; j < cols; j++) {
                config.setCellStyle(i, j, dataStyle);
            }
        }

        // 累计行样式
        TableImageUtils.CellStyle summaryStyle = new TableImageUtils.CellStyle()
                .setFont(new Font("微软雅黑", Font.BOLD, 14))
                .setBgColor(new Color(234, 244, 234))  // 浅绿色，更柔和
                .setTextColor(new Color(76, 154, 76))  // 深绿色字体
                .setAlignment(TableImageUtils.TextAlignment.CENTER)
                .setPadding(10);

        for (int i = 0; i < cols; i++) {
            config.setCellStyle(rows - 1, i, summaryStyle);
        }

        return config;
    }
}