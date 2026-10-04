package pn.torn.goldeneye.napcat.strategy.vip;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.napcat.send.msg.param.QqMsgParam;
import pn.torn.goldeneye.napcat.strategy.base.BaseVipMsgStrategy;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.model.torn.stocks.trade.StockTradeAdvice;
import pn.torn.goldeneye.torn.service.user.StockTradeStrategyService;
import pn.torn.goldeneye.utils.DateTimeUtils;
import pn.torn.goldeneye.utils.image.TableImageUtils;
import pn.torn.goldeneye.utils.image.TextImageUtils;

import java.awt.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Stock分析策略实现类
 *
 * @author Bai
 * @version 1.8.0
 * @since 2026.06.01
 */
@Component
@RequiredArgsConstructor
public class VipStocksStrategyImpl extends BaseVipMsgStrategy {
    private final StockTradeStrategyService stockAnalysisService;

    @Override
    public String getCommand() {
        return BotCommands.VIP_STOCK_RECOMMEND;
    }

    @Override
    public String getCommandDescription() {
        return "查看Stock模型分析结果（系统内部研究口径）";
    }

    @Override
    protected List<? extends QqMsgParam<?>> handle(TornUserDO user, String msg) {
        StockTradeStrategyService.StockTradeAnalysis analysis = stockAnalysisService.analyze(LocalDateTime.now(), false);
        return super.buildImageMsg(this.buildGptStockAnalyzeMsg(analysis));
    }

    private String buildGptStockAnalyzeMsg(StockTradeStrategyService.StockTradeAnalysis analysis) {
        List<StockTradeAdvice> analyzeList = analysis.advices();
        if (CollectionUtils.isEmpty(analyzeList) && CollectionUtils.isEmpty(analysis.warnings())) {
            return TextImageUtils.renderTextToBase64("暂时没有操作建议");
        }

        List<List<String>> tableData = new ArrayList<>();
        TableImageUtils.TableConfig tableConfig = new TableImageUtils.TableConfig();
        tableData.add(List.of(DateTimeUtils.convertToString(
                        analyzeList.isEmpty() ? LocalDateTime.now() : analyzeList.getFirst().analysisTime()) + " Stock模型记录",
                "", "", "", "", ""));
        tableConfig.addMerge(0, 0, 1, 6);
        tableConfig.setCellStyle(0, 0, new TableImageUtils.CellStyle()
                .setBgColor(Color.WHITE)
                .setPadding(25)
                .setFont(new Font("微软雅黑", Font.BOLD, 30)));

        // 月度风格停推/缺失告警置于表格顶部(月度规范§13.4: 指令回复顶部显式告警文案)
        int warningRowCount = 0;
        for (String warning : analysis.warnings()) {
            int warningRow = 1 + warningRowCount;
            tableData.add(List.of(warning, "", "", "", "", ""));
            tableConfig.addMerge(warningRow, 0, 1, 6);
            tableConfig.setCellStyle(warningRow, 0, new TableImageUtils.CellStyle()
                    .setFont(new Font("微软雅黑", Font.BOLD, 16))
                    .setAlignment(TableImageUtils.TextAlignment.LEFT));
            warningRowCount++;
        }

        int headerRow = 1 + warningRowCount;
        tableData.add(List.of("Stock", "参考价", "系统动作", "系统评分", "规则说明", "依据"));
        tableConfig.setSubTitle(headerRow, 6);


        for (StockTradeAdvice analyze : analyzeList) {
            tableData.add(List.of(
                    analyze.stocksShortname(),
                    analyze.getBasePriceText(),
                    analyze.getActionName(),
                    String.format("%.0f", analyze.score()),
                    analyze.getStrategyName(),
                    analyze.reasons().stream().map(r -> "· " + r).collect(Collectors.joining("\n"))));
        }

        tableData.add(List.of("以上为系统内部模型记录，不构成投资建议。", "", "", "", "", ""));
        int totalRow = headerRow + 1 + analyzeList.size();
        tableConfig.addMerge(totalRow, 0, 1, 6);
        tableConfig.setCellStyle(totalRow, 0, new TableImageUtils.CellStyle()
                .setFont(new Font("微软雅黑", Font.BOLD, 14))
                .setAlignment(TableImageUtils.TextAlignment.RIGHT));

        return TableImageUtils.renderTableToBase64(tableData, tableConfig);
    }
}