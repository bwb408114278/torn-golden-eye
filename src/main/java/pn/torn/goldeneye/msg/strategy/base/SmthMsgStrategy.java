package pn.torn.goldeneye.msg.strategy.base;

/**
 * Pn群消息策略
 *
 * @author Bai
 * @version 0.3.0
 * @since 2025.07.24
 */
public abstract class SmthMsgStrategy extends BaseGroupMsgStrategy {
    @Override
    public boolean isNeedAdmin() {
        return false;
    }
}