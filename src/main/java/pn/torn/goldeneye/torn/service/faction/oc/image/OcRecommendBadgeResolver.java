package pn.torn.goldeneye.torn.service.faction.oc.image;

import org.springframework.stereotype.Component;
import pn.torn.goldeneye.utils.image.document.TableCellBadgeToneEnum;
import pn.torn.goldeneye.utils.image.document.TableCellContent;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 推荐表格副标题徽章解析器。
 * <p>
 * 只对推荐/分配入口已生成的评分与理由文本做展示映射，不重新计算评分、不复制推荐理由判定；
 * 理由词表与{@code buildRecommendReason}的固定文案精确匹配，未知文案以中性徽章兜底展示。
 *
 * @author Bai
 * @version 1.6.0
 * @since 2026.09.02
 */
@Component
public class OcRecommendBadgeResolver {
    private static final String REASON_SEPARATOR = "、";
    private static final String STOPPED_REASON = "已停转，急需加入";
    private static final String STOP_COUNTDOWN_SUFFIX = "小时内停转";
    private static final String NEW_TEAM_REASON = "新队";
    private static final String EXCELLENT_RATE_REASON = "超高成功率";
    private static final String HIGH_RATE_REASON = "高成功率";
    private static final BigDecimal SCORE_GREEN_THRESHOLD = BigDecimal.valueOf(80);
    private static final BigDecimal SCORE_AMBER_THRESHOLD = BigDecimal.valueOf(60);

    /**
     * 解析推荐评分和推荐理由为副标题徽章：评分徽章在前，理由逐段成徽章。
     *
     * @param recommendScore 推荐度评分，可为空
     * @param reason         推荐理由原文，多段以「、」拼接，可为空
     * @return 徽章列表；评分与理由均无内容时返回空列表
     */
    public List<TableCellContent.Badge> buildBadges(BigDecimal recommendScore, String reason) {
        List<TableCellContent.Badge> badges = new ArrayList<>();
        if (recommendScore != null) {
            badges.add(new TableCellContent.Badge(scoreText(recommendScore), scoreTone(recommendScore)));
        }
        if (reason != null && !reason.isBlank()) {
            for (String fragment : reason.split(REASON_SEPARATOR)) {
                String trimmed = fragment.trim();
                if (!trimmed.isEmpty()) {
                    badges.add(new TableCellContent.Badge(trimmed, reasonTone(trimmed)));
                }
            }
        }
        return badges;
    }

    /**
     * 格式化评分徽章文本，去掉无意义尾零。
     *
     * @param recommendScore 推荐度评分
     * @return 评分徽章文本
     */
    private String scoreText(BigDecimal recommendScore) {
        return "评分 " + recommendScore.stripTrailingZeros().toPlainString();
    }

    /**
     * 按评分分档映射色调：80及以上绿色，60至79琥珀，60以下中性灰。
     *
     * @param recommendScore 推荐度评分
     * @return 评分徽章色调
     */
    private TableCellBadgeToneEnum scoreTone(BigDecimal recommendScore) {
        if (recommendScore.compareTo(SCORE_GREEN_THRESHOLD) >= 0) {
            return TableCellBadgeToneEnum.SUCCESS;
        }
        if (recommendScore.compareTo(SCORE_AMBER_THRESHOLD) >= 0) {
            return TableCellBadgeToneEnum.WARNING;
        }
        return TableCellBadgeToneEnum.NEUTRAL;
    }

    /**
     * 按理由固定词表映射色调；停转紧急为红、停转倒计时为琥珀、新队为蓝，
     * 成功率由高到低为绿、蓝、灰，未知文案以中性灰兜底。
     *
     * @param fragment 理由单段文本
     * @return 理由徽章色调
     */
    private TableCellBadgeToneEnum reasonTone(String fragment) {
        if (STOPPED_REASON.equals(fragment)) {
            return TableCellBadgeToneEnum.DANGER;
        }
        if (fragment.endsWith(STOP_COUNTDOWN_SUFFIX)) {
            return TableCellBadgeToneEnum.WARNING;
        }
        if (NEW_TEAM_REASON.equals(fragment) || HIGH_RATE_REASON.equals(fragment)) {
            return TableCellBadgeToneEnum.INFO;
        }
        if (EXCELLENT_RATE_REASON.equals(fragment)) {
            return TableCellBadgeToneEnum.SUCCESS;
        }
        return TableCellBadgeToneEnum.NEUTRAL;
    }
}
