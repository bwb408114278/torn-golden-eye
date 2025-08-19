package pn.torn.goldeneye.constants.torn.enums;

import lombok.Getter;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import pn.torn.goldeneye.utils.StringFuzzyMatchUtils;

import java.util.*;
import java.util.stream.Collectors;

/**
 * OC岗位枚举
 *
 * @author zmpress
 * @version 0.1.0
 * @since 2025.08.18
 */
@Getter
public enum TornOcPositionAliasEnum {
    IMITATOR("Imitator", "模仿"),
    LOOTER("Looter", "掠夺"),
    CAR_THIEF("Car Thief", "偷车"),
    HUSTLER("Hustler", "骗子"),
    MUSCLE("Muscle", "肌肉"),
    ENFORCER("Enforcer", "打手"),
    LOOKOUT("Lookout", "放哨"),
    SNIPER("Sniper", "狙击"),
    ENGINEER("Engineer", "工程", "工程师"),
    HACKER("Hacker", "黑客"),
    PICKLOCK("Picklock", "锁匠", "开锁"),
    ROBBER("Robber", "强盗", "抢劫"),
    NEGOTIATOR("Negotiator", "谈判"),
    TECHIE("Techie", "技术"),
    BOMBER("Bomber", "爆破", "炸弹"),
    DRIVER("Driver", "司机", "开车"),
    THIEF("Thief", "小偷", "贼"),
    CAT_BURGLAR("Cat Burglar", "飞贼");

    private final String key;
    private final String[] aliases;
    private final Set<String> aliasSet = new HashSet<>();

    TornOcPositionAliasEnum(String key, String... aliases) {
        this.key = key;
        this.aliases = aliases;
        aliasSet.addAll(Arrays.stream(aliases).collect(Collectors.toSet()));
    }

    public static TornOcPositionAliasEnum codeOf(String code) {
        if (!StringUtils.hasText(code)) {
            return null;
        }

        Map<String, Set<String>> keyValueMap = new LinkedHashMap<>();
        for (TornOcPositionAliasEnum value : values()) {
            keyValueMap.put(value.getKey(), value.getAliasSet());
        }

        List<String> matchedKeys = StringFuzzyMatchUtils.fuzzyMatching(code, keyValueMap);
        if (CollectionUtils.isEmpty(matchedKeys)) {
            return null;
        }

        String matchedKey = matchedKeys.get(0);
        for (TornOcPositionAliasEnum value : values()) {
            if (value.getKey().equals(matchedKey)) {
                return value;
            }
        }

        return null;
    }

}