package pn.torn.goldeneye.torn.model.faction.armory;

/**
 * Torn帮派可用物品接口
 *
 * @author Bai
 * @version 0.4.0
 * @since 2025.12.30
 */
public interface TornFactionUsageItem {
    /**
     * 获取物品ID
     *
     * @return 物品ID
     */
    Long getItemId();

    /**
     * 获取可用数量
     *
     * @return 可用数量
     */
    Integer getAvailableQty();
}