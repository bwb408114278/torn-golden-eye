package pn.torn.goldeneye.napcat.strategy.manage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.base.bot.Bot;
import pn.torn.goldeneye.base.bot.BotHttpReqParam;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.constants.torn.enums.TornFactionRoleTypeEnum;
import pn.torn.goldeneye.napcat.receive.msg.QqRecMsgSender;
import pn.torn.goldeneye.napcat.send.msg.GroupMsgHttpBuilder;
import pn.torn.goldeneye.napcat.send.msg.param.QqMsgParam;
import pn.torn.goldeneye.napcat.send.msg.param.TextQqMsg;
import pn.torn.goldeneye.napcat.strategy.base.BaseGroupMsgStrategy;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.market.StockAlphaDailyCloseService;
import pn.torn.goldeneye.torn.service.stocks.alert.market.StockMarketClock;
import pn.torn.goldeneye.torn.service.stocks.rebuild.StockHistoricalMaintenanceGate;
import pn.torn.goldeneye.utils.DateTimeUtils;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;

/**
 * 预填股票α日线超管指令策略。
 * <p>
 * 解析 {@code 预填股票α日线#结束日期}，按该日期为闭区间上界构建或修复α日线收盘快照。
 * 本类只做参数解析、任务投递与受理回执：批量读取与写入统一投递历史数据维护执行器
 * {@code stockBackfillExecutor} 异步执行，共用 {@link StockHistoricalMaintenanceGate} 互斥，
 * 不占用Bot消息线程，也不把批量历史扫描伪装成瞬时完成；执行结果通过独立回执发送到指令发起群。
 *
 * @author Bai
 * @version 1.6.1
 * @since 2026.09.08
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockAlphaDailyPrefillStrategyImpl extends BaseGroupMsgStrategy {
    /**
     * 指令参数分隔符。
     */
    private static final String PARAM_SEPARATOR = "#";

    private final StockAlphaDailyCloseService alphaDailyCloseService;
    private final StockMarketClock marketClock;
    private final ThreadPoolTaskExecutor stockBackfillExecutor;
    private final StockHistoricalMaintenanceGate maintenanceGate;
    private final Bot bot;

    @Override
    public String getCommand() {
        return BotCommands.ALPHA_STOCK_DAILY_PREFILL;
    }

    @Override
    public String getCommandDescription() {
        return "预填或修复股票α日线快照，可带结束日期，例如" + getCommand() + "#2026-09-05";
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
        LocalDate endDate = resolveEndDate(msg);
        if (endDate == null) {
            return super.buildTextMsg("结束日期无效，必须为已结束自然日，例如" + getCommand() + "#2026-09-05");
        }
        if (!maintenanceGate.tryAcquire()) {
            log.warn("股票α日线预填未受理, 原因=已有历史数据维护任务在执行中, endDate={}, groupId={}", endDate, groupId);
            return super.buildTextMsg("已有历史数据维护任务在执行中，股票α日线预填本次未受理，请稍后重试");
        }
        try {
            stockBackfillExecutor.execute(() -> runPrefill(endDate, groupId));
        } catch (RejectedExecutionException e) {
            maintenanceGate.release();
            log.warn("股票α日线预填未受理, 原因=历史数据维护执行器已满, endDate={}, groupId={}", endDate, groupId);
            return super.buildTextMsg("历史数据维护执行器已满，股票α日线预填本次未受理，请稍后重试");
        }
        log.info("股票α日线预填任务已受理, endDate={}, groupId={}", endDate, groupId);
        return super.buildTextMsg("股票α日线快照预填已受理并在后台执行，结束日期："
                + DateTimeUtils.convertToString(endDate) + "，完成后回执本群");
    }

    /**
     * 在历史数据维护执行器内执行预填、发送执行回执并释放共享互斥门。
     * <p>
     * 预填只写入已结束自然日中缺失或不完整的快照，重复执行收敛为更新；异常只记录并回执，不外抛。
     * 回执区分"写入条数"与"无已结束有效日期可构建"，避免把"无需构建"误报为执行成功写入。
     *
     * @param endDate 预填结束日期（闭区间上界，已结束自然日）
     * @param groupId 指令发起的群号
     */
    private void runPrefill(LocalDate endDate, long groupId) {
        long begin = System.currentTimeMillis();
        try {
            int built = alphaDailyCloseService.buildDailyCloses(endDate);
            String result = built > 0
                    ? "写入条数：" + built
                    : "无已结束有效日期可构建（窗口内日期已完整或无行情）";
            sendReceipt(groupId, "【股票α日线预填完成】\n结束日期：" + DateTimeUtils.convertToString(endDate)
                    + "\n" + result + "\n耗时：" + (System.currentTimeMillis() - begin) + "ms");
        } catch (RuntimeException e) {
            log.error("股票α日线预填异常, endDate={}, groupId={}: {}", endDate, groupId, e.getMessage(), e);
            sendReceipt(groupId, "【股票α日线预填失败】\n结束日期：" + DateTimeUtils.convertToString(endDate)
                    + "\n错误摘要：" + (e.getMessage() == null ? "未知异常" : e.getMessage())
                    + "\n可使用相同结束日期重新提交；已写入部分保持幂等。");
        } finally {
            maintenanceGate.release();
        }
    }

    /**
     * 向指令发起群发送执行回执。
     * <p>
     * 群号无效时只记录日志；发送失败只记录ERROR，不影响已完成的预填结果。
     *
     * @param groupId 指令发起群号
     * @param text    回执文本
     */
    private void sendReceipt(long groupId, String text) {
        if (groupId <= 0L) {
            log.info("股票α日线预填-无有效回执群号, 仅记录日志: {}", text.replace('\n', ' '));
            return;
        }
        try {
            BotHttpReqParam param = new GroupMsgHttpBuilder()
                    .setGroupId(groupId)
                    .addMsg(new TextQqMsg(text))
                    .build();
            bot.sendRequest(param, String.class);
        } catch (Exception e) {
            log.error("股票α日线预填-执行回执发送异常, groupId={}: {}", groupId, e.getMessage(), e);
        }
    }

    /**
     * 解析指令结束日期。
     * <p>
     * 参数为空时使用最近已结束自然日;显式传入当前自然日或未来日期一律拒绝,
     * 避免把未结束自然日的部分bar冻结为日终收盘。
     *
     * @param msg 指令消息
     * @return 已结束自然日；参数为空时为最近已结束自然日，解析失败或未结束时为null
     */
    private LocalDate resolveEndDate(String msg) {
        LocalDate lastEndedDay = marketClock.lastEndedNaturalDay();
        if (msg == null || msg.isBlank() || !msg.contains(PARAM_SEPARATOR)) {
            return lastEndedDay;
        }
        String param = msg.substring(msg.lastIndexOf(PARAM_SEPARATOR) + 1);
        if (param.isBlank()) {
            return lastEndedDay;
        }
        LocalDate parsed;
        try {
            parsed = DateTimeUtils.convertToDate(param.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
        return parsed.isAfter(lastEndedDay) ? null : parsed;
    }
}
