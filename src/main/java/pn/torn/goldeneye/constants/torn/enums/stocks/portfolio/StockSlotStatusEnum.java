package pn.torn.goldeneye.constants.torn.enums.stocks.portfolio;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

/**
 * 股票槽位状态枚举 - 描述组合槽位的占用情况
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.24
 */
@Getter
@RequiredArgsConstructor
public enum StockSlotStatusEnum {
    /**
     * 可用 - 槽位空闲可分配
     */
    AVAILABLE("AVAILABLE", "可用"),
    /**
     * 已预留 - 槽位被预占但尚未成交
     */
    RESERVED("RESERVED", "已预留"),
    /**
     * 已占用 - 槽位已被批次占用
     */
    OCCUPIED("OCCUPIED", "已占用"),
    /**
     * 数据陈旧占用 - 占用但行情数据过期
     */
    STALE("STALE", "数据陈旧占用"),
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
    public static StockSlotStatusEnum fromCode(String code) {
        return Arrays.stream(values())
                .filter(e -> e.code.equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知槽位状态编码: " + code));
    }
}
