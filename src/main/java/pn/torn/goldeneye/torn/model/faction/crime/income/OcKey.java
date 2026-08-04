package pn.torn.goldeneye.torn.model.faction.crime.income;

/**
 * OC身份键，以名称和等级唯一定位一个OC，用于链配置父节点匹配。
 *
 * <p>链父节点匹配必须使用名称加等级，避免同名不同等级的OC被误判为同一链节点。</p>
 *
 * @param name OC名称
 * @param rank OC等级
 * @author Bai
 * @version 1.2.12
 * @since 2026.08.03
 */
public record OcKey(
        String name,
        int rank) {
}
