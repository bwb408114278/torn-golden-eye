package pn.torn.goldeneye.constants.torn.enums.stocks.portfolio;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.Set;

/**
 * 股票批次状态枚举 - 描述组合中单个批次的生命周期状态
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.24
 */
@Getter
@RequiredArgsConstructor
public enum StockBatchStatusEnum {
    /**
     * 待买入 - 信号已触发但尚未成交
     */
    ENTRY_PENDING("ENTRY_PENDING", "待买入"),
    /**
     * 持仓中 - 已买入且未关闭
     */
    OPEN("OPEN", "持仓中"),
    /**
     * 数据陈旧 - 持仓中但行情数据过期
     */
    DATA_STALE("DATA_STALE", "数据陈旧"),
    /**
     * 待卖出 - 卖出信号已触发但尚未成交
     */
    EXIT_PENDING("EXIT_PENDING", "待卖出"),
    /**
     * 卖出数据陈旧 - 卖出流程中行情数据过期
     */
    DATA_STALE_EXIT("DATA_STALE_EXIT", "卖出数据陈旧"),
    /**
     * 达到目标收益关闭
     */
    CLOSED_TARGET("CLOSED_TARGET", "达到目标收益关闭"),
    /**
     * 区间恢复退出关闭
     */
    CLOSED_RANGE("CLOSED_RANGE", "区间恢复退出关闭"),
    /**
     * 风险退出关闭
     */
    CLOSED_RISK("CLOSED_RISK", "风险退出关闭"),
    /**
     * 达到最长持有时间关闭
     */
    CLOSED_TIME("CLOSED_TIME", "达到最长持有时间关闭"),
    /**
     * 动态收益保护退出关闭
     */
    CLOSED_DYNAMIC("CLOSED_DYNAMIC", "动态收益保护退出关闭"),
    /**
     * 盈利换仓退出关闭
     */
    CLOSED_ROTATION("CLOSED_ROTATION", "盈利换仓退出关闭"),
    /**
     * 系统管理关闭
     */
    ADMIN_CLOSED("ADMIN_CLOSED", "系统管理关闭"),
    /**
     * 已取消 - 买入前被取消
     */
    CANCELLED("CANCELLED", "已取消"),
    ;

    /**
     * 活跃状态集合：待买入、持仓中、数据陈旧、待卖出、卖出数据陈旧
     */
    private static final Set<StockBatchStatusEnum> ACTIVE_STATUSES = Set.of(
            ENTRY_PENDING, OPEN, DATA_STALE, EXIT_PENDING, DATA_STALE_EXIT
    );

    /**
     * 英文编码
     */
    private final String code;
    /**
     * 中文展示
     */
    private final String chineseDisplay;

    /**
     * 是否为活跃状态（尚未关闭或取消）
     *
     * @return 活跃状态返回true
     */
    public boolean isActive() {
        return ACTIVE_STATUSES.contains(this);
    }

    /**
     * 根据编码获取枚举值
     *
     * @param code 英文编码
     * @return 对应的枚举值
     * @throws IllegalArgumentException 编码不存在时抛出
     */
    public static StockBatchStatusEnum fromCode(String code) {
        return Arrays.stream(values())
                .filter(e -> e.code.equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知批次状态编码: " + code));
    }
}
