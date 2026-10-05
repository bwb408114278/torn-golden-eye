package pn.torn.goldeneye.torn.service.faction.oc.image.rate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionOcSlotDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcSlotDO;
import pn.torn.goldeneye.torn.service.faction.oc.image.rate.OcRateTierResolver.OcRateGroup;
import pn.torn.goldeneye.torn.service.faction.oc.image.rate.OcRateTierResolver.OcRateValueTier;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 用户OC成功率表展示口径解析测试，覆盖数值档、双维星级、级别分组与帮派要求覆盖四类口径边界。
 *
 * @author Bai
 * @version 1.9.0
 * @since 2026.10.05
 */
@DisplayName("用户OC成功率表展示口径解析测试")
class OcRateTierResolverTest {

    @Test
    @DisplayName("数值档按帮派要求判定，超出与差距的临界值归入相邻档")
    void valueTier_boundaries() {
        assertEquals(OcRateValueTier.EXCEED, OcRateTierResolver.valueTier(70, 60));
        assertEquals(OcRateValueTier.PASS, OcRateTierResolver.valueTier(69, 60));
        assertEquals(OcRateValueTier.PASS, OcRateTierResolver.valueTier(60, 60));
        assertEquals(OcRateValueTier.FAIL_NEAR, OcRateTierResolver.valueTier(59, 60));
        assertEquals(OcRateValueTier.FAIL_NEAR, OcRateTierResolver.valueTier(51, 60));
        assertEquals(OcRateValueTier.FAIL_FAR, OcRateTierResolver.valueTier(50, 60));
        assertEquals(OcRateValueTier.NONE, OcRateTierResolver.valueTier(null, 60));
    }

    @Test
    @DisplayName("双维星级按各自档位边界生成文本，未配置返回空串")
    void stars_tierBoundaries() {
        assertEquals("⚔️★★★★★", OcRateTierResolver.successStars(25));
        assertEquals("⚔️★★★★", OcRateTierResolver.successStars(24));
        assertEquals("⚔️★★★★", OcRateTierResolver.successStars(20));
        assertEquals("⚔️★★★", OcRateTierResolver.successStars(19));
        assertEquals("⚔️★★★", OcRateTierResolver.successStars(15));
        assertEquals("⚔️★★", OcRateTierResolver.successStars(14));
        assertEquals("⚔️★★", OcRateTierResolver.successStars(10));
        assertEquals("⚔️★", OcRateTierResolver.successStars(9));
        assertEquals("⚔️★", OcRateTierResolver.successStars(1));
        assertEquals("", OcRateTierResolver.successStars(0));
        assertEquals("", OcRateTierResolver.successStars(null));

        assertEquals("💰★★★★★", OcRateTierResolver.fortuneStars(BigDecimal.valueOf(35)));
        assertEquals("💰★★★★", OcRateTierResolver.fortuneStars(BigDecimal.valueOf(34)));
        assertEquals("💰★★★★", OcRateTierResolver.fortuneStars(BigDecimal.valueOf(25)));
        assertEquals("💰★★★", OcRateTierResolver.fortuneStars(BigDecimal.valueOf(24)));
        assertEquals("💰★★★", OcRateTierResolver.fortuneStars(BigDecimal.valueOf(15)));
        assertEquals("💰★★", OcRateTierResolver.fortuneStars(BigDecimal.valueOf(14)));
        assertEquals("💰★★", OcRateTierResolver.fortuneStars(BigDecimal.valueOf(10)));
        assertEquals("💰★", OcRateTierResolver.fortuneStars(BigDecimal.valueOf(9)));
        assertEquals("💰★", OcRateTierResolver.fortuneStars(BigDecimal.ONE));
        assertEquals("", OcRateTierResolver.fortuneStars(BigDecimal.ZERO));
        assertEquals("", OcRateTierResolver.fortuneStars(null));
    }

    @Test
    @DisplayName("级别分组由级别与链式前置名单共同决定")
    void group_byRankAndChainPrerequisite() {
        assertEquals(OcRateGroup.ENTRY, OcRateTierResolver.group(7, "Blast from the Past"));
        assertEquals(OcRateGroup.CORE, OcRateTierResolver.group(8, "Break the Bank"));
        assertEquals(OcRateGroup.CHAIN, OcRateTierResolver.group(8, "Stacking the Deck"));
        assertEquals(OcRateGroup.CHAIN, OcRateTierResolver.group(8, "Lock Stock"));
        assertEquals(OcRateGroup.CHAIN, OcRateTierResolver.group(8, "Manifest Cruelty"));
        assertEquals(OcRateGroup.CHAIN, OcRateTierResolver.group(9, "Ace in the Hole"));
        assertEquals(OcRateGroup.CHAIN, OcRateTierResolver.group(10, "Crane Reaction"));

        assertEquals("7级·入门", OcRateTierResolver.groupLabel(7, OcRateGroup.ENTRY));
        assertEquals("8级·核心", OcRateTierResolver.groupLabel(8, OcRateGroup.CORE));
        assertEquals("8级·连锁前置", OcRateTierResolver.groupLabel(8, OcRateGroup.CHAIN));
        assertEquals("9级·连锁", OcRateTierResolver.groupLabel(9, OcRateGroup.CHAIN));
        assertEquals("10级·连锁", OcRateTierResolver.groupLabel(10, OcRateGroup.CHAIN));
    }

    @Test
    @DisplayName("帮派岗位要求覆盖优先于全局默认，未命中按级别与岗位编码回退")
    void requiredPassRate_factionOverrideFirst() {
        TornSettingOcSlotDO slot = slot(7, "Blast from the Past", "Picklock#1", 55);
        TornSettingFactionOcSlotDO override = factionSlot(7, "Blast from the Past", "Picklock#1", 65);
        TornSettingFactionOcSlotDO otherOc = factionSlot(7, "Window of Opportunity", "Picklock#1", 70);
        TornSettingFactionOcSlotDO otherSlot = factionSlot(7, "Blast from the Past", "Bomber#1", 75);

        assertEquals(65, OcRateTierResolver.requiredPassRate(slot, List.of(otherOc, otherSlot, override)));
        assertEquals(55, OcRateTierResolver.requiredPassRate(slot, List.of(otherOc, otherSlot)));
        assertEquals(55, OcRateTierResolver.requiredPassRate(slot, List.of()));
    }

    private TornSettingOcSlotDO slot(int rank, String ocName, String slotCode, int passRate) {
        TornSettingOcSlotDO slot = new TornSettingOcSlotDO();
        slot.setRank(rank);
        slot.setOcName(ocName);
        slot.setSlotCode(slotCode);
        slot.setPassRate(passRate);
        return slot;
    }

    private TornSettingFactionOcSlotDO factionSlot(int rank, String ocName, String slotCode, int passRate) {
        TornSettingFactionOcSlotDO slot = new TornSettingFactionOcSlotDO();
        slot.setRank(rank);
        slot.setOcName(ocName);
        slot.setSlotCode(slotCode);
        slot.setPassRate(passRate);
        return slot;
    }
}
