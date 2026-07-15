package pn.torn.goldeneye.torn.model.faction.crime.planning;

/**
 * 随机刷新建议。
 */
public record OcRefreshAdvice(boolean refreshRecommended, String spawnPool,
                              int refreshCount, boolean replanAfterRefresh,
                              String reason) {
}
