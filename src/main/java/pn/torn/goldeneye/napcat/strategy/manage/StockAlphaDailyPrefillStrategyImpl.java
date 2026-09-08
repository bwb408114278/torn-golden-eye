package pn.torn.goldeneye.napcat.strategy.manage;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.constants.torn.enums.TornFactionRoleTypeEnum;
import pn.torn.goldeneye.napcat.receive.msg.QqRecMsgSender;
import pn.torn.goldeneye.napcat.send.msg.param.QqMsgParam;
import pn.torn.goldeneye.napcat.strategy.base.BaseGroupMsgStrategy;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.market.StockAlphaDailyCloseService;
import pn.torn.goldeneye.torn.service.stocks.alert.market.StockMarketClock;
import pn.torn.goldeneye.utils.DateTimeUtils;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * 预填股票α日线超管指令策略。
 * <p>
 * 解析 {@code 预填股票α日线#结束日期}，按该日期为闭区间上界构建或修复α日线收盘快照。
 * 本类只做参数解析与回执，具体批量读取与写入由 {@link StockAlphaDailyCloseService} 承担。
 *
 * @author Bai
 * @version 1.6.1
 * @since 2026.09.08
 */
@Component
@RequiredArgsConstructor
public class StockAlphaDailyPrefillStrategyImpl extends BaseGroupMsgStrategy {
    /**
     * 指令参数分隔符。
     */
    private static final String PARAM_SEPARATOR = "#";

    private final StockAlphaDailyCloseService alphaDailyCloseService;
    private final StockMarketClock marketClock;

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
            return super.buildTextMsg("结束日期无效，请使用" + getCommand() + "#2026-09-05");
        }
        int built = alphaDailyCloseService.buildDailyCloses(endDate);
        return super.buildTextMsg("股票α日线快照预填完成，结束日期：" + DateTimeUtils.convertToString(endDate)
                + "，写入条数：" + built);
    }

    /**
     * 解析指令结束日期。
     *
     * @param msg 指令消息
     * @return 结束日期；参数为空时为当前业务日期，解析失败时为null
     */
    private LocalDate resolveEndDate(String msg) {
        if (msg == null || msg.isBlank()) {
            return marketClock.today();
        }
        String param = msg.contains(PARAM_SEPARATOR)
                ? msg.substring(msg.lastIndexOf(PARAM_SEPARATOR) + 1)
                : msg;
        if (param.isBlank()) {
            return marketClock.today();
        }
        try {
            return DateTimeUtils.convertToDate(param.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
