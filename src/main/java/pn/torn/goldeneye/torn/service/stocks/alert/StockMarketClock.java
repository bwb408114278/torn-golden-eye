package pn.torn.goldeneye.torn.service.stocks.alert;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 股票市场时间组件 - 统一管理Asia/Shanghai时区的业务时间计算
 * <p>
 * 所有领域规则通过本组件获取业务时间,禁止在领域服务中直接调用
 * {@code LocalDateTime.now()}或{@code LocalDate.now()}决定策略结果。
 * 测试中可注入固定时间。
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.26
 */
@Component
public class StockMarketClock {

    /**
     * 产品时区
     */
    private static final ZoneId ZONE_ID = ZoneId.of("Asia/Shanghai");

    /**
     * 获取当前业务时间
     *
     * @return Asia/Shanghai时区的当前时间
     */
    public LocalDateTime now() {
        return LocalDateTime.now(ZONE_ID);
    }

    /**
     * 获取当前业务日期
     *
     * @return Asia/Shanghai时区的当前日期
     */
    public LocalDate today() {
        return LocalDate.now(ZONE_ID);
    }

    /**
     * 获取摘要日期(发送日前一自然日)
     *
     * @return 前一自然日
     */
    public LocalDate summaryDate() {
        return today().minusDays(1);
    }

    /**
     * 计算当前已结束的15分钟桶时间
     * <p>
     * 将当前时间对齐到桶边界后回退15分钟,得到最近一个已结束桶的开始时间。
     *
     * @return 已结束桶的开始时间
     */
    public LocalDateTime currentEndedBucket() {
        return Stock15mBarBuildService.alignToBucket(now()).minusMinutes(Stock15mBarBuildService.BUCKET_MINUTES);
    }
}
