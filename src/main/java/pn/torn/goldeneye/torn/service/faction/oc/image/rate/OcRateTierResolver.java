package pn.torn.goldeneye.torn.service.faction.oc.image.rate;

import pn.torn.goldeneye.repository.model.setting.TornSettingFactionOcSlotDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcSlotDO;
import pn.torn.goldeneye.torn.service.faction.oc.OcSlotTierCalculator;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/**
 * 用户OC成功率表的展示口径解析器：成功率数值档、岗位双维星级文本、OC级别分组与帮派要求覆盖。
 * <p>
 * 纯静态口径，无DAO、时钟与Spring依赖。成功率档位只做展示映射，
 * 帮派要求覆盖解析与7级资格判定共用同一份匹配规则。
 *
 * @author Bai
 * @version 1.9.0
 * @since 2026.10.05
 */
public final class OcRateTierResolver {
    /**
     * 作为链式前置的8级OC名单，来自帮派OC实例中前置OC的真实统计。
     * <p>库中无静态前置配置字段，Torn新增链式关系时需人工维护该名单。</p>
     */
    public static final Set<String> CHAIN_PREREQUISITE_OCS =
            Set.of("Stacking the Deck", "Lock Stock", "Manifest Cruelty");

    private static final String SUCCESS_EMOJI = "⚔️";
    private static final String FORTUNE_EMOJI = "💰";
    private static final String STAR = "★";

    private static final int ENTRY_MAX_RANK = 7;
    private static final int CORE_RANK = 8;
    private static final int EXCEED_GAP = 10;
    private static final int FAIL_FAR_GAP = 10;
    private static final int TIER_FIVE = 5;

    private static final BigDecimal FORTUNE_TIER_FIVE_MIN = BigDecimal.valueOf(35);
    private static final BigDecimal FORTUNE_TIER_FOUR_MIN = BigDecimal.valueOf(25);
    private static final BigDecimal FORTUNE_TIER_THREE_MIN = BigDecimal.valueOf(15);
    private static final BigDecimal FORTUNE_TIER_TWO_MIN = BigDecimal.valueOf(10);

    private OcRateTierResolver() {
    }

    /**
     * 解析成功率数值所在档位，展示侧按档位着色。
     *
     * @param passRate         成员在该岗位的成功率记录，无记录时为null
     * @param requiredPassRate 该岗位的帮派要求成功率
     * @return 成功率数值档，无记录为{@link OcRateValueTier#NONE}
     */
    public static OcRateValueTier valueTier(Integer passRate, int requiredPassRate) {
        if (passRate == null) {
            return OcRateValueTier.NONE;
        }
        if (passRate >= requiredPassRate + EXCEED_GAP) {
            return OcRateValueTier.EXCEED;
        }
        if (passRate >= requiredPassRate) {
            return OcRateValueTier.PASS;
        }
        if (requiredPassRate - passRate >= FAIL_FAR_GAP) {
            return OcRateValueTier.FAIL_FAR;
        }
        return OcRateValueTier.FAIL_NEAR;
    }

    /**
     * 生成岗位权重星级文本，权重影响整案成功率。
     *
     * @param priority 岗位权重原始值，可为null
     * @return {@code ⚔️}加实心星，未配置（null或0及以下）返回空串
     */
    public static String successStars(Integer priority) {
        if (priority == null || priority <= 0) {
            return "";
        }
        return SUCCESS_EMOJI + STAR.repeat(OcSlotTierCalculator.priorityTier(priority));
    }

    /**
     * 生成大成功收益星级文本，大成功收益由岗位贡献占比决定。
     *
     * @param bestSuccess 岗位大成功占比，可为null
     * @return {@code 💰}加实心星，未配置（null或0及以下）返回空串
     */
    public static String fortuneStars(BigDecimal bestSuccess) {
        if (bestSuccess == null || bestSuccess.compareTo(BigDecimal.ZERO) <= 0) {
            return "";
        }
        return FORTUNE_EMOJI + STAR.repeat(fortuneTier(bestSuccess));
    }

    /**
     * 解析OC所属级别分组。
     *
     * @param rank   OC级别
     * @param ocName OC名称
     * @return 级别分组：7级及以下为入门，非前置的8级为核心，其余为连锁
     */
    public static OcRateGroup group(int rank, String ocName) {
        if (rank <= ENTRY_MAX_RANK) {
            return OcRateGroup.ENTRY;
        }
        if (rank == CORE_RANK) {
            return CHAIN_PREREQUISITE_OCS.contains(ocName) ? OcRateGroup.CHAIN : OcRateGroup.CORE;
        }
        return OcRateGroup.CHAIN;
    }

    /**
     * 生成级别与分组展示标签。
     *
     * @param rank  OC级别
     * @param group 级别分组
     * @return 形如{@code 9级·连锁}的标签，8级连锁前置单独标注
     */
    public static String groupLabel(int rank, OcRateGroup group) {
        String groupText = switch (group) {
            case ENTRY -> "入门";
            case CORE -> "核心";
            case CHAIN -> rank == CORE_RANK ? "连锁前置" : "连锁";
        };
        return rank + "级·" + groupText;
    }

    /**
     * 解析岗位的帮派要求成功率：帮派覆盖优先，未覆盖时回退全局默认。
     *
     * @param slot         全量岗位设置
     * @param factionSlots 目标帮派的岗位要求覆盖，调用方已按帮派ID过滤
     * @return 该岗位实际生效的要求成功率
     */
    public static int requiredPassRate(TornSettingOcSlotDO slot, List<TornSettingFactionOcSlotDO> factionSlots) {
        return factionSlots.stream()
                .filter(factionSlot -> factionSlot.getRank().equals(slot.getRank()))
                .filter(factionSlot -> factionSlot.getOcName().equals(slot.getOcName()))
                .filter(factionSlot -> factionSlot.getSlotCode().equals(slot.getSlotCode()))
                .findAny()
                .map(TornSettingFactionOcSlotDO::getPassRate)
                .orElse(slot.getPassRate());
    }

    /**
     * 按大成功占比压缩为1~5档。
     *
     * @param bestSuccess 岗位大成功占比，必须大于0
     * @return 收益档位
     */
    private static int fortuneTier(BigDecimal bestSuccess) {
        if (bestSuccess.compareTo(FORTUNE_TIER_FIVE_MIN) >= 0) {
            return TIER_FIVE;
        }
        if (bestSuccess.compareTo(FORTUNE_TIER_FOUR_MIN) >= 0) {
            return 4;
        }
        if (bestSuccess.compareTo(FORTUNE_TIER_THREE_MIN) >= 0) {
            return 3;
        }
        if (bestSuccess.compareTo(FORTUNE_TIER_TWO_MIN) >= 0) {
            return 2;
        }
        return 1;
    }

    /**
     * 成功率数值档，与数值格样式一一对应。
     */
    public enum OcRateValueTier {
        /**
         * 超出帮派要求10及以上。
         */
        EXCEED,
        /**
         * 达到帮派要求。
         */
        PASS,
        /**
         * 低于帮派要求且差距小于10。
         */
        FAIL_NEAR,
        /**
         * 低于帮派要求且差距达到10及以上。
         */
        FAIL_FAR,
        /**
         * 无成功率记录。
         */
        NONE
    }

    /**
     * OC级别分组。
     */
    public enum OcRateGroup {
        /**
         * 入门：7级及以下。
         */
        ENTRY,
        /**
         * 核心：非链式前置的8级OC。
         */
        CORE,
        /**
         * 连锁：8级链式前置与9、10级OC。
         */
        CHAIN
    }
}
