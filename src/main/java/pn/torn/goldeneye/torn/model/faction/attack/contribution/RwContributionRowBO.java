package pn.torn.goldeneye.torn.model.faction.attack.contribution;

import java.math.BigDecimal;
import java.util.Map;

/**
 * RW贡献榜单行展示模型
 *
 * @param userId     成员Torn用户ID
 * @param nickname   结算时的昵称快照
 * @param totalScore 全部合格场次得分之和，保留一位小数
 * @param warCount   实际上榜场次数，用于总分并列时排序
 * @param rankByRwId 各场战神榜名次，键为RW ID；缺失表示该场未上榜
 * @author Bai
 * @version 1.6.4
 * @since 2026.09.16
 */
public record RwContributionRowBO(
        long userId,
        String nickname,
        BigDecimal totalScore,
        int warCount,
        Map<Long, Integer> rankByRwId) {
}
