package pn.torn.goldeneye.torn.model.faction.crime.constraint;

/**
 * Torn Oc接口约束
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.08.18
 */
public interface TornFactionOc {
    /**
     * OC ID
     *
     * @return ID
     */
    Long getId();

    /**
     * 获取OC名称
     *
     * @return OC名称
     */
    String getName();

    /**
     * 获取级别
     *
     * @return 级别
     */
    Integer getRank();
}