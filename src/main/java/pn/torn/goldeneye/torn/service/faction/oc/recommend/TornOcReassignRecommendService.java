package pn.torn.goldeneye.torn.service.faction.oc.recommend;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcSlotDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcUserDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcUserDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcSlotDO;
import pn.torn.goldeneye.torn.manager.setting.TornSettingOcCoefficientManager;
import pn.torn.goldeneye.torn.manager.setting.TornSettingOcSlotManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * OC队伍大锅饭推荐逻辑层
 *
 * @author Bai
 * @version 0.3.0
 * @since 2025.11.01
 */
@Slf4j
@Service
public class TornOcReassignRecommendService extends TornOcRecommendService {
    private final TornSettingOcCoefficientManager coefficientManager;

    public TornOcReassignRecommendService(TornSettingOcSlotManager settingOcSlotManager, TornFactionOcDAO ocDao,
                                          TornFactionOcSlotDAO ocSlotDao, TornFactionOcUserDAO ocUserDao,
                                          TornSettingOcCoefficientManager coefficientManager) {
        super(settingOcSlotManager, ocDao, ocSlotDao, ocUserDao);
        this.coefficientManager = coefficientManager;
    }

    @Override
    protected BigDecimal calculateRecommendScore(TornFactionOcDO oc, TornSettingOcSlotDO slotSetting,
                                                 TornFactionOcUserDO userPassRate) {
        // 1. 停转时间评分（权重80%）
        BigDecimal timeScore = super.calculateTimeScore(oc.getReadyTime());
        // 2. 成功率评分 - 归一化处理，限制在0-100范围内
        BigDecimal coefficient = coefficientManager.getCoefficient(oc.getName(), oc.getRank(),
                slotSetting.getSlotCode(), userPassRate.getPassRate());

        // 计算原始成功率评分：成功率 × 系数/25（归一化到100分制）
        BigDecimal passRateScore = BigDecimal.valueOf(userPassRate.getPassRate())
                .multiply(coefficient)
                .divide(BigDecimal.valueOf(25), 2, RoundingMode.HALF_UP)
                .min(BigDecimal.valueOf(100));

        // 3. 难度奖励
        BigDecimal difficultyBonus = BigDecimal.valueOf(oc.getRank().equals(8) ? 100 : 0);
        // 4. 加权计算：时间80% + 成功率15% + 难度奖励5%
        return timeScore.multiply(BigDecimal.valueOf(0.80))
                .add(passRateScore.multiply(BigDecimal.valueOf(0.15)))
                .add(difficultyBonus.multiply(BigDecimal.valueOf(0.05)))
                .setScale(2, RoundingMode.HALF_UP);
    }
}