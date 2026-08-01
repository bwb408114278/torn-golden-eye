package pn.torn.goldeneye.constants.torn.enums.stocks.portfolio;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

/**
 * 股票正式决定原因编码枚举 - BatchMark.formal_reason 的稳定原因编码
 * <p>
 * 对应冻结策略文档《VIP股票虚拟组合完整业务设计》第14节 SELL/HOLD 原因码。
 * 正式决定原因编码用于稳定查询、对账与研究维度，不随日志或消息文案变化。
 * 完整冻结清单以策略文档为准，本枚举只收录当前退出规则引擎实际产出且已冻结的编码。
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.08.01
 */
@Getter
@RequiredArgsConstructor
public enum StockFormalReasonEnum {
    /**
     * 达到目标收益退出
     */
    SELL_TARGET_REACHED("SELL_TARGET_REACHED", "达到目标收益退出"),
    /**
     * 区间恢复退出
     */
    SELL_RANGE_RECOVERED("SELL_RANGE_RECOVERED", "区间恢复退出"),
    /**
     * 达到最长持有时间退出
     */
    SELL_MAX_HOLD("SELL_MAX_HOLD", "达到最长持有时间退出"),
    /**
     * 硬性风险止损退出
     */
    SELL_HARD_RISK("SELL_HARD_RISK", "硬性风险止损退出"),
    /**
     * 数据/管理关闭 - DATA_STALE_EXIT恢复后的独立灾难处置,非普通策略卖出
     */
    SELL_DATA_ADMIN_CLOSE("SELL_DATA_ADMIN_CLOSE", "数据异常/管理关闭"),
    /**
     * 未触发任何退出规则,保持持有
     */
    HOLD_NO_EXIT_TRIGGERED("HOLD_NO_EXIT_TRIGGERED", "未触发任何退出规则,保持持有"),
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
    public static StockFormalReasonEnum fromCode(String code) {
        return Arrays.stream(values())
                .filter(e -> e.code.equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知正式决定原因编码: " + code));
    }
}
