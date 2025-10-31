package pn.torn.goldeneye.repository.model.torn;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import pn.torn.goldeneye.repository.model.BaseDO;

/**
 * Torn物品表
 *
 * @author Bai
 * @version 0.2.0
 * @since 2025.09.26
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName(value = "torn_items", autoResultMap = true)
public class TornItemsDO extends BaseDO {
    /**
     * 物品ID
     */
    private Integer id;
    /**
     * 股票名称
     */
    private String itemName;
    /**
     * 图片
     */
    private String itemImage;
    /**
     * 类型
     */
    private String itemType;
    /**
     * 市场价
     */
    private Long marketPrice;
    /**
     * 卖店价
     */
    private Long sellPrice;
}