package pn.torn.goldeneye.torn.model.faction.crime.recommend.flow;

import java.util.List;

/**
 * OC流程图节点。
 *
 * <p>判定节点的参与者是「这个判定实际参与判定的岗位」：多个参与者时该判定成功率取它们的算术平均，
 * 与站点计算器口径一致。结局节点没有参与者。</p>
 *
 * @param nodeCode     节点编码
 * @param nodeType     节点类型
 * @param participants 判定参与者岗位编码；结局节点为空列表
 * @param rewardValue  好结局奖励（现金与道具折算）；坏结局为0，未配置时为null
 * @author Bai
 * @version 1.9.5
 * @since 2026.10.24
 */
public record OcFlowNode(
        String nodeCode,
        OcFlowNodeTypeEnum nodeType,
        List<String> participants,
        Long rewardValue) {
    /**
     * 兼容不需要结局奖励的图装配与测试。
     */
    public OcFlowNode(String nodeCode, OcFlowNodeTypeEnum nodeType, List<String> participants) {
        this(nodeCode, nodeType, participants, null);
    }

    /**
     * 归一化参与者列表，避免调用方传入null。
     */
    public OcFlowNode {
        participants = participants == null ? List.of() : List.copyOf(participants);
    }

    /**
     * 判断是否为判定节点。
     *
     * @return 判定节点返回true
     */
    public boolean isCheck() {
        return nodeType != null && nodeType.isCheck();
    }
}
