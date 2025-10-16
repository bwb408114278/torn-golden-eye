package pn.torn.goldeneye.msg.strategy.base;

import jakarta.annotation.Resource;
import pn.torn.goldeneye.configuration.property.ProjectProperty;

/**
 * Pn群消息策略
 *
 * @author Bai
 * @version 0.3.0
 * @since 2025.10.16
 */
public abstract class PnMsgStrategy extends BaseGroupMsgStrategy {
    @Resource
    protected ProjectProperty projectProperty;

    @Override
    public long getCustomGroupId() {
        return projectProperty.getGroupId();
    }

    @Override
    public boolean isNeedAdmin() {
        return false;
    }
}