package pn.torn.goldeneye.napcat.strategy.manage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.base.bot.Bot;
import pn.torn.goldeneye.base.bot.BotHttpReqParam;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.constants.torn.enums.TornFactionRoleTypeEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.StockPersonalityEnum;
import pn.torn.goldeneye.napcat.receive.msg.QqRecMsgSender;
import pn.torn.goldeneye.napcat.send.msg.GroupMsgHttpBuilder;
import pn.torn.goldeneye.napcat.send.msg.param.QqMsgParam;
import pn.torn.goldeneye.napcat.send.msg.param.TextQqMsg;
import pn.torn.goldeneye.napcat.strategy.base.BaseGroupMsgStrategy;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockMonthlyStateDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMonthlyStateDO;
import pn.torn.goldeneye.torn.manager.setting.SysSettingManager;
import pn.torn.goldeneye.torn.service.stocks.alert.market.StockMarketClock;
import pn.torn.goldeneye.torn.service.stocks.rebuild.StockHistoricalMaintenanceGate;
import pn.torn.goldeneye.torn.service.stocks.rebuild.StockMonthlyStateRangeRebuildService;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;

/**
 * 回补Stock月度风格超管指令策略(一次性生命周期)。
 * <p>
 * 解析 {@code 回补Stock月度风格#yyyy-MM#yyyy-MM}(起始月#结束月),按月正序委托
 * {@link StockMonthlyStateRangeRebuildService} 执行「初始化DRAFT→重算DRAFT→SYSTEM自动确认」,
 * 单月失败不中断、可按相同范围幂等重跑;完成后与旧 {@code sys_setting.STOCK_PERSONALITY}
 * 逐支对账(只落日志与回执文本,不建对账表)。
 * <p>
 * 前置约束:月度证据依赖15分钟bar,bar/feature存在缺口时请先用「重建Stock派生数据」补齐,
 * 缺口月份会保持DRAFT不生效,补齐后按相同范围重跑本指令即可。
 * <p>
 * <b>一次性生命周期:</b>回补完成并对账通过后,本指令、本类与对账逻辑必须在后续版本删除,
 * 不得作为常驻能力保留(设计文档§4.7,2026-10-04确认)。
 *
 * @author Bai
 * @version 1.8.0
 * @since 2026.10.04
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockMonthlyStyleBackfillStrategyImpl extends BaseGroupMsgStrategy {
    /**
     * 指令参数分隔符
     */
    private static final String PARAM_SEPARATOR = "#";
    /**
     * 旧风格配置键(仅本一次性对账使用,随本指令一并删除;数据行本身保留为只读历史)
     */
    private static final String LEGACY_STOCK_PERSONALITY_KEY = "STOCK_PERSONALITY";

    private final StockMonthlyStateRangeRebuildService rangeRebuildService;
    private final TornStockMonthlyStateDAO monthlyStateDao;
    private final SysSettingManager settingManager;
    private final StockMarketClock marketClock;
    private final ThreadPoolTaskExecutor stockBackfillExecutor;
    private final StockHistoricalMaintenanceGate maintenanceGate;
    private final Bot bot;

    @Override
    public String getCommand() {
        return BotCommands.MONTHLY_STYLE_BACKFILL;
    }

    @Override
    public String getCommandDescription() {
        return "按月正序回补Stock月度风格状态,例如" + getCommand() + "#2026-01#2026-10";
    }

    @Override
    public boolean isNeedSa() {
        return true;
    }

    @Override
    public TornFactionRoleTypeEnum getRoleType() {
        return null;
    }

    @Override
    public List<? extends QqMsgParam<?>> handle(long groupId, QqRecMsgSender sender, String msg) {
        YearMonth[] months = resolveMonths(msg);
        if (months.length != 2) {
            return super.buildTextMsg("月份参数无效,格式必须为" + getCommand() + "#yyyy-MM#yyyy-MM,"
                    + "且结束月不得晚于当前月,例如" + getCommand() + "#2026-01#2026-10");
        }
        YearMonth startMonth = months[0];
        YearMonth endMonth = months[1];
        if (!maintenanceGate.tryAcquire()) {
            log.warn("Stock月度风格回补未受理, 原因=已有历史数据维护任务在执行中, start={}, end={}, groupId={}",
                    startMonth, endMonth, groupId);
            return super.buildTextMsg("已有历史数据维护任务在执行中,Stock月度风格回补本次未受理,请稍后重试");
        }
        try {
            stockBackfillExecutor.execute(() -> runBackfill(startMonth, endMonth, groupId));
        } catch (RejectedExecutionException e) {
            maintenanceGate.release();
            log.warn("Stock月度风格回补未受理, 原因=历史数据维护执行器已满, start={}, end={}, groupId={}",
                    startMonth, endMonth, groupId);
            return super.buildTextMsg("历史数据维护执行器已满,Stock月度风格回补本次未受理,请稍后重试");
        }
        log.info("Stock月度风格回补任务已受理, start={}, end={}, groupId={}", startMonth, endMonth, groupId);
        return super.buildTextMsg("Stock月度风格回补已受理并在后台执行,范围:" + startMonth + "~" + endMonth
                + "。提示:bar/feature存在缺口时请先执行「重建Stock派生数据」,缺口月份将保持DRAFT,"
                + "补齐后按相同范围重跑即可;完成后回执本群。");
    }

    /**
     * 在历史数据维护执行器内执行按月回补、对账、发送回执并释放共享互斥门。
     * <p>
     * 单月失败由 {@link StockMonthlyStateRangeRebuildService} 内部记ERROR并继续;
     * 整体异常只记录并回执,不外抛;无论结果如何 finally 释放互斥门。
     *
     * @param startMonth 起始月(含)
     * @param endMonth   结束月(含)
     * @param groupId    指令发起的群号
     */
    private void runBackfill(YearMonth startMonth, YearMonth endMonth, long groupId) {
        long begin = System.currentTimeMillis();
        try {
            int totalChange = rangeRebuildService.rebuild(
                    startMonth.atDay(1).atStartOfDay(),
                    endMonth.plusMonths(1).atDay(1).atStartOfDay());
            String reconciliation = reconcileWithLegacySetting();
            log.info("Stock月度风格回补任务完成, start={}, end={}, totalChange={}, 耗时={}ms",
                    startMonth, endMonth, totalChange, System.currentTimeMillis() - begin);
            sendReceipt(groupId, "【Stock月度风格回补完成】\n范围:" + startMonth + "~" + endMonth
                    + "\n新建/重算/确认变更总数:" + totalChange
                    + "\n耗时:" + (System.currentTimeMillis() - begin) + "ms\n\n" + reconciliation);
        } catch (RuntimeException e) {
            log.error("Stock月度风格回补异常, start={}, end={}, groupId={}: {}",
                    startMonth, endMonth, groupId, e.getMessage(), e);
            sendReceipt(groupId, "【Stock月度风格回补失败】\n范围:" + startMonth + "~" + endMonth
                    + "\n错误摘要:" + (e.getMessage() == null ? "未知异常" : e.getMessage())
                    + "\n可使用相同范围重新提交;已完成月份保持幂等。");
        } finally {
            maintenanceGate.release();
        }
    }

    /**
     * 与旧 {@code sys_setting.STOCK_PERSONALITY} 逐支对账(一次性,只落日志与回执文本)。
     * <p>
     * 对账对象为每股最近一条CONFIRMED月度状态的 {@code strategyFitPrior};
     * 对账源为旧配置解析出的 简称→StockPersonalityEnum;差异逐支输出供人工判定,
     * 不建对账表、不写任何业务状态。旧配置行缺失时输出跳过说明。
     *
     * @return 对账文本(逐支一行)
     */
    private String reconcileWithLegacySetting() {
        String raw = settingManager.getSettingValue(LEGACY_STOCK_PERSONALITY_KEY);
        Map<String, StockPersonalityEnum> legacyStyles = parseLegacyStyles(raw);

        LocalDate targetMonth = marketClock.today().withDayOfMonth(1);
        List<TornStockMonthlyStateDO> latestConfirmed = monthlyStateDao.selectLatestConfirmedUpToMonth(targetMonth);
        if (latestConfirmed.isEmpty()) {
            return "对账:无任何已确认月度状态,请检查bar证据与回补范围";
        }
        if (legacyStyles.isEmpty()) {
            return "对账:旧配置STOCK_PERSONALITY已缺失,跳过对账;已确认月度状态共" + latestConfirmed.size() + "支";
        }

        StringBuilder text = new StringBuilder("对账(旧sys_setting口径 → 月度状态口径):");
        int consistent = 0;
        int different = 0;
        int missingLegacy = 0;
        for (TornStockMonthlyStateDO state : latestConfirmed) {
            StockPersonalityEnum legacy = legacyStyles.get(
                    state.getStocksShortname() == null ? "" : state.getStocksShortname().toUpperCase());
            String newName = state.getStrategyFitPrior();
            if (legacy == null) {
                missingLegacy++;
                text.append("\n").append(state.getStocksShortname()).append(": 旧=无配置 新=").append(newName);
            } else if (legacy.name().equals(newName)) {
                consistent++;
                text.append("\n").append(state.getStocksShortname()).append(": ").append(newName).append("(一致)");
            } else {
                different++;
                text.append("\n").append(state.getStocksShortname())
                        .append(": 旧=").append(legacy.name()).append(" 新=").append(newName);
            }
        }
        log.info("Stock月度风格回补-对账完成, consistent={}, different={}, missingLegacy={}",
                consistent, different, missingLegacy);
        return text + "\n小结:一致" + consistent + "支,不一致" + different + "支,旧配置缺失" + missingLegacy
                + "支;差异逐支人工判定。";
    }

    /**
     * 解析旧风格配置(与退役前SysSettingManager.getStockPersonalities同口径:SYM1:TYPE1,SYM2:TYPE2)。
     *
     * @param raw 原始配置值
     * @return 简称(大写)→个性枚举;配置缺失或全无效时返回空Map
     */
    private Map<String, StockPersonalityEnum> parseLegacyStyles(String raw) {
        Map<String, StockPersonalityEnum> result = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return result;
        }
        for (String entry : raw.split(",")) {
            String[] parts = entry.trim().split(":");
            if (parts.length == 2) {
                try {
                    result.put(parts[0].trim().toUpperCase(),
                            StockPersonalityEnum.valueOf(parts[1].trim().toUpperCase()));
                } catch (IllegalArgumentException ignored) {
                    // 忽略无效的配置项(与退役前口径一致)
                }
            }
        }
        return result;
    }

    /**
     * 解析指令月份参数。
     *
     * @param msg 指令消息
     * @return [起始月, 结束月];格式非法、起始晚于结束或结束月晚于当前月时返回空数组
     */
    private YearMonth[] resolveMonths(String msg) {
        if (msg == null) {
            return new YearMonth[0];
        }
        String[] parts = msg.split(PARAM_SEPARATOR);
        if (parts.length != 3) {
            return new YearMonth[0];
        }
        YearMonth start;
        YearMonth end;
        try {
            start = YearMonth.parse(parts[1].trim());
            end = YearMonth.parse(parts[2].trim());
        } catch (DateTimeParseException e) {
            return new YearMonth[0];
        }
        if (start.isAfter(end) || end.isAfter(YearMonth.from(marketClock.today()))) {
            return new YearMonth[0];
        }
        return new YearMonth[]{start, end};
    }

    /**
     * 向指令发起群发送执行回执;群号无效时只记录日志,发送失败只记ERROR。
     *
     * @param groupId 指令发起群号
     * @param text    回执文本
     */
    private void sendReceipt(long groupId, String text) {
        if (groupId <= 0L) {
            log.info("Stock月度风格回补-无有效回执群号, 仅记录日志: {}", text.replace('\n', ' '));
            return;
        }
        try {
            BotHttpReqParam param = new GroupMsgHttpBuilder()
                    .setGroupId(groupId)
                    .addMsg(new TextQqMsg(text))
                    .build();
            bot.sendRequest(param, String.class);
        } catch (Exception e) {
            log.error("Stock月度风格回补-执行回执发送异常, groupId={}: {}", groupId, e.getMessage(), e);
        }
    }
}
