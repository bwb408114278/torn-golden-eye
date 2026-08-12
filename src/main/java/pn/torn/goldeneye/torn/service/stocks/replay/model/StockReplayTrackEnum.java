package pn.torn.goldeneye.torn.service.stocks.replay.model;

import lombok.Getter;

import java.math.BigDecimal;

/**
 * 隔离回放轨道定义。
 *
 * <p>每条轨道使用同一信号、排序、整数股数、费用和固定退出口径,仅资金口径与记账方式不同。
 * 观察类轨道不持有槽位与现金,只输出研究事实。</p>
 *
 * @author Bai
 * @version 1.2.14
 * @since 2026.08.06
 */
@Getter
public enum StockReplayTrackEnum {

    /**
     * 生产正式组合: 5 槽 × 每槽 20 亿。
     */
    FORMAL_20E("FORMAL_20E", "生产5槽×20亿正式", 5, new BigDecimal("2000000000.00")),

    /**
     * 历史研究对照组合: 5 槽 × 每槽 4 亿。
     */
    FORMAL_4E("FORMAL_4E", "历史5槽×4亿对照", 5, new BigDecimal("400000000.00")),

    /**
     * 无限资金影子: 正式接纳溢出信号恒 1 股,不占槽位。
     */
    UNLIMITED_SHADOW("UNLIMITED_SHADOW", "无限资金影子", 0, null),

    /**
     * 拒绝观察: 对被拒绝候选建立理论入场与退出路径(研究事实)。
     */
    REJECTION_OBSERVATION("REJECTION_OBSERVATION", "拒绝观察", 0, null),

    /**
     * 动态SELL研究数据: 公式未冻结,只采集每开放批次×可用bar研究输入。
     */
    DYNAMIC_SELL_SHADOW("DYNAMIC_SELL_SHADOW", "动态SELL研究数据", 0, null),

    /**
     * 高风险观察: riskLevel=HIGH 候选的 14 天理论路径。
     */
    HIGH_RISK_OBSERVATION("HIGH_RISK_OBSERVATION", "高风险观察", 0, null),

    /**
     * 当前Java原始BUY对照: 全部策略命中的边沿信号及其前向路径。
     */
    RAW_BUY_CONTROL("RAW_BUY_CONTROL", "当前Java原始BUY对照", 0, null);

    /**
     * 轨道编码。
     * -- GETTER --
     * 轨道编码。
     */
    private final String code;
    /**
     * 轨道展示名。
     * -- GETTER --
     * 轨道展示名。
     */
    private final String displayName;
    /**
     * 正式槽位数(观察/影子轨道为0)。
     * -- GETTER --
     * 正式槽位数。
     */
    private final int slotCount;
    /**
     * 每槽初始资金(正式轨道),观察/影子轨道为null。
     * -- GETTER --
     * 每槽初始资金。
     */
    private final BigDecimal initialCashPerSlot;

    StockReplayTrackEnum(String code, String displayName, int slotCount, BigDecimal initialCashPerSlot) {
        this.code = code;
        this.displayName = displayName;
        this.slotCount = slotCount;
        this.initialCashPerSlot = initialCashPerSlot;
    }

    /**
     * 是否为持有槽位与现金的正式轨道。
     *
     * @return 正式轨道返回true
     */
    public boolean isFormal() {
        return initialCashPerSlot != null;
    }
}
