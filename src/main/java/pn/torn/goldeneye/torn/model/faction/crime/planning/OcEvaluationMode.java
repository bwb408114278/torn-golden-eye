package pn.torn.goldeneye.torn.model.faction.crime.planning;

/**
 * OC规划岗位评价模式。
 */
public enum OcEvaluationMode {
    DIFFERENTIAL_WORK_HOUR,
    POSITION_WEIGHT;

    /**
     * 解析岗位候选人评价模式。
     *
     * @param value 评价模式编码；为空时使用岗位权重模式
     * @return 对应的评价模式
     */
    public static OcEvaluationMode of(String value) {
        return value == null ? POSITION_WEIGHT : valueOf(value);
    }
}
