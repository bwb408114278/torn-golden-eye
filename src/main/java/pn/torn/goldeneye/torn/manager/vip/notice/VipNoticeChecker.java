package pn.torn.goldeneye.torn.manager.vip.notice;

import pn.torn.goldeneye.constants.bot.enums.VipNoticeTypeEnum;
import pn.torn.goldeneye.repository.model.vip.VipNoticeConfigDO;
import pn.torn.goldeneye.repository.model.vip.VipNoticeStateDO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 提醒检查器：每种提醒类型实现此接口
 *
 * @author Bai
 * @version 1.1.1
 * @since 2026.02.13
 */
public interface VipNoticeChecker {
    /**
     * 获取提醒类型
     *
     * @return 提醒类型
     */
    List<VipNoticeTypeEnum> getType();

    /**
     * 检查单个用户，返回需要发送的消息列表
     *
     * @param config    用户提醒设置
     * @param stateList 用户提醒状态列表
     * @param checkTime 本轮检查的时间基准
     * @return 需要发送的消息文本列表, 空为不需要添加
     */
    List<String> checkAndUpdate(VipNoticeConfigDO config, List<VipNoticeStateDO> stateList, LocalDateTime checkTime);
}