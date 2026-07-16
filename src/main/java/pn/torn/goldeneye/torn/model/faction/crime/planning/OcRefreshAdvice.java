package pn.torn.goldeneye.torn.model.faction.crime.planning;

/**
 * 随机刷新建议。
 *
 * @param refreshRecommended 是否建议刷新OC
 * @param spawnPool 建议刷新的OC池类型
 * @param refreshCount 建议刷新次数
 * @param replanAfterRefresh 刷新后是否需要重新规划
 * @param reason 刷新建议原因
 */public record OcRefreshAdvice(boolean refreshRecommended, String spawnPool,
                              int refreshCount, boolean replanAfterRefresh,
                              String reason) {
}
