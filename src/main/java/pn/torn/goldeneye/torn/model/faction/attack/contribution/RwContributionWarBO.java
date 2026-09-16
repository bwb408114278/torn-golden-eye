package pn.torn.goldeneye.torn.model.faction.attack.contribution;

import java.math.BigDecimal;

/**
 * RW贡献榜单场战争展示模型
 *
 * @param rwId              RW ID
 * @param opponentShortName 对手帮派简称；为空时展示层回退对手全名
 * @param opponentName      对手帮派全名，简称缺失时的回退展示值
 * @param opponentScore     对手最终战争分，决定本场得分系数
 * @param coefficient       对手得分对应的得分系数
 * @param settled           该场是否已有名次结算行；缺失时展示“未结算”
 * @author Bai
 * @version 1.6.4
 * @since 2026.09.16
 */
public record RwContributionWarBO(
        long rwId,
        String opponentShortName,
        String opponentName,
        int opponentScore,
        BigDecimal coefficient,
        boolean settled) {
}
