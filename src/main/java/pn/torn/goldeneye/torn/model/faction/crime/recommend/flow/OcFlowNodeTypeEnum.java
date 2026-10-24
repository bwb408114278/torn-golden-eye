package pn.torn.goldeneye.torn.model.faction.crime.recommend.flow;

/**
 * OC流程图节点类型枚举。
 *
 * <p>只区分「需要判定的节点」与「两种结局」，不表达结局的好坏程度：排序只需要知道某条路径
 * 是否算成功，好结局之间不再细分。</p>
 *
 * @author Bai
 * @version 1.9.3
 * @since 2026.10.24
 */
public enum OcFlowNodeTypeEnum {
    /**
     * 判定节点：按参与者成功率分流到PASS与FAIL两条分支
     */
    CHECK,
    /**
     * 好结局：到达该节点的路径计入OC成功
     */
    GOOD_END,
    /**
     * 坏结局：到达该节点的路径计入OC失败
     */
    BAD_END;

    /**
     * 按配置文本解析节点类型。
     *
     * @param code 配置文本
     * @return 节点类型
     * @throws IllegalArgumentException 配置文本为空或不认识
     */
    public static OcFlowNodeTypeEnum fromCode(String code) {
        if (code == null) {
            throw new IllegalArgumentException("流程图节点类型为空");
        }
        try {
            return valueOf(code.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知的流程图节点类型: " + code);
        }
    }

    /**
     * 判断是否为判定节点。
     *
     * @return 判定节点返回true
     */
    public boolean isCheck() {
        return this == CHECK;
    }
}
