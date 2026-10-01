package pn.torn.goldeneye.constants.torn.enums.stocks.portfolio;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;

/**
 * 股票规则模式枚举 - 策略规则的运行模式分级
 *
 * @author Bai
 * @version 1.6.5
 * @since 2026.07.24
 */
@Slf4j
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

    /**
     * 解析规则模式编码,缺失或非法时安全降级为{@link #SHADOW}。
     * <p>
     * 本方法是规则模式解析(含降级与告警)的唯一宿主,门禁与轮次事务不得各自复制一套 if/try-catch。
     *
     * @param code 规则模式英文编码;为null或空白视为缺失
     * @return 解析出的规则模式;缺失或非法时返回{@link #SHADOW}
     */
    public static StockRuleModeEnum resolve(String code) {
        if (code == null || code.isBlank()) {
            return SHADOW;
        }
        try {
            return fromCode(code);
        } catch (IllegalArgumentException e) {
            log.warn("规则模式编码无效,默认SHADOW: code={}", code);
            return SHADOW;
        }
    }
}
