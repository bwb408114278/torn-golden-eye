package pn.torn.goldeneye.torn.service.stocks.backfill;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 股票历史数据来源枚举 - 区分实时 Torn 采集与 Tornsy 历史回填
 * <p>
 * 来源仅用于审计，运行时没有双数据源选择；数据库自然分钟唯一索引保证
 * 同一股票、同一自然分钟只有一条有效历史事实。
 *
 * @author Bai
 * @version 1.2.15
 * @since 2026.08.13
 */
@Getter
@RequiredArgsConstructor
public enum StockHistoryDataSourceEnum {
    /**
     * Torn API 实时采集
     */
    TORN_API("TORN_API", "Torn实时采集"),
    /**
     * Tornsy m1 历史回填
     */
    TORNSY_BACKFILL("TORNSY_BACKFILL", "Tornsy历史回填");

    /**
     * 来源编码
     */
    private final String code;
    /**
     * 中文说明
     */
    private final String description;
}
