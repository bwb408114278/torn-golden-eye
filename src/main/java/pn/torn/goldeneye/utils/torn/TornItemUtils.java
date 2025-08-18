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
    private static final List<String> ITEM_NAME_LIST = new ArrayList<>();

    static {
        ITEM_NAME_LIST.add("Bottle of Beer");
        ITEM_NAME_LIST.add("Bottle of Moonshine");

        ITEM_NAME_LIST.add("Can of Crocozade");

        ITEM_NAME_LIST.add("Bag of Bon Bons");
        ITEM_NAME_LIST.add("Box of Extra Strong Mints");

        ITEM_NAME_LIST.add(TornConstants.ITEM_NAME_SMALL_RED);
        ITEM_NAME_LIST.add("First Aid Kit");
        ITEM_NAME_LIST.add("Morphine");
        ITEM_NAME_LIST.add("Neumune Tablet");

        ITEM_NAME_LIST.add("Empty Blood Bag");
        ITEM_NAME_LIST.add("Blood Bag : A+");
        ITEM_NAME_LIST.add("Blood Bag : A-");
        ITEM_NAME_LIST.add("Blood Bag : AB+");
        ITEM_NAME_LIST.add("Blood Bag : AB-");
        ITEM_NAME_LIST.add("Blood Bag : B+");
        ITEM_NAME_LIST.add("Blood Bag : B-");
        ITEM_NAME_LIST.add("Blood Bag : O+");
        ITEM_NAME_LIST.add("Blood Bag : O-");
        ITEM_NAME_LIST.add("Blood Bag : Irradiated");
    }

    /**
     * 从文本中获取物品名称
     *
     * @param text 文本
     * @return 物品名称
     */
    public static String getItemName(String text) {
        for (String item : ITEM_NAME_LIST) {
            if (text.contains(item)) {
                return item;
            }
        }

        return text;
    }
}