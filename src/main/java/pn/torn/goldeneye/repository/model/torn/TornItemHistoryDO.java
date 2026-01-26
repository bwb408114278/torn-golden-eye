package pn.torn.goldeneye.repository.model.torn;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import pn.torn.goldeneye.repository.model.BaseDO;

import java.time.LocalDate;

/**
 * Torn物品历史表
 *
 * @author Bai
 * @version 0.5.0
 * @since 2026.01.26
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName(value = "torn_item_history", autoResultMap = true)
public class TornItemHistoryDO extends BaseDO {
    /**
     * ID
     */
    private Long id;
    /**
     * 物品ID
     */
    private Integer itemId;
    /**
     * 物品名称
     */
    private String itemName;
    /**
     * 市场价
     */
    private Long marketPrice;
    /**
     * 存量
     */
    private Long circulation;
    /**
     * 记录日期
     */
    private LocalDate regDate;
}