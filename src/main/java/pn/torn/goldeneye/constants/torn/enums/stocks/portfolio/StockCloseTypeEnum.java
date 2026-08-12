package pn.torn.goldeneye.constants.torn.enums.stocks.portfolio;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

/**
 * 股票关闭类型枚举 - 与批次关闭状态(CLOSED_*)对应的关闭原因分类
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.24
 */
@Getter
@RequiredArgsConstructor
public enum StockCloseTypeEnum {
    /**
     * 达到目标收益
     */
    CLOSED_TARGET("CLOSED_TARGET", "达到目标收益"),
    /**
     * 区间恢复退出
     */
    CLOSED_RANGE("CLOSED_RANGE", "区间恢复退出"),
    /**
     * 风险退出
     */
    CLOSED_RISK("CLOSED_RISK", "风险退出"),
    /**
     * 达到最长持有时间
     */
    CLOSED_TIME("CLOSED_TIME", "达到最长持有时间"),
    /**
     * 动态收益保护退出
     */
    CLOSED_DYNAMIC("CLOSED_DYNAMIC", "动态收益保护退出"),
    /**
     * 盈利换仓退出
     */
    CLOSED_ROTATION("CLOSED_ROTATION", "盈利换仓退出"),
    /**
     * 系统管理关闭
     */
    ADMIN_CLOSED("ADMIN_CLOSED", "系统管理关闭"),
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
    public static StockCloseTypeEnum fromCode(String code) {
        return Arrays.stream(values())
                .filter(e -> e.code.equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知关闭类型编码: " + code));
    }
}
