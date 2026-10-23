package pn.torn.goldeneye.torn.model.faction.crime.recommend;

import pn.torn.goldeneye.repository.model.setting.TornSettingOcSlotDO;

/**
 * 成员对某个空位的可填性判定结果。
 *
 * <p>一次判定同时带出岗位配置与成员成功率，供推荐与分配两个入口共用，避免同一段
 * 「查岗位配置、查成员成功率、比较成功率」的判定在两处各写一遍。</p>
 *
 * @param requirement 岗位配置（已按帮派覆盖成功率要求），不为空
 * @param passRate    成员在该岗位的成功率；null表示成功率矩阵无记录，按能力未知处理
 * @author Bai
 * @version 1.9.3
 * @since 2026.10.23
 */
public record OcSlotEligibility(
        TornSettingOcSlotDO requirement,
        Integer passRate) {

    /**
     * 判断成员是否达到该岗位的成功率要求。
     *
     * <p>口径：成功率无记录不等于不可推荐（能力未知按可填处理，交由映射产出参与排序）；
     * 只有矩阵里明确记录的数值低于岗位要求时才算不达标。岗位未配置成功率要求时一律可填。</p>
     *
     * @return 可填时返回true
     */
    public boolean eligible() {
        Integer requiredPassRate = requirement.getPassRate();
        if (requiredPassRate == null || passRate == null) {
            return true;
        }
        return passRate >= requiredPassRate;
    }
}
