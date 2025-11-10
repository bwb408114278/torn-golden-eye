package pn.torn.goldeneye.utils.torn;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import pn.torn.goldeneye.constants.torn.TornConstants;

import java.util.ArrayList;
import java.util.List;

/**
 * 物品名称过滤工具
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.08.07
 */
@Slf4j
@NoArgsConstructor(access = AccessLevel.NONE)
public class TornItemUtils {
    private static final List<String> FACTION_ITEM_NAME_LIST = new ArrayList<>();

    static {
        FACTION_ITEM_NAME_LIST.add("Bottle of Beer");
        FACTION_ITEM_NAME_LIST.add("Bottle of Mistletoe Madness");
        FACTION_ITEM_NAME_LIST.add("Bottle of Pumpkin Brew");
        FACTION_ITEM_NAME_LIST.add("Bottle of Wicked Witch");
        FACTION_ITEM_NAME_LIST.add("Bottle of Kandy Kane");
        FACTION_ITEM_NAME_LIST.add("Bottle of Minty Mayhem");
        FACTION_ITEM_NAME_LIST.add("Bottle of Stinky Swamp Punch");
        FACTION_ITEM_NAME_LIST.add("Bottle of Champagne");
        FACTION_ITEM_NAME_LIST.add("Bottle of Tequila");
        FACTION_ITEM_NAME_LIST.add("Bottle of Moonshine");

        FACTION_ITEM_NAME_LIST.add(TornConstants.ITEM_NAME_SMALL_RED);
        FACTION_ITEM_NAME_LIST.add("First Aid Kit");
        FACTION_ITEM_NAME_LIST.add("Morphine");
        FACTION_ITEM_NAME_LIST.add("Ipecac Syrup");
        FACTION_ITEM_NAME_LIST.add("Neumune Tablet");

        FACTION_ITEM_NAME_LIST.add("Empty Blood Bag");
        FACTION_ITEM_NAME_LIST.add("Blood Bag : A+");
        FACTION_ITEM_NAME_LIST.add("Blood Bag : A-");
        FACTION_ITEM_NAME_LIST.add("Blood Bag : AB+");
        FACTION_ITEM_NAME_LIST.add("Blood Bag : AB-");
        FACTION_ITEM_NAME_LIST.add("Blood Bag : B+");
        FACTION_ITEM_NAME_LIST.add("Blood Bag : B-");
        FACTION_ITEM_NAME_LIST.add("Blood Bag : O+");
        FACTION_ITEM_NAME_LIST.add("Blood Bag : O-");
        FACTION_ITEM_NAME_LIST.add("Blood Bag : Irradiated");

        FACTION_ITEM_NAME_LIST.add("Bag of Bon Bons");
        FACTION_ITEM_NAME_LIST.add("Bag of Chocolate Kisses");
        FACTION_ITEM_NAME_LIST.add("Bag of Tootsie Rolls");
        FACTION_ITEM_NAME_LIST.add("Bag of Candy Kisses");
        FACTION_ITEM_NAME_LIST.add("Box of Bon Bons");
        FACTION_ITEM_NAME_LIST.add("Box of Sweet Hearts");
        FACTION_ITEM_NAME_LIST.add("Box of Chocolate Bars");
        FACTION_ITEM_NAME_LIST.add("Box of Extra Strong Mints");
        FACTION_ITEM_NAME_LIST.add("Pixie Sticks");
        FACTION_ITEM_NAME_LIST.add("Lollipop");

        FACTION_ITEM_NAME_LIST.add("Can of Goose Juice");
        FACTION_ITEM_NAME_LIST.add("Can of Damp Valley");
        FACTION_ITEM_NAME_LIST.add("Can of Crocozade");
        FACTION_ITEM_NAME_LIST.add("Can of Taurine Elite");

        FACTION_ITEM_NAME_LIST.add("Xanax");
        FACTION_ITEM_NAME_LIST.add("Cannabis");
        FACTION_ITEM_NAME_LIST.add("Vicodin");
        FACTION_ITEM_NAME_LIST.add("Ecstasy");
    }

    /**
     * 从文本中获取物品名称
     *
     * @param text 文本
     * @return 物品名称
     */
    public static String getItemName(String text) {
        for (String item : FACTION_ITEM_NAME_LIST) {
            if (text.contains(item)) {
                return item;
            }
        }

        return text;
    }
}