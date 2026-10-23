package pn.torn.goldeneye.torn.model.faction.crime.recommend;

/**
 * OC产出估算来源枚举。
 *
 * <p>来源只表达「这个数字是怎么估出来的」，不表达该OC是否在大锅饭名单里：名单只决定收益分给谁，
 * 与推荐口径无关。估算层判定来源的唯一依据是「有没有足够的实测样本」，而不是配置或帮派身份。</p>
 *
 * @author Bai
 * @version 1.9.3
 * @since 2026.10.23
 */
public enum OcYieldSourceEnum {
    /**
     * 实测来源：该(OC名称, 等级, 帮派)在torn_faction_oc_income中的成功样本数已达到最小样本阈值
     */
    MEASURED,
    /**
     * 等级映射来源：无实测样本，按torn_setting_oc.expected_reward与等级阶梯映射估算
     */
    MAPPED,
    /**
     * 链映射来源：无实测样本且该OC属于链式OC，估算值已含前置步骤的成功率折算与前置人天
     */
    CHAIN_MAPPED
}
