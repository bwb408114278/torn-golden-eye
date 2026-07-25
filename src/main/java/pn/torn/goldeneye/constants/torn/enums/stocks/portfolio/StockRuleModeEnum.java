package pn.torn.goldeneye.constants.torn.enums.stocks.portfolio;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

/**
 * 股票规则模式枚举 - 策略规则的运行模式分级
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.24
 */
@Getter
@RequiredArgsConstructor
public enum StockRuleModeEnum {
    /**
     * 关闭 - 规则不生效
     */
    OFF("OFF", "关闭"),
    /**
     * 影子模式 - 仅记录不实际交易
     */
    SHADOW("SHADOW", "影子模式"),
    /**
     * 试运行模式 - 小规模试运行
     */
    PROVISIONAL("PROVISIONAL", "试运行模式"),
    /**
     * 正式模式 - 全量正式运行
     */
    FORMAL("FORMAL", "正式模式"),
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
    public static StockRuleModeEnum fromCode(String code) {
        return Arrays.stream(values())
                .filter(e -> e.code.equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知规则模式编码: " + code));
    }
}
