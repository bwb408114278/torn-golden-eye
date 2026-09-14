package pn.torn.goldeneye.constants.torn.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

/**
 * 大锅饭收益模式枚举 - 标识帮派大锅饭收益的分配方式
 *
 * <p>code与数据库income_mode列及Liquibase种子(设计5.1/5.3/14.1)的存储值一致，均为大写，
 * {@link #of(String)}按该口径解析，保证写入({@link #getCode()})与读取往返一致。</p>
 *
 * @author Bai
 * @version 1.6.2
 * @since 2026.09.14
 */
@Getter
@RequiredArgsConstructor
public enum TornOcIncomeModeEnum {
    /**
     * 系数模式 - 按岗位系数与有效工时分配收益
     */
    COEFFICIENT("COEFFICIENT", "系数"),
    /**
     * 平分模式 - 工时系数固定为1, 收益均分
     */
    EQUAL("EQUAL", "平分"),
    ;

    /**
     * 存储编码
     */
    private final String code;
    /**
     * 指令回执中文展示
     */
    private final String label;

    /**
     * 根据存储编码解析收益模式
     *
     * @param code 存储编码
     * @return 对应的收益模式；编码为null或不存在时返回null
     */
    public static TornOcIncomeModeEnum of(String code) {
        return Arrays.stream(values())
                .filter(e -> e.code.equals(code))
                .findFirst()
                .orElse(null);
    }

    /**
     * 根据指令参数中文标签解析收益模式
     *
     * @param label 中文标签（系数/平分）
     * @return 对应的收益模式；标签不合法时返回null
     */
    public static TornOcIncomeModeEnum ofLabel(String label) {
        return Arrays.stream(values())
                .filter(e -> e.label.equals(label))
                .findFirst()
                .orElse(null);
    }
}
