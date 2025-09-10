package pn.torn.goldeneye.configuration.property.larksuite;

import lombok.Data;

/**
 * 飞书多维表属性
 *
 * @author Bai
 * @version 0.2.0
 * @since 2025.09.08
 */
@Data
public class LarkSuiteBitTableProperty {
    /**
     * 业务名称
     */
    private String name;
    /**
     * APP Token
     */
    private String appToken;
    /**
     * 表格ID
     */
    private String tableId;
    /**
     * 视图ID
     */
    private String viewId;
}