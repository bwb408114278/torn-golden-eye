package pn.torn.goldeneye.constants.torn.enums.stocks.portfolio;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

/**
 * 股票资格结果枚举 - 股票是否允许进入组合的资格判定结果
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.24
 */
@Getter
@RequiredArgsConstructor
public enum StockEligibilityResultEnum {
    /**
     * 允许 - 满足条件可建立组合
     */
    ALLOWED("ALLOWED", "允许"),
    /**
     * 拒绝 - 不满足条件被拒绝
     */
    REJECTED("REJECTED", "拒绝"),
    /**
     * 观察 - 处于观察期暂不建立
     */
    OBSERVED("OBSERVED", "观察"),
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
    public static StockEligibilityResultEnum fromCode(String code) {
        return Arrays.stream(values())
                .filter(e -> e.code.equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知资格结果编码: " + code));
    }
}
