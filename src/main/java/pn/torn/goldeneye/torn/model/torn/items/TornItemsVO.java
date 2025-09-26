package pn.torn.goldeneye.torn.model.torn.items;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import pn.torn.goldeneye.repository.model.torn.TornItemsDO;

/**
 * Torn物品响应参数
 *
 * @author Bai
 * @version 0.2.0
 * @since 2025.09.26
 */
@Data
public class TornItemsVO {
    /**
     * 物品ID
     */
    private int id;
    /**
     * 物品名称
     */
    private String name;
    /**
     * 物品描述
     */
    private String description;
    /**
     * 物品效果
     */
    private String effect;
    /**
     *
     */
    private String requirement;
    /**
     * 图片
     */
    private String image;
    /**
     * 类型
     */
    private String type;
    /**
     * 子类型
     */
    @JsonProperty("sub_type")
    private String subType;
    /**
     * 是否面具
     */
    @JsonProperty("is_masked")
    private boolean isMasked;
    /**
     * 是否可交易
     */
    @JsonProperty("is_tradable")
    private boolean isTradable;
    /**
     * 是否在城市中找到
     */
    @JsonProperty("is_found_in_city")
    private boolean isFoundInCity;
    /**
     * 流通量
     */
    private long circulation;
    /**
     * 物品价值
     */
    private TornItemsValueVO value;

    public TornItemsDO convert2DO() {
        TornItemsDO item = new TornItemsDO();
        item.setId(this.id);
        item.setItemName(this.name);
        item.setItemImage(this.image);
        item.setItemType(this.type);
        item.setMarketPrice(this.value.getMarketPrice());
        return item;
    }
}