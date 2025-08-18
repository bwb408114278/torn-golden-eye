package pn.torn.goldeneye.constants.torn.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.util.StringUtils;

/**
 * OC岗位枚举
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.08.18
 */
@AllArgsConstructor
@Getter
public enum TornOcPositionEnum {
    GASLIGHT_3_IMITATOR("Imitator", "Imitator", 3),
    GASLIGHT_3_LOOTER("Looter", "Looter", 3),
    SMOKE_3_CAR_THIEF("Car Thief", "Car Thief", 3),
    SMOKE_3_HUSTLER("Hustler", "Hustler", 3),
    SMOKE_3_IMITATOR("Imitator", "Imitator", 3),
    SNOW_4_HUSTLER("Hustler", "Hustler", 4),
    SNOW_4_IMITATOR("Imitator", "Imitator", 4),
    SNOW_4_MUSCLE("Muscle", "Muscle", 4),
    STAGE_4_ENFORCER("Enforcer", "Enforcer", 4),
    STAGE_4_LOOKOUT("Lookout", "Lookout", 4),
    STAGE_4_MUSCLE("Muscle", "Muscle", 4),
    STAGE_4_SNIPER("Sniper", "Sniper", 4),
    COUNTER_5_ENGINEER("Engineer", "Engineer", 5),
    COUNTER_5_HACKER("Hacker", "Hacker", 5),
    COUNTER_5_LOOTER("Looter", "Looter", 5),
    COUNTER_5_PICKLOCK("Picklock", "Picklock", 5),
    COUNTER_5_ROBBER("Robber", "Robber", 5),
    LEAVE_5_IMITATOR("Imitator", "Imitator", 5),
    LEAVE_5_NEGOTIATOR("Negotiator", "Negotiator", 5),
    LEAVE_5_TECHIE("Techie", "Techie", 5),
    NO_5_CAR_THIEF("Car Thief", "Car Thief", 5),
    NO_5_ENGINEER("Engineer", "Engineer", 5),
    NO_5_TECHIE("Techie", "Techie", 5),
    BIDDING_6_BOMBER("Bomber", "Bomber", 6),
    BIDDING_6_DRIVER("Driver", "Driver", 6),
    BIDDING_6_ROBBER("Robber", "Robber", 6),
    HONEY_6_ENFORCER("Enforcer", "Enforcer", 6),
    HONEY_6_MUSCLE("Muscle", "Muscle", 6),
    BLAST_7_BOMBER("Bomber", "Bomber#1", 7),
    BLAST_7_ENGINEER("Engineer", "Engineer#1", 7),
    BLAST_7_HACKER("Hacker", "Hacker#1", 7),
    BLAST_7_MUSCLE("Muscle", "Muscle#1", 7),
    BLAST_7_PICKLOCK_1("Picklock", "Picklock#1", 7),
    BLAST_7_PICKLOCK_2("Picklock", "Picklock#2", 7),
    BREAK_8_MUSCLE_1("Muscle", "Muscle#1", 8),
    BREAK_8_MUSCLE_2("Muscle", "Muscle#2", 8),
    BREAK_8_MUSCLE_3("Muscle", "Muscle#3", 8),
    BREAK_8_ROBBER("Robber", "Robber#1", 8),
    BREAK_8_THIEF_1("Thief", "Thief#1", 8),
    BREAK_8_THIEF_2("Thief", "Thief#2", 8),
    STACKING_8_CAT_BURGLAR("Cat Burglar", "Cat Burglar", 8),
    STACKING_8_DRIVER("Driver", "Driver", 8),
    STACKING_8_HACKER("Hacker", "Hacker", 8),
    STACKING_8_IMITATOR("Imitator", "Imitator", 8),
    ACE_9_DRIVER("Driver", "Driver", 9),
    ACE_9_HACKER("Hacker", "Hacker", 9),
    ACE_9_IMITATOR("Imitator", "Imitator", 9),
    ACE_9_MUSCLE("Muscle", "Muscle", 9);

    private final String code;
    private final String fullCode;
    private final int rank;

    public static TornOcPositionEnum codeOf(String code, int rank) {
        if (!StringUtils.hasText(code)) {
            return null;
        }

        String upperCode = code.replace(" ", "").toUpperCase();
        for (TornOcPositionEnum value : values()) {
            if (value.getRank() == rank &&
                    value.getCode().replace(" ", "").toUpperCase().equals(upperCode)) {
                return value;
            }
        }

        return null;
    }
}