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
 * @version 0.2.0
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
     * 多维表属性
     */
    private List<LarkSuiteBitTableProperty> bitTable;

    public LarkSuiteBitTableProperty findBitTable(String name) {
        for (LarkSuiteBitTableProperty bitTable : bitTable) {
            if (bitTable.getName().equals(name)) {
                return bitTable;
            }
        }

        throw new BizException("未找到对应的飞书多维表配置");
    }
}