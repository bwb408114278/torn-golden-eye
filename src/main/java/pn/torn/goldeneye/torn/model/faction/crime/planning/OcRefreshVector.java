package pn.torn.goldeneye.torn.model.faction.crime.planning;

/**
 * 普通池与高阶池的联合刷新次数向量。
 *
 * @param normalCount 普通池刷新次数
 * @param highCount 高阶池刷新次数
 * @author Bai
 * @version 1.2.10
 * @since 2026.07.17
 */
public record OcRefreshVector(int normalCount, int highCount) {

    /**
     * 获取本向量的总刷新次数。
     *
     * @return 普通池和高阶池刷新次数之和
     */
    public int totalCount() {
        return normalCount + highCount;
    }
}
