package pn.torn.goldeneye.napcat.strategy.manage;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.constants.bot.BotCommands;
import pn.torn.goldeneye.torn.service.stocks.alert.Stock15mBarBuildService;
import pn.torn.goldeneye.torn.service.stocks.rebuild.StockDerivedDataRebuildScheduler;
import pn.torn.goldeneye.torn.service.stocks.rebuild.StockDerivedDataRebuildScheduler.DerivedRebuildSubmission;
import pn.torn.goldeneye.utils.DateTimeUtils;

import java.time.LocalDateTime;

/**
 * 重建 VIP 股票派生数据超管指令策略。
 * <p>
 * 解析 {@code start#end}，对齐到 15 分钟桶后提交 {@link StockDerivedDataRebuildScheduler}。
 * 本类只做参数解析与受理/拒绝反馈，不在消息线程执行分钟查询、bar/feature 计算或批量写入。
 *
 * @author Bai
 * @version 1.4.2
 * @since 2026.08.23
 */
@Component
@RequiredArgsConstructor
public class StockDerivedDataRebuildStrategyImpl extends BaseStockHistoryRangeStrategy<DerivedRebuildSubmission> {

    private final StockDerivedDataRebuildScheduler scheduler;

    @Override
    public String getCommand() {
        return BotCommands.DERIVED_STOCK_DATA_REBUILD;
    }

    @Override
    public String getCommandDescription() {
        return "按指定时间范围重建VIP股票派生数据";
    }

    @Override
    protected DerivedRebuildSubmission submit(long groupId, LocalDateTime start, LocalDateTime end) {
        return scheduler.submit(start, end, groupId);
    }

    @Override
    protected boolean isAccepted(DerivedRebuildSubmission submission) {
        return submission == DerivedRebuildSubmission.ACCEPTED;
    }

    @Override
    protected String buildAcceptedMessage(LocalDateTime start, LocalDateTime end) {
        LocalDateTime alignedStart = Stock15mBarBuildService.alignToBucket(start);
        LocalDateTime alignedEnd = Stock15mBarBuildService.alignToBucket(end);
        return "VIP股票派生数据重建任务已受理，范围：[" + DateTimeUtils.convertToString(alignedStart)
                + ", " + DateTimeUtils.convertToString(alignedEnd) + ")，请关注日志和最终回执。";
    }

    @Override
    protected String buildRejectedMessage(DerivedRebuildSubmission submission) {
        return "VIP股票派生数据重建未受理：" + switch (submission) {
            case NOT_PROD -> "当前非生产环境";
            case INVALID_RANGE -> "时间范围无效，起始时间需早于结束时间";
            case TOO_RECENT -> "结束时间过新，需早于当前时间30分钟的稳定截止";
            case ALREADY_PROCESSING -> "已有历史数据维护任务在执行中，请稍后再试";
            case EXECUTOR_REJECTED -> "历史数据维护执行器已满，请稍后再试";
            default -> "未知原因";
        };
    }
}
