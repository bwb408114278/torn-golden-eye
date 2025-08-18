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
@Getter
public enum TornOcPositionAliasEnum {

    IMITATOR("Imitator", "模仿者", "冒牌货", "假扮者"),
    LOOTER("Looter", "掠夺者", "抢劫者", "劫掠者"),
    CAR_THIEF("Car Thief", "偷车贼", "汽车窃贼"),
    HUSTLER("Hustler", "骗子", "投机者", "混混", "奸商"),
    MUSCLE("Muscle", "肌肉", "壮汉", "保镖", "武力担当"),
    ENFORCER("Enforcer", "执行者", "打手", "执法者"),
    LOOKOUT("Lookout", "望风者", "放哨", "警戒者"),
    SNIPER("Sniper", "狙击手"),
    ENGINEER("Engineer", "工程师", "技工", "机械师"),
    HACKER("Hacker", "黑客", "骇客"),
    PICKLOCK("Picklock", "撬锁匠", "开锁人", "锁匠"),
    ROBBER("Robber", "强盗", "劫匪", "抢劫犯"),
    NEGOTIATOR("Negotiator", "谈判专家", "斡旋者", "调解人"),
    TECHIE("Techie", "技术员", "技术宅", "科技迷"),
    BOMBER("Bomber", "爆破手", "投弹手", "炸弹客"),
    DRIVER("Driver", "司机", "车手", "驾驶员"),
    THIEF("Thief", "小偷", "贼"),
    CAT_BURGLAR("Cat Burglar", "飞贼", "夜贼", "攀爬盗");

    private final String key;
    private final String[] aliases;

    TornOcPositionAliasEnum(String key, String... aliases) {
        this.key = key;
        this.aliases = aliases;
    }

    public static TornOcPositionAliasEnum keyOf(String key) {
        if (!StringUtils.hasText(key)) {
            return null;
        }

        for (TornOcPositionAliasEnum value : values()) {
            if (org.apache.commons.lang3.StringUtils.equalsAnyIgnoreCase(key, value.key)) {
                return value;
            }
        }

        return null;
    }

}