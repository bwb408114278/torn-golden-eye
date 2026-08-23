package pn.torn.goldeneye.configuration.property;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 股票历史回填配置属性
 * <p>
 * 仅承载 Tornsy HTTP 技术参数（分页大小）。人工回填范围由超管机器人指令传入；
 * 日常巡检范围由代码固定为昨天自然日。本类不承担回填启停或范围运营控制，
 * 不存在任何回填开关或时间范围配置。
 *
 * @author Bai
 * @version 1.4.2
 * @since 2026.08.13
 */
@Data
@Component
@ConfigurationProperties(prefix = "stock-history-backfill")
public class StockHistoryBackfillProperty {
    /**
     * Tornsy 分页大小（默认 1000，部署前以探针结论为准）。
     * <p>
     * 该值同时限制非饱和时间片长度：每个请求窗口最多 {@code min(900, pageLimit - 1)} 分钟，
     * 从而保证正常 m1 数据不可能达到 pageLimit 条；pageLimit 必须大于 1，否则回填在任何
     * HTTP 请求或写入前 fail-fast。
     */
    private int pageLimit = 1000;
}
