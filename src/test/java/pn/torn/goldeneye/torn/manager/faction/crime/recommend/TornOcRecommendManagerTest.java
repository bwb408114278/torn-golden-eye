package pn.torn.goldeneye.torn.manager.faction.crime.recommend;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcSlotDO;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OC推荐管理器集成测试，验证禁用过滤与岗位要求查询职责分离。
 */
@Slf4j
@SpringBootTest
@DisplayName("OC推荐集成测试")
class TornOcRecommendManagerTest {
    @Autowired
    private TornOcRecommendManager recommendManager;
    // ── 帮派常量 ──────────────────────────────────────────────
    private static final int FACTION_HP = 2095;
    private static final int FACTION_BSU = 11796;
    private static final int FACTION_CCRC = 27902;

    @Test
    @DisplayName("查询禁用OC，返回Null")
    void findSlotSetting_disabledOc_returnsNull() {
        TornFactionOcDO oc = oc("No Reserve", 5);
        TornFactionOcSlotDO slot = slot("Engineer#1");

        TornSettingOcSlotDO result = recommendManager.findSlotSetting(FACTION_BSU, oc, slot);
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("禁用OC仍可查询岗位要求")
    void findSlotRequirement_disabledOc_returnsRequirement() {
        TornFactionOcDO oc = oc("No Reserve", 5);
        TornFactionOcSlotDO slot = slot("Engineer#1");

        TornSettingOcSlotDO result = recommendManager.findSlotRequirement(FACTION_BSU, oc, slot);

        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("查询未禁用OC，正常返回")
    void findSlotSetting_notDisabled_returnsGlobalSetting() {
        TornFactionOcDO oc = oc("No Reserve", 5);
        TornFactionOcSlotDO slot = slot("Engineer#1");

        TornSettingOcSlotDO result = recommendManager.findSlotSetting(FACTION_CCRC, oc, slot);
        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("自定义岗位成功率，覆盖返回")
    void findSlotSetting_factionOverride_returnsCustomPassRate() {
        TornFactionOcDO oc = oc("Window of Opportunity", 7);
        TornFactionOcSlotDO slot = slot("Looter#1");

        TornSettingOcSlotDO result = recommendManager.findSlotSetting(FACTION_BSU, oc, slot);
        assertThat(result).isNotNull();
        assertThat(result.getPassRate()).isEqualTo(60);
    }

    @Test
    @DisplayName("无自定义岗位成功率，正常返回")
    void findSlotSetting_noOverride_returnsGlobalPassRate() {
        TornFactionOcDO oc = oc("Blast from the Past", 7);
        TornFactionOcSlotDO slot = slot("Picklock#2");

        TornSettingOcSlotDO globalResult = recommendManager.findSlotSetting(FACTION_HP, oc, slot);
        TornSettingOcSlotDO overrideResult = recommendManager.findSlotSetting(FACTION_CCRC, oc, slot);

        assertThat(globalResult).isNotNull();
        assertThat(overrideResult).isNotNull();
        assertThat(overrideResult.getPassRate()).isNotEqualTo(globalResult.getPassRate());
    }

    // ── 工具方法 ──────────────────────────────────────────────
    private TornFactionOcDO oc(String name, int rank) {
        TornFactionOcDO oc = new TornFactionOcDO();
        oc.setName(name);
        oc.setRank(rank);
        oc.setReadyTime(LocalDateTime.now().plusHours(10));
        return oc;
    }

    private TornFactionOcSlotDO slot(String position) {
        TornFactionOcSlotDO s = new TornFactionOcSlotDO();
        s.setPosition(position);
        return s;
    }
}
