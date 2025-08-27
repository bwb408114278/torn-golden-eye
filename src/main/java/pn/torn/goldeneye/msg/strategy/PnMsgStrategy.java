package pn.torn.goldeneye.msg.strategy;

/**
 * Pn群消息策略
 *
 * @author Bai
 * @version 1.0
 * @since 2025.07.24
 */
public abstract class PnMsgStrategy extends BaseGroupMsgStrategy {
    @Override
    public boolean isNeedAdmin() {
        return false;
    }
}