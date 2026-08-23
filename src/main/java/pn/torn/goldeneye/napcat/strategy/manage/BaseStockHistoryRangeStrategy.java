package pn.torn.goldeneye.napcat.strategy.manage;

import pn.torn.goldeneye.constants.torn.enums.TornFactionRoleTypeEnum;
import pn.torn.goldeneye.napcat.receive.msg.QqRecMsgSender;
import pn.torn.goldeneye.napcat.send.msg.param.QqMsgParam;
import pn.torn.goldeneye.napcat.strategy.base.BaseGroupMsgStrategy;
import pn.torn.goldeneye.utils.DateTimeUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 历史数据范围指令策略基类。
 * <p>
 * 统一处理超管 {@code start#end} 范围指令的参数解析、格式错误反馈、已受理/未受理回复；
 * 子类只需提供提交入口、受理判断与对应的文案，未来新增历史数据回填类指令可继续继承本类。
 *
 * @author Bai
 * @version 1.4.2
 * @since 2026.08.23
 */
public abstract class BaseStockHistoryRangeStrategy<T> extends BaseGroupMsgStrategy {

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

        T submission = submit(groupId, start, end);
        if (isAccepted(submission)) {
            return super.buildTextMsg(buildAcceptedMessage(start, end));
        }
        return super.buildTextMsg(buildRejectedMessage(submission));
    }

    /**
     * 提交历史数据任务。
     *
     * @param groupId 发起指令的群号
     * @param start   起始时间（含）
     * @param end     结束时间（不含）
     * @return 调度器提交结果
     */
    protected abstract T submit(long groupId, LocalDateTime start, LocalDateTime end);

    /**
     * 判断提交结果是否为已受理。
     *
     * @param submission 提交结果
     * @return true 表示已受理
     */
    protected abstract boolean isAccepted(T submission);

    /**
     * 构建已受理回复。
     *
     * @param start 起始时间（含）
     * @param end   结束时间（不含）
     * @return 已受理文本
     */
    protected abstract String buildAcceptedMessage(LocalDateTime start, LocalDateTime end);

    /**
     * 构建未受理回复。
     *
     * @param submission 提交结果
     * @return 未受理文本
     */
    protected abstract String buildRejectedMessage(T submission);
}
