package pn.torn.goldeneye.configuration.property.larksuite;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.base.exception.BizException;

import java.util.List;

/**
 * 飞书属性
 *
 * @author Bai
 * @version 1.2.0
 * @since 2025.09.04
 */
@Data
@Component
@ConfigurationProperties(prefix = "lark-suite")
public class LarkSuiteProperty {
    /**
     * App Id
     */
    private String appId;
    /**
     * App Secret
     */
    private String appSecret;
    /**
     * 自建应用App Id
     */
    private String selfAppId;
    /**
     * 自建应用App Secret
     */
    private String selfAppSecret;
    /**
     * 多维表属性
     */
    private List<LarkSuiteBitTableProperty> bitTable;
    /**
     * 云文档属性
     */
    private List<LarkSuiteTableProperty> table;

    public LarkSuiteBitTableProperty findBitTable(String name) {
        for (LarkSuiteBitTableProperty child : bitTable) {
            if (child.getName().equals(name)) {
                return child;
            }
        }

        throw new BizException("未找到对应的飞书多维表配置");
    }

    public LarkSuiteTableProperty findTable(String name) {
        for (LarkSuiteTableProperty child : table) {
            if (child.getName().equals(name)) {
                return child;
            }
        }

        throw new BizException("未找到对应的飞书云文档配置");
    }
}