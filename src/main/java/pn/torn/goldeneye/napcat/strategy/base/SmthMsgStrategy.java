package pn.torn.goldeneye.napcat.strategy.base;

import pn.torn.goldeneye.constants.torn.enums.TornFactionRoleTypeEnum;

/**
 * Pn群消息策略
 *
 * @author Bai
 * @version 0.3.0
 * @since 2025.07.24
 */
public abstract class SmthMsgStrategy extends BaseGroupMsgStrategy {
    @Override
    public TornFactionRoleTypeEnum getRoleType() {
        return null;
    }
}