package pn.torn.goldeneye.constants.torn.enums.stocks.portfolio;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

/**
 * 拒绝观察理论结果枚举。
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.30
 */
@Getter
@RequiredArgsConstructor
public enum StockObservationResultEnum {
    /**
     * 无法建立理论入场。
     */
    NO_THEORETICAL_ENTRY("NO_THEORETICAL_ENTRY", "无法理论入场"),
    /**
     * 观察截止前没有可用后续行情。
     */
    OBSERVATION_DATA_INSUFFICIENT("OBSERVATION_DATA_INSUFFICIENT", "观察数据不足"),
    /**
     * 理论观察路径已完成。
     */
    OBSERVATION_COMPLETED("OBSERVATION_COMPLETED", "观察完成");

    private final String code;
    private final String chineseDisplay;

    /**
     * 按编码解析结果枚举。
     *
     * @param code 结果编码
     * @return 结果枚举
     */
    public static StockObservationResultEnum fromCode(String code) {
        return Arrays.stream(values())
                .filter(item -> item.code.equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知拒绝观察结果编码: " + code));
    }
}
