package pn.torn.goldeneye.configuration.socket.handler;

import org.springframework.util.StringUtils;
import pn.torn.goldeneye.base.exception.BizException;
import pn.torn.goldeneye.napcat.strategy.base.BaseMsgStrategy;

/**
 * 消息处理器基类
 *
 * <p>承载群聊与私聊处理器对策略参数组装的公共逻辑，保证两端对 at 目标的
 * 传递与拒绝语义完全一致。</p>
 *
 * @author Bai
 * @version 1.4.0
 * @since 2026.08.22
 */
public abstract class BaseMessageHandler {

    /**
     * 组装策略参数：无 at 时保持纯文本参数；有 at 时仅用户查询策略接收内部 at 标记，
     * 不支持 at 的策略拒绝执行并返回稳定错误，不静默丢弃 at。
     *
     * @param msgArray 命令数组
     * @param atMarker 内部 at 目标标记；无 at 时为空字符串
     * @param strategy 目标策略
     * @return 策略参数；携带 at 标记时为纯文本参数与标记的拼接
     * @throws BizException 消息携带 at 但策略不支持用户目标查询时抛出
     */
    protected String resolveParam(String[] msgArray, String atMarker, BaseMsgStrategy strategy) {
        String plainParam = msgArray.length > 2 ? msgArray[2] : "";
        if (!StringUtils.hasText(atMarker)) {
            return plainParam;
        }
        if (!strategy.supportsAtUserTarget()) {
            throw new BizException(BaseMsgStrategy.AT_UNSUPPORTED_MSG);
        }
        return plainParam + atMarker;
    }
}
