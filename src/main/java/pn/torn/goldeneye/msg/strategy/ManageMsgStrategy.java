package pn.torn.goldeneye.msg.strategy;

import jakarta.annotation.Resource;
import pn.torn.goldeneye.configuration.property.TestProperty;

/**
 * 管理员群消息策略
 *
 * @author Bai
 * @version 1.0
 * @since 2025.07.24
 */
public abstract class ManageMsgStrategy extends BaseMsgStrategy {
    @Resource
    private TestProperty testProperty;

    @Override
    public long getGroupId() {
        return testProperty.getGroupId();
    }
}