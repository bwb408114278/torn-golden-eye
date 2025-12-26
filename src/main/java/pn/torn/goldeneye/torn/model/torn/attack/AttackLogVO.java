package pn.torn.goldeneye.torn.model.torn.attack;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import pn.torn.goldeneye.repository.model.torn.TornAttackLogDO;
import pn.torn.goldeneye.torn.manager.torn.AttackLogParser;
import pn.torn.goldeneye.torn.model.user.elo.TornUserStatsVO;
import pn.torn.goldeneye.utils.DateTimeUtils;

import java.util.Map;

import static pn.torn.goldeneye.constants.torn.TornConstants.*;

/**
 * 战斗Log响应参数
 *
 * @author Bai
 * @version 0.4.0
 * @since 2025.12.17
 */
@Slf4j
@Data
public class AttackLogVO {
    /**
     * 战斗文本
     */
    private String text;
    /**
     * 战斗时间
     */
    private long timestamp;
    /**
     * 动作类型
     */
    private String action;
    /**
     * 图标
     */
    private String icon;
    /**
     * 攻击方
     */
    private AttackLogAttackerVO attacker;
    /**
     * 防守方
     */
    private AttackLogDefenderVO defender;

    public void setText(String text) {
        this.text = text.replace("  ", " ");
    }

    public TornAttackLogDO convert2DO(String logId, Map<Long, String> userNameMap, Map<Long, TornUserStatsVO> eloMap) {
        TornAttackLogDO log = new TornAttackLogDO();
        log.setLogId(logId);
        log.setLogTime(DateTimeUtils.convertToDateTime(this.timestamp));
        log.setLogText(this.text);
        log.setLogAction(this.action);
        log.setLogIcon(this.icon);

        extractAttacker(userNameMap, log, eloMap);
        extractDefender(userNameMap, log, eloMap);
        extractDamage(log);
        return log;
    }

    private void extractAttacker(Map<Long, String> userNameMap, TornAttackLogDO log, Map<Long, TornUserStatsVO> eloMap) {
        if (this.attacker == null) {
            log.setAttackerId(0L);
            log.setAttackerName(SOMEONE);
            log.setAttackerItemId(0L);
            log.setAttackerItemName("");
        } else {
            log.setAttackerId(this.attacker.getId());
            log.setAttackerName(this.attacker.getName(userNameMap));
            log.setAttackerElo(getElo(log.getAttackerId(), eloMap));
            if (log.getLogText().startsWith(SOMEONE) && !checkIsDefenderType()) {
                log.setLogText(log.getLogText().replaceFirst(SOMEONE, log.getAttackerName()));
            }

            if (this.attacker.getItem() != null && !this.attacker.getItem().getId().equals(999L)) {
                log.setAttackerItemId(this.attacker.getItem().getId());
                log.setAttackerItemName(this.attacker.getItem().getName());
            } else {
                log.setAttackerItemId(0L);
                log.setAttackerItemName("");
            }
        }
    }

    private void extractDefender(Map<Long, String> userNameMap, TornAttackLogDO log, Map<Long, TornUserStatsVO> eloMap) {
        if (this.defender == null) {
            log.setDefenderId(0L);
            log.setDefenderName(SOMEONE);
        } else {
            log.setDefenderId(this.defender.getId());
            log.setDefenderName(this.defender.getName(userNameMap));
            log.setDefenderElo(getElo(log.getDefenderId(), eloMap));

            if (log.getLogText().contains(SOMEONE)) {
                if (!log.getLogText().startsWith(SOMEONE) || checkIsDefenderType()) {
                    log.setLogText(log.getLogText().replaceFirst(SOMEONE, log.getDefenderName()));
                } else {
                    int secondIndex = log.getLogText().indexOf(SOMEONE, 7);
                    if (secondIndex != -1) {
                        String result = log.getLogText().substring(0, secondIndex)
                                + log.getDefenderName()
                                + log.getLogText().substring(secondIndex + 7);
                        log.setLogText(result);
                    }
                }
            }
        }
    }

    private void extractDamage(TornAttackLogDO log) {
        String syringe = checkIsSyringe();
        if (StringUtils.hasText(syringe)) {
            log.setSyringeType(syringe);
            return;
        }

        AttackLogParser.CombatLog result = AttackLogParser.LogParser.parse(log.getLogText());
        log.setDamage(result.getDamage());
        log.setIsMiss(result.getIsMiss());
        log.setIsCritical(result.isCritical());
        log.setHitLocation(result.getHitLocation());
        log.setAmmoType(result.getAmmoType());
        log.setDamageType(result.getDamageType());
    }

    private boolean checkIsDefenderType() {
        for (String typeText : DEFENDER_ATTACK_TYPE) {
            if (this.text.contains(typeText)) {
                return true;
            }
        }

        return false;
    }

    private String checkIsSyringe() {
        for (String typeText : SYRINGE) {
            if (this.text.contains(typeText)) {
                return typeText;
            }
        }

        return "";
    }

    private Integer getElo(long userId, Map<Long, TornUserStatsVO> eloMap) {
        if (userId == 0L) {
            return 0;
        }

        TornUserStatsVO stats = eloMap.get(userId);
        return stats == null ? 0 : stats.getValue();
    }
}