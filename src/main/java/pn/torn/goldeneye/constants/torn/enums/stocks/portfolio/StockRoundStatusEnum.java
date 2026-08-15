package pn.torn.goldeneye.constants.torn.enums.stocks.portfolio;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

/**
 * 股票轮次状态枚举 - 描述数据轮次（Bar/特征构建）的处理状态
 *
 * @author Bai
 * @version 1.2.18
 * @since 2026.07.24
 */
@Getter
@RequiredArgsConstructor
public enum StockRoundStatusEnum {
    /**
     * 等待中 - 轮次已创建等待触发
     */
    PENDING("PENDING", "等待中"),
    /**
     * 构建Bar中 - 正在聚合K线
     */
    BUILDING_BAR("BUILDING_BAR", "构建Bar中"),
    /**
     * 构建特征中 - 正在计算特征
     */
    BUILDING_FEATURE("BUILDING_FEATURE", "构建特征中"),
    /**
     * 就绪 - 数据已就绪可处理
     */
    READY("READY", "就绪"),
    /**
     * 处理中 - 策略正在处理
     */
    PROCESSING("PROCESSING", "处理中"),
    /**
     * 已完成 - 轮次处理完毕
     */
    COMPLETED("COMPLETED", "已完成"),
    /**
     * 等待数据 - 等待行情数据到位
     */
    WAITING_DATA("WAITING_DATA", "等待数据"),
    /**
     * 可重试失败 - 发生可重试错误
     */
    FAILED_RETRYABLE("FAILED_RETRYABLE", "可重试失败"),
    /**
     * 最终失败 - 重试耗尽后的最终失败
     */
    FAILED_FINAL("FAILED_FINAL", "最终失败"),
    /**
     * 仅数据修复 - Tornsy历史回填实际插入分钟导致的派生数据(bar/feature)已按当前版本重算;
     * 仅表达"数据已修复且已隔离策略副作用"的审计事实,不属于生产策略待处理队列,
     * 永不进入策略事务,生产轮次消费白名单查询绝不返回该状态。
     */
    REPAIRED_DATA_ONLY("REPAIRED_DATA_ONLY", "仅数据修复"),
    ;

    /**
     * 英文编码
     */
    private final String code;
    /**
     * 中文展示
     */
    private final String chineseDisplay;

    /**
     * 根据编码获取枚举值
     *
     * @param code 英文编码
     * @return 对应的枚举值
     * @throws IllegalArgumentException 编码不存在时抛出
     */
    public static StockRoundStatusEnum fromCode(String code) {
        return Arrays.stream(values())
                .filter(e -> e.code.equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知轮次状态编码: " + code));
    }
}
