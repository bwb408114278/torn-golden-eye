package pn.torn.goldeneye.torn.model.torn.auction;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * Torn拍卖物品响应参数
 *
 * @author Bai
 * @version 0.5.0
 * @since 2026.01.13
 */
@Data
public class TornAuctionItemVO {
    /**
     * 物品ID
     */
    private int id;
    /**
     * 物品UID
     */
    private long uid;
    /**
     * 物品名称
     */
    private String name;
    /**
     * 物品类型
     */
    private String type;
    /**
     * 物品子类型
     */
    @JsonProperty("sub_type")
    private String subType;
    /**
     * 物品状态
     */
    private TornAuctionItemStatsVO stats;
    /**
     * 物品加成
     */
    private List<TornAuctionItemBonusVO> bonuses;
    /**
     * 稀有程度
     */
    private String rarity;
}