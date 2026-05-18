package pn.torn.goldeneye.torn.manager.vip.notice;

import pn.torn.goldeneye.repository.model.vip.VipNoticeStateDO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * VIP提醒检查基础策略类
 *
 * @author Bai
 * @version 1.1.1
 * @since 2026.02.13
 */
public abstract class BaseVipNoticeChecker implements VipNoticeChecker {
    // 当剩余时间为 0 时，多久重新查询一次 API(分钟)
    protected static final long RECHECK_MINUTES_WHEN_ZERO = 30;

    /**
     * 检查是否需要提醒
     *
     * @return true为需要
     */
    public boolean checkDontNotice(List<VipNoticeStateDO> stateList, LocalDateTime checkTime) {
        for (VipNoticeStateDO state : stateList) {
            boolean result = shouldCallApi(checkTime, state.getLastCheckTime(), state.getLastValue());
            if (result) {
                return false;
            }
        }

        return true;
    }

    /**
     * 判断是否应该调用 API 重新查询
     *
     * @param now           当前检查时间
     * @param lastCheckTime 上次该类型检查的时间
     * @param remainSecond  上次查到的剩余秒数
     * @return true = 应该调 API
     */
    private boolean shouldCallApi(LocalDateTime now, LocalDateTime lastCheckTime, long remainSecond) {
        if (lastCheckTime == null) {
            return true;
        }

        if (remainSecond == 0L) {
            return !lastCheckTime.plusMinutes(RECHECK_MINUTES_WHEN_ZERO).isAfter(now);
        }

        return !lastCheckTime.plusSeconds(remainSecond).isAfter(now);
    }
}