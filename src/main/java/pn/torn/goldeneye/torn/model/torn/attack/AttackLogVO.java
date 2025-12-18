package pn.torn.goldeneye.torn.model.torn.attack;

import lombok.Data;
import pn.torn.goldeneye.repository.model.torn.TornAttackLogDO;
import pn.torn.goldeneye.torn.manager.user.TornUserManager;
import pn.torn.goldeneye.utils.DateTimeUtils;

/**
 * 战斗Log响应参数
 *
 * @author Bai
 * @version 0.4.0
 * @since 2025.12.17
 */
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

    public TornAttackLogDO convert2DO(String logId, TornUserManager userManager) {
        TornAttackLogDO log = new TornAttackLogDO();
        log.setLogId(logId);
        log.setLogTime(DateTimeUtils.convertToDateTime(this.timestamp));
        log.setLogText(this.text);
        log.setLogAction(this.action);
        log.setLogIcon(this.icon);
        log.setAttackerId(this.attacker.getId());
        log.setAttackerName(this.attacker.getName(userManager));
        log.setDefenderId(this.defender.getId());
        log.setDefenderName(this.defender.getName(userManager));

        if (this.attacker.getItem() != null) {
            log.setAttackerItemId(this.attacker.getItem().getId());
            log.setAttackerItemName(this.attacker.getItem().getName());
        } else {
            log.setAttackerItemId(0L);
            log.setAttackerItemName("");
        }

        return log;
    }
}