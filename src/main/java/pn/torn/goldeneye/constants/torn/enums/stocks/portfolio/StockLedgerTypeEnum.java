package pn.torn.goldeneye.constants.torn.enums.stocks.portfolio;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

/**
 * 股票账本类型枚举 - 区分正式组合、影子组合与观察记录
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.24
 */
@Getter
@RequiredArgsConstructor
public enum StockLedgerTypeEnum {
    /**
     * 正式组合 - 真实资金交易
     */
    FORMAL("FORMAL", "正式组合"),
    /**
     * 无限资金影子 - 模拟无限资金的影子账本
     */
    UNLIMITED_SHADOW("UNLIMITED_SHADOW", "无限资金影子"),
    /**
     * 拒绝观察 - 被拒绝信号的观察记录
     */
    REJECTED_OBSERVATION("REJECTED_OBSERVATION", "拒绝观察"),
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
    public static StockLedgerTypeEnum fromCode(String code) {
        return Arrays.stream(values())
                .filter(e -> e.code.equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知账本类型编码: " + code));
    }
}
