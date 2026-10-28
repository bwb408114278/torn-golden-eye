package pn.torn.goldeneye.torn.model.faction.crime.recommend;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcSlotDO;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 岗位可填性判定测试：口径为「有要求就必须有实测记录且不低于要求」。
 *
 * <p>成功率矩阵无记录（null）表示该岗位从未实测，按不可填处理；只有岗位未配置成功率要求时
 * 才一律可填。这两条是推荐与分配两个入口共用的准入口径，因此单独断言，不依赖服务层打桩。</p>
 *
 * @author Bai
 * @version 1.9.5
 * @since 2026.10.23
 */
@DisplayName("岗位可填性判定测试")
class OcSlotEligibilityTest {

    @Test
    @DisplayName("成功率低于岗位要求时不可填")
    void passRateBelowRequirement_shouldBeIneligible() {
        assertThat(new OcSlotEligibility(setting(60), 50).eligible()).isFalse();
        assertThat(new OcSlotEligibility(setting(60), 59).eligible()).isFalse();
    }

    @Test
    @DisplayName("成功率刚好等于岗位要求时可填")
    void passRateEqualToRequirement_shouldBeEligible() {
        assertThat(new OcSlotEligibility(setting(60), 60).eligible()).isTrue();
        assertThat(new OcSlotEligibility(setting(60), 80).eligible()).isTrue();
    }

    @Test
    @DisplayName("成功率矩阵无记录时不可填，不得按能力未知放行")
    void missingPassRate_shouldBeIneligible() {
        assertThat(new OcSlotEligibility(setting(60), null).eligible()).isFalse();
        assertThat(new OcSlotEligibility(setting(75), null).eligible()).isFalse();
    }

    @Test
    @DisplayName("岗位未配置成功率要求时一律可填")
    void requirementWithoutPassRate_shouldBeEligible() {
        assertThat(new OcSlotEligibility(setting(null), 10).eligible()).isTrue();
        assertThat(new OcSlotEligibility(setting(null), null).eligible()).isTrue();
    }

    private TornSettingOcSlotDO setting(Integer passRate) {
        TornSettingOcSlotDO setting = new TornSettingOcSlotDO();
        setting.setOcName("Break the Bank");
        setting.setRank(8);
        setting.setSlotCode("Engineer#1");
        setting.setSlotShortCode("Engineer#1");
        setting.setPassRate(passRate);
        return setting;
    }
}
