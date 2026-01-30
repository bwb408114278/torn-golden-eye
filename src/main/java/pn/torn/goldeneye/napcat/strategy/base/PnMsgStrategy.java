package pn.torn.goldeneye.napcat.strategy.base;

import jakarta.annotation.Resource;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.constants.torn.enums.TornFactionRoleTypeEnum;

import java.util.List;

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
    public List<Long> getCustomGroupId() {
        return List.of(projectProperty.getGroupId());
    }

    @Override
    public TornFactionRoleTypeEnum getRoleType() {
        return null;
    }
}