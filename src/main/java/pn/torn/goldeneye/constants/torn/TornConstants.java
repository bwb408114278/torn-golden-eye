package pn.torn.goldeneye.constants.torn;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Torn常量
 *
 * @author Bai
 * @version 0.3.0
 * @since 2025.07.22
 */
@NoArgsConstructor(access = AccessLevel.NONE)
public class TornConstants {
    // ====================基础设置相关====================
    /**
     * 基础路径
     */
    public static final String BASE_URL_V2 = "https://api.torn.com/v2";
    /**
     * PN帮派ID
     */
    public static final long FACTION_PN_ID = 20465L;

    // ====================OC相关====================
    public static final List<String> ROTATION_OC_NAME = new ArrayList<>();
    /**
     * 飞书多维表 - OC收益
     */
    public static final String BIT_TABLE_OC_BENEFIT = "oc_benefit";

    static {
        ROTATION_OC_NAME.add("Blast from the Past");
        ROTATION_OC_NAME.add("Break the Bank");
        ROTATION_OC_NAME.add("Clinical Precision");
    }

    // ====================物品相关====================
    /**
     * 物品名称 - 小红
     */
    public static final String ITEM_NAME_SMALL_RED = "Small First Aid Kit";
}