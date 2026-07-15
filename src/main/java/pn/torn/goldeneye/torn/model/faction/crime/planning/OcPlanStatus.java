package pn.torn.goldeneye.torn.model.faction.crime.planning;

/**
 * OC规划配置状态。
 */
public enum OcPlanStatus {
    DISABLED,
    OBSERVE_ONLY,
    READY;

    public static OcPlanStatus of(String value) {
        return value == null ? DISABLED : valueOf(value);
    }
}
