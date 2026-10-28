package pn.torn.goldeneye.torn.service.faction.oc.image;

import org.springframework.stereotype.Component;
import pn.torn.goldeneye.utils.image.document.TableCellBadgeToneEnum;
import pn.torn.goldeneye.utils.image.document.TableCellContent;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 推荐表格副标题徽章解析器。
 * <p>
 * 只对推荐/分配入口已生成的评分与理由文本做展示映射，不重新计算评分、不复制推荐理由判定；
 * 理由词表与{@code buildRecommendReason}的固定文案精确匹配，未知文案以中性徽章兜底展示。
 * 评分一律以百分制整数展示，任何金额（元/人天）都不出现在图面上。
 *
 * @author Bai
 * @version 1.9.4
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
    /**
     * 评分徽章文本前缀：对外只展示百分制评分，不展示金额。
     */
    private static final String SCORE_PREFIX = "评分 ";
    /**
     * 评分展示的小数位数，百分制评分固定为整数。
     */
    private static final int SCORE_SCALE = 0;
    /**
     * 评分绿色门槛：60分及以上说明这支队在经济性与紧迫度上都值得优先加入。
     */
    private static final BigDecimal SCORE_GREEN_THRESHOLD = new BigDecimal("60");
    /**
     * 评分信息蓝门槛：35分至60分为次优档。
     */
    private static final BigDecimal SCORE_INFO_THRESHOLD = new BigDecimal("35");

    /**
     * 解析推荐评分和推荐理由为副标题徽章：评分徽章在前，理由逐段成徽章。
     *
     * @param recommendScore 百分制推荐评分，取值1至100，可为空
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
     * 格式化评分徽章文本：百分制评分以整数展示，不带单位。
     *
     * @param recommendScore 百分制推荐评分
     * @return 评分徽章文本
     */
    private String scoreText(BigDecimal recommendScore) {
        return SCORE_PREFIX + recommendScore.setScale(SCORE_SCALE, RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * 按百分制评分分档映射色调：60分及以上绿色，35分至60分信息蓝，35分以下中性灰。
     *
     * @param recommendScore 百分制推荐评分
     * @return 评分徽章色调
     */
    private TableCellBadgeToneEnum scoreTone(BigDecimal recommendScore) {
        if (recommendScore.compareTo(SCORE_GREEN_THRESHOLD) >= 0) {
            return TableCellBadgeToneEnum.SUCCESS;
        }
        if (recommendScore.compareTo(SCORE_INFO_THRESHOLD) >= 0) {
            return TableCellBadgeToneEnum.INFO;
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
