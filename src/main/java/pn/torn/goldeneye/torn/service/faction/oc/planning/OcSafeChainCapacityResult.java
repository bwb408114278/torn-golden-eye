package pn.torn.goldeneye.torn.service.faction.oc.planning;

/**
 * 高阶链安全并行容量证明结果。
 */
public record OcSafeChainCapacityResult(int committedCount, int provenSafeConcurrentCount,
                                        int provenAdditionalCount, boolean maximumProven) {
}
