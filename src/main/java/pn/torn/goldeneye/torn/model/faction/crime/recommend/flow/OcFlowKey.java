package pn.torn.goldeneye.torn.model.faction.crime.recommend.flow;

/**
 * OC流程图唯一键：同一OC名称在不同等级是不同的流程图。
 *
 * @param ocName OC名称
 * @param rank   OC等级
 * @author Bai
 * @version 1.9.3
 * @since 2026.10.24
 */
public record OcFlowKey(
        String ocName,
        Integer rank) {
    /**
     * 构造流程图键。
     *
     * @param ocName OC名称
     * @param rank   OC等级
     * @return 流程图键
     */
    public static OcFlowKey of(String ocName, Integer rank) {
        return new OcFlowKey(ocName, rank);
    }
}
