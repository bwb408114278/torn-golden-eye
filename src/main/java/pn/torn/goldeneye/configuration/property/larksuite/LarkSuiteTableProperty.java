package pn.torn.goldeneye.configuration.property.larksuite;

import lombok.Data;

/**
 * 飞书云文档属性
 *
 * @author Bai
 * @version 1.2.0
 * @since 2026.06.03
 */
@Data
public class LarkSuiteTableProperty {
    /**
     * 业务名称
     */
    private String name;
    /**
     * APP Token
     */
    private String appToken;
}