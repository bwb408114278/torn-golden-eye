package pn.torn.goldeneye.repository.model.torn;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import pn.torn.goldeneye.repository.model.BaseDO;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Torn拍卖行表
 *
 * @author Bai
 * @version 0.5.0
 * @since 2026.01.13
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName(value = "torn_auction", autoResultMap = true)
public class TornAuctionDO extends BaseDO {
    /**
     * 拍卖ID
     */
    private Long id;
    /**
     * 买方ID
     */
    private Long buyerId;
    /**
     * 买方名称
     */
    private String buyerName;
    /**
     * 卖方ID
     */
    private Long sellerId;
    /**
     * 卖方名称
     */
    private String sellerName;
    /**
     * 成交时间
     */
    private LocalDateTime finishTime;
    /**
     * 成交金额
     */
    private Long price;
    /**
     * 竞价次数
     */
    private Integer bids;
    /**
     * 物品ID
     */
    private Integer itemId;
    /**
     * 物品UID
     */
    private Long itemUid;
    /**
     * 物品名称
     */
    private String itemName;
    /**
     * 物品类型
     */
    private String itemType;
    /**
     * 物品子类型
     */
    private String itemSubType;
    /**
     * 物品稀有度
     */
    private String itemRarity;
    /**
     * 伤害
     */
    private BigDecimal itemDamage;
    /**
     * 命中
     */
    private BigDecimal itemAccuracy;
    /**
     * 护甲
     */
    private BigDecimal itemArmor;
    /**
     * 品质
     */
    private BigDecimal itemQuality;
    /**
     * 加成1ID
     */
    private Integer bonus1Id;
    /**
     * 加成1名称
     */
    private String bonus1Title;
    /**
     * 加成1值
     */
    private Integer bonus1Value;
    /**
     * 加成1描述
     */
    private String bonus1Desc;
    /**
     * 加成2ID
     */
    private Integer bonus2Id;
    /**
     * 加成2名称
     */
    private String bonus2Title;
    /**
     * 加成2值
     */
    private Integer bonus2Value;
    /**
     * 加成2描述
     */
    private String bonus2Desc;
}