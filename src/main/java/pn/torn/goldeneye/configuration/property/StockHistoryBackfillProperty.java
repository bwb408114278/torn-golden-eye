package pn.torn.goldeneye.configuration.property;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 股票历史回填配置属性
 * <p>
 * 对应环境变量 {@code STOCK_HISTORY_BACKFILL_*}：自动短窗口补正与 13 个月一次性实验
 * 的开关与固定窗口均由外部配置控制，默认全部关闭，部署后需显式开启。
 *
 * @author Bai
 * @version 1.2.15
 * @since 2026.08.13
 */
@Data
@Component
@ConfigurationProperties(prefix = "stock-history-backfill")
public class StockHistoryBackfillProperty {
    /**
     * 自动短窗口补正开关（默认关闭）
     */
    private boolean autoEnabled = false;
    /**
     * 一次性实验开关（默认关闭）
     */
    private boolean experimentEnabled = false;
    /**
     * 实验固定开始时间（yyyy-MM-dd HH:mm:ss，为空时按执行时刻前推 13 个月计算）
     */
    private String experimentStart;
    /**
     * 实验固定结束时间（yyyy-MM-dd HH:mm:ss，必须早于稳定截止，为空时按稳定截止计算）
     */
    private String experimentEnd;
    /**
     * Tornsy 分页大小（默认 1000，部署前以探针结论为准）
     */
    private int pageLimit = 1000;
}
