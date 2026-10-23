package pn.torn.goldeneye.torn.service.faction.oc.image;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.utils.image.document.TableCellBadgeToneEnum;
import pn.torn.goldeneye.utils.image.document.TableCellContent;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 推荐副标题徽章解析测试，聚焦评分分档、理由词表和未知兜底。
 *
 * @author Bai
 * @version 1.9.3
 * @since 2026.09.02
 */
@DisplayName("推荐副标题徽章解析测试")
class OcRecommendBadgeResolverTest {
    private final OcRecommendBadgeResolver resolver = new OcRecommendBadgeResolver();

    @Test
    @DisplayName("评分按300/150分档映射绿/琥珀/灰并去掉尾零")
    void buildBadges_shouldMapScoreBands() {
        assertEquals(TableCellBadgeToneEnum.SUCCESS,
                toneOf(resolver.buildBadges(new BigDecimal("340.50"), null).getFirst()));
        assertEquals("评分 340.5", resolver.buildBadges(new BigDecimal("340.50"), null).getFirst().text());
        assertEquals(TableCellBadgeToneEnum.SUCCESS,
                toneOf(resolver.buildBadges(BigDecimal.valueOf(300), null).getFirst()));
        assertEquals(TableCellBadgeToneEnum.WARNING,
                toneOf(resolver.buildBadges(new BigDecimal("299.99"), null).getFirst()));
        assertEquals(TableCellBadgeToneEnum.WARNING,
                toneOf(resolver.buildBadges(BigDecimal.valueOf(150), null).getFirst()));
        assertEquals(TableCellBadgeToneEnum.NEUTRAL,
                toneOf(resolver.buildBadges(new BigDecimal("149.99"), null).getFirst()));
    }

    @Test
    @DisplayName("理由按固定词表逐段映射色调，未知文案中性灰兜底")
    void buildBadges_shouldMapReasonVocabulary() {
        List<TableCellContent.Badge> badges = resolver.buildBadges(null,
                "已停转，急需加入、超高成功率");

        assertEquals(TableCellBadgeToneEnum.DANGER, toneOf(badges.get(0)));
        assertEquals(TableCellBadgeToneEnum.SUCCESS, toneOf(badges.get(1)));

        badges = resolver.buildBadges(null, "12小时内停转、高成功率");
        assertEquals(TableCellBadgeToneEnum.WARNING, toneOf(badges.get(0)));
        assertEquals(TableCellBadgeToneEnum.INFO, toneOf(badges.get(1)));

        badges = resolver.buildBadges(null, "新队、成功率达标");
        assertEquals(TableCellBadgeToneEnum.INFO, toneOf(badges.get(0)));
        assertEquals(TableCellBadgeToneEnum.NEUTRAL, toneOf(badges.get(1)));

        badges = resolver.buildBadges(null, "临时新增理由");
        assertEquals(TableCellBadgeToneEnum.NEUTRAL, toneOf(badges.getFirst()));
    }

    @Test
    @DisplayName("评分在前理由在后，缺失部分不产生徽章")
    void buildBadges_shouldOrderBadgesAndSkipMissingParts() {
        List<TableCellContent.Badge> badges = resolver.buildBadges(BigDecimal.ONE, "  ");
        assertEquals(List.of(new TableCellContent.Badge("评分 1", TableCellBadgeToneEnum.NEUTRAL)), badges);

        assertEquals(List.of(), resolver.buildBadges(null, null));
        assertEquals(List.of(), resolver.buildBadges(null, " "));
    }

    private TableCellBadgeToneEnum toneOf(TableCellContent.Badge badge) {
        return badge.badgeTone();
    }
}
