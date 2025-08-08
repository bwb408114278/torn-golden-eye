package pn.torn.goldeneye.constants.torn;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Torn常量
 *
 * @author Bai
 * @since 2025.07.22
 * @version 0.1.0
 */
@NoArgsConstructor(access = AccessLevel.NONE)
public class TornConstants {
    /**
     * 基础路径
     */
    public static final String BASE_URL = "https://api.torn.com";
    /**
     * 基础路径
     */
    public static final String BASE_URL_V2 = "https://api.torn.com/v2";

    /**
     * PN帮派ID
     */
    public static final long FACTION_PN_ID = 20465L;

    /**
     * 8级 oc chain名称
     */
    public static final String OC_RANK_8_CHAIN = "Stacking the Deck";
    /**
     * 物品名称 - 小红
     */
    public static final String ITEM_NAME_SMALL_RED = "Small First Aid Kit";
}