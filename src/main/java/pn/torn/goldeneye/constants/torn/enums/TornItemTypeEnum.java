package pn.torn.goldeneye.constants.torn.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

/**
 * 物品类型枚举
 *
 * @author Bai
 * @version 0.3.0
 * @since 2025.11.20
 */
@AllArgsConstructor
@Getter
public enum TornItemTypeEnum {
    /**
     * 饮料
     */
    ENERGY_DRINK("Energy Drink"),
    /**
     * 酒
     */
    ALCOHOL("Alcohol"),
    /**
     * 糖
     */
    CANDY("Candy");

    private final String code;

    public static List<String> getMisuseCodeList() {
        return List.of(ENERGY_DRINK.getCode(), ALCOHOL.getCode(), CANDY.getCode());
    }
}