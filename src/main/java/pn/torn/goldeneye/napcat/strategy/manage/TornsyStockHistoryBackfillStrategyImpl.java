package pn.torn.goldeneye.napcat.strategy.manage;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.constants.torn.enums.TornFactionRoleTypeEnum;
import pn.torn.goldeneye.napcat.receive.msg.QqRecMsgSender;
import pn.torn.goldeneye.napcat.send.msg.param.QqMsgParam;
import pn.torn.goldeneye.napcat.strategy.base.BaseGroupMsgStrategy;
import pn.torn.goldeneye.torn.service.stocks.backfill.TornsyStockHistoryBackfillScheduler;
import pn.torn.goldeneye.torn.service.stocks.backfill.TornsyStockHistoryBackfillScheduler.BackfillSubmission;
import pn.torn.goldeneye.utils.DateTimeUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Tornsy 股票历史人工范围回填策略实现类
 * <p>
 * 超管指令入口：按 {@code start#end} 传入任意历史范围，投递调度器异步补数。
 * 本类只做参数解析与用户反馈，执行器隔离、30 分钟稳定截止与 JVM 防重入
 * 全部收敛在 {@link TornsyStockHistoryBackfillScheduler}，不在消息线程同步
 * 执行长范围 HTTP 请求、分钟入库和 feature 重算。
 *
 * @author Bai
 * @version 1.2.18
 * @since 2026.08.15
 */
@Component
@RequiredArgsConstructor
public class TornsyStockHistoryBackfillStrategyImpl extends BaseGroupMsgStrategy {
    /**
     * 回填调度器（唯一执行入口）
     */
    private final TornsyStockHistoryBackfillScheduler scheduler;

    @Override
    public String getCommand() {
        return BotCommands.TORNSY_STOCK_HISTORY_SYNC;
    }

    @Override
    public String getCommandDescription() {
        return "按指定时间范围同步Tornsy股票分钟数据";
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
        String[] msgArray = msg.split("#");
        if (msgArray.length != 2) {
            return super.sendErrorFormatMsg();
        }
        LocalDateTime start;
        LocalDateTime end;
        try {
            start = DateTimeUtils.convertToDateTime(msgArray[0].trim());
            end = DateTimeUtils.convertToDateTime(msgArray[1].trim());
        } catch (RuntimeException e) {
            return super.sendErrorFormatMsg();
        }
        if (!start.isBefore(end)) {
            return super.sendErrorFormatMsg();
        }

        BackfillSubmission submission = scheduler.submitManualBackfill(start, end, groupId);
        if (submission == BackfillSubmission.ACCEPTED) {
            return super.buildTextMsg("Tornsy股票数据同步任务已受理，范围：[" + DateTimeUtils.convertToString(start)
                    + ", " + DateTimeUtils.convertToString(end) + ")，请关注日志和数据验收结果。");
        }
        return super.buildTextMsg("Tornsy股票数据同步未受理：" + rejectReason(submission));
    }

    /**
     * 将调度器拒绝结果转换为可区分的用户反馈原因
     *
     * @param submission 调度器返回的非受理提交结果
     * @return 拒绝原因文本
     */
    private String rejectReason(BackfillSubmission submission) {
        return switch (submission) {
            case NOT_PROD -> "当前非生产环境";
            case INVALID_RANGE -> "时间范围无效，起始时间需早于结束时间";
            case TOO_RECENT -> "结束时间过新，需早于当前时间30分钟的稳定截止";
            case ALREADY_PROCESSING -> "已有回填任务在执行中，请稍后再试";
            case EXECUTOR_REJECTED -> "回填执行器已满，请稍后再试";
            default -> "未知原因";
        };
    }
}
