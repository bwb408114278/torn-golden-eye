package pn.torn.goldeneye.torn.model.faction.crime.recommend.flow;

/**
 * OC流程图判定分支枚举。
 *
 * <p>每个判定恰好两条出边：PASS沿用该判定的成功率，FAIL取补集。声明顺序同时用作遍历顺序，
 * 保证同一份配置每次装配得到的拓扑序一致。</p>
 *
 * @author Bai
 * @version 1.9.3
 * @since 2026.10.24
 */
public enum OcFlowBranchEnum {
    /**
     * 判定通过
     */
    PASS,
    /**
     * 判定失败
     */
    FAIL;

    /**
     * 按配置文本解析分支。
     *
     * @param code 配置文本
     * @return 分支
     * @throws IllegalArgumentException 配置文本为空或不认识
     */
    public static OcFlowBranchEnum fromCode(String code) {
        if (code == null) {
            throw new IllegalArgumentException("流程图分支为空");
        }
        try {
            return valueOf(code.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知的流程图分支: " + code);
        }
    }
}
