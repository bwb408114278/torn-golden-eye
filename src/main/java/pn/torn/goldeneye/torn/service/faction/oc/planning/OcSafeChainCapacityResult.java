package pn.torn.goldeneye.torn.service.faction.oc.planning;

/**
 * 高阶链安全并行容量证明结果。
 *
 * @param committedCount 已存在成员的高阶根数量
 * @param provenSafeConcurrentCount 已证明安全的高阶链总并行数
 * @param provenAdditionalCount 已证明可新增的高阶链数量
 * @param maximumProven 是否已证明该数量为最大值而非安全下界
 */public record OcSafeChainCapacityResult(int committedCount, int provenSafeConcurrentCount,
                                        int provenAdditionalCount, boolean maximumProven) {
}
