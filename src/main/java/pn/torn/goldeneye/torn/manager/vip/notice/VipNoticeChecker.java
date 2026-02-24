package pn.torn.goldeneye.torn.manager.vip.notice;

import pn.torn.goldeneye.repository.model.vip.VipNoticeDO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 提醒检查器：每种提醒类型实现此接口
 *
 * @author Bai
 * @version 0.5.0
 * @since 2026.02.13
 */
public interface VipNoticeChecker {
    /**
     * 检查单个用户，返回需要发送的消息列表
     *
     * @param notice    用户提醒数据
     * @param checkTime 本轮检查的时间基准
     * @return 需要发送的消息文本列表, 空为不需要添加
     */
    List<String> checkAndUpdate(VipNoticeDO notice, LocalDateTime checkTime);
}