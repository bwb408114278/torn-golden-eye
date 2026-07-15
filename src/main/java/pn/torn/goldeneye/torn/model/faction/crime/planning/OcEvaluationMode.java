package pn.torn.goldeneye.torn.model.faction.crime.planning;

/**
 * OC规划岗位评价模式。
 */
public enum OcEvaluationMode {
    DIFFERENTIAL_WORK_HOUR,
    POSITION_WEIGHT;

    public static OcEvaluationMode of(String value) {
        return value == null ? POSITION_WEIGHT : valueOf(value);
    }
}
