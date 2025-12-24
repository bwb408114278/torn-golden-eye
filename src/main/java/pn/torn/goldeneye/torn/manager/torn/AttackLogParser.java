package pn.torn.goldeneye.torn.manager.torn;

import lombok.AccessLevel;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 战斗日志分期器
 *
 * @author Bai
 * @version 0.4.0
 * @since 2025.12.23
 */
@NoArgsConstructor(access = AccessLevel.NONE)
public class AttackLogParser {
    // 战斗日志数据模型
    @Data
    public static class CombatLog {
        /**
         * 伤害
         */
        private Integer damage;
        /**
         * 命中部位
         */
        private String hitLocation;
        /**
         * 伤害类型
         */
        private String damageType;
        /**
         * 子弹类型
         */
        private String ammoType;
        /**
         * 是否失手
         */
        private Boolean isMiss;

        public Boolean isCritical() {
            return Boolean.FALSE.equals(isMiss) &&
                    ("Head".equals(hitLocation) || "Throat".equals(hitLocation) || "Heart".equals(hitLocation));
        }
    }

    // 解析器
    @NoArgsConstructor(access = AccessLevel.NONE)
    public static class LogParser {
        // 各种攻击模式的正则表达式
        private static final Pattern FIRED_PATTERN = Pattern.compile(
                "(\\w+) fired (\\d+) (?:(\\w+) )?rounds? of (?:his|her|their) ([\\w\\s\\-]+?) " +
                        "(critically hitting|hitting|missing|puncturing) (\\w+)(?: in the ([\\w\\s]+?) for (\\d+))?");
        private static final Pattern HIT_PATTERN = Pattern.compile(
                "(\\w+) (critically )?(?:hit|missed|flogged) (\\w+) with (?:his|her|their) ([\\w\\s\\-]+)" +
                        "(?: in the ([\\w\\s]+?) for (\\d+))?");

        private static final Pattern BLEEDING_PATTERN = Pattern.compile(
                "Bleeding damaged (\\w+) for (\\d+)");

        private static final Pattern POISON_PATTERN = Pattern.compile(
                "Poison damaged (\\w+) for (\\d+)");

        private static final Pattern TEMP_PATTERN = Pattern.compile(
                "\\w+ (?:threw a [\\w\\s]+|sprayed [\\w\\s]+ in \\w+'s face|missed \\w+ " +
                        "with (?:his|her|their) [\\w\\s]+)");

        private static final Pattern DAMAGE_PATTERN = Pattern.compile("for (\\d+)");


        /**
         * 解析单条日志
         */
        public static CombatLog parse(String logText) {
            CombatLog log = new CombatLog();
            if (parseGunAttack(log, logText)) {
                return log;
            }

            if (parseTempDamage(log, logText)) {
                return log;
            }

            if (parseMeleeAttack(log, logText)) {
                return log;
            }

            if (parseBleedingDamage(log, logText)) {
                return log;
            }

            if (parsePoisonDamage(log, logText)) {
                return log;
            }

            return log;
        }

        /**
         * 解析射击攻击
         */
        private static boolean parseGunAttack(CombatLog log, String text) {
            Matcher m = FIRED_PATTERN.matcher(text);
            if (!m.find()) {
                return false;
            }

            log.setDamageType("gun");
            String ammoType = m.group(3);
            if (ammoType != null) {
                log.setAmmoType(ammoType);
            }

            String action = m.group(5);
            if (action != null) {
                if (!action.equals("missing")) {
                    log.setIsMiss(false);
                    if (m.group(7) != null) {
                        log.setHitLocation(m.group(7));
                    }
                    if (m.group(8) != null) {
                        log.setDamage(Integer.parseInt(m.group(8)));
                    }
                } else {
                    log.setIsMiss(true);
                }
            }
            return true;
        }

        /**
         * 解析近战攻击
         */
        private static boolean parseMeleeAttack(CombatLog log, String text) {
            Matcher m = HIT_PATTERN.matcher(text);
            if (m.find()) {
                log.setDamageType("melee");
                // 检查是否命中
                if (text.contains("missed")) {
                    log.setIsMiss(true);
                } else {
                    log.setIsMiss(false);
                    if (m.group(5) != null) {
                        log.setHitLocation(m.group(5));
                    }
                    if (m.group(6) != null) {
                        log.setDamage(Integer.parseInt(m.group(6)));
                    }
                }

                return true;
            }
            return false;
        }

        /**
         * 解析流血伤害
         */
        private static boolean parseBleedingDamage(CombatLog log, String text) {
            Matcher m = BLEEDING_PATTERN.matcher(text);
            if (m.find()) {
                log.setDamage(Integer.parseInt(m.group(2)));
                log.setDamageType("bleeding");
                return true;
            }
            return false;
        }

        /**
         * 解析中毒伤害
         */
        private static boolean parsePoisonDamage(CombatLog log, String text) {
            Matcher m = POISON_PATTERN.matcher(text);
            if (m.find()) {
                log.setDamage(Integer.parseInt(m.group(2)));
                log.setDamageType("poison");
                return true;
            }
            return false;
        }

        /**
         * 解析手榴弹伤害
         */
        private static boolean parseTempDamage(CombatLog log, String text) {
            if (!TEMP_PATTERN.matcher(text).find()) {
                return false;
            }

            log.setDamageType("temp");

            // 判断是否miss/未生效
            boolean isMissOrIneffective = text.contains("missed") ||
                    text.contains("ineffective") ||
                    text.contains("deflected");
            if (isMissOrIneffective) {
                log.setIsMiss(true);
            } else {
                log.setIsMiss(false);
                Matcher damageMatcher = DAMAGE_PATTERN.matcher(text);
                if (damageMatcher.find()) {
                    log.setDamage(Integer.parseInt(damageMatcher.group(1)));
                }
            }

            return true;
        }
    }
}