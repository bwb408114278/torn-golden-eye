package pn.torn.goldeneye.torn.model.faction.crime.recommend;

import pn.torn.goldeneye.repository.model.setting.TornSettingOcSlotDO;

/**
 * 成员对某个空位的可填性判定结果。
 *
 * <p>一次判定同时带出岗位配置与成员成功率，供推荐与分配两个入口共用，避免同一段
 * 「查岗位配置、查成员成功率、比较成功率」的判定在两处各写一遍。</p>
 *
 * @param requirement 岗位配置（已按帮派覆盖成功率要求），不为空
 * @param passRate    成员在该岗位的实测成功率；null表示成功率矩阵没有该成员在「该OC、该等级、该岗位」的记录
 * @author Bai
 * @version 1.9.5
 * @since 2026.10.23
 */
public record OcSlotEligibility(
        TornSettingOcSlotDO requirement,
        Integer passRate) {

    /**
     * 判断成员是否达到该岗位的成功率要求。
     *
     * <p>口径（2026-10-26 业务口径变更，撤销「能力未知按可填」）：成功率矩阵无记录只说明该岗位从未实测，
     * 不代表能力达标。若按可填处理，「没测过」比「测过但偏低」更容易通过准入——实测成员被门槛挡在门外，
     * 缺数据成员直通高价值高级队。因此仅当岗位未配置成功率要求时无条件可填；配置了要求时，
     * 必须存在实测记录且不低于要求才算达标。</p>
     *
     * @return 可填时返回true
     */
    public boolean eligible() {
        Integer requiredPassRate = requirement.getPassRate();
        if (requiredPassRate == null) {
            return true;
        }
        return passRate != null && passRate >= requiredPassRate;
    }
}
