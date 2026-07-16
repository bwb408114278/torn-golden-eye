package pn.torn.goldeneye.torn.model.faction.crime.planning;

/**
 * OC规划配置状态。
 */
public enum OcPlanStatus {
    DISABLED,
    OBSERVE_ONLY,
    READY;

    /**
     * 解析OC规划档案状态。
     *
     * @param value 状态编码；为空时使用观察模式
     * @return 对应的规划档案状态
     */
    public static OcPlanStatus of(String value) {
        return value == null ? DISABLED : valueOf(value);
    }
}
