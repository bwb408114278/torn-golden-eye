package pn.torn.goldeneye.constants.torn.enums.stocks.portfolio;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

/**
 * 股票候选接纳结果枚举，区分资格、正式分配和数据门禁失败原因。
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.27
 */
@Getter
@RequiredArgsConstructor
public enum StockCandidateAllocationResultEnum {
    /**
     * 已正式分配槽位并创建正式批次。
     */
    FORMAL_ALLOCATED("FORMAL_ALLOCATED"),
    /**
     * 无可用正式槽位。
     */
    NO_AVAILABLE_SLOT("NO_AVAILABLE_SLOT"),
    /**
     * 正式槽位可用资金不足以买入一股。
     */
    INSUFFICIENT_FUNDS("INSUFFICIENT_FUNDS"),
    /**
     * 锁后复核发现槽位不可再分配。
     */
    SLOT_RECHECK_FAILED("SLOT_RECHECK_FAILED"),
    /**
     * 本轮行情不存在或不可用。
     */
    DATA_NOT_READY("DATA_NOT_READY");

    /**
     * 结果编码。
     */
    private final String code;

    /**
     * 按编码解析结果。
     *
     * @param code 结果编码
     * @return 对应结果
     */
    public static StockCandidateAllocationResultEnum fromCode(String code) {
        return Arrays.stream(values())
                .filter(value -> value.code.equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知候选接纳结果: " + code));
    }
}
