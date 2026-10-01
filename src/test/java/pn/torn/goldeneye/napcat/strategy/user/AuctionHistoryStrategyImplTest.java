package pn.torn.goldeneye.napcat.strategy.user;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.repository.dao.torn.TornAuctionDAO;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 拍卖历史物品条件构建测试。
 *
 * <p>覆盖套装别名一次查询整套装备的展开行为、单部位别名保持物品全名精确匹配，
 * 以及未登记别名回退到左起字母模糊匹配的原有能力。</p>
 *
 * @author Bai
 * @version 1.6.5
 * @since 2026.09.17
 */
@DisplayName("拍卖历史物品条件构建测试")
class AuctionHistoryStrategyImplTest {

    private AuctionHistoryStrategyImpl strategy;

    @BeforeEach
    void setUp() {
        // 物品条件构建为纯映射逻辑，不访问数据库，DAO 仅用于满足构造依赖
        strategy = new AuctionHistoryStrategyImpl(new TornAuctionDAO());
    }

    @Test
    @DisplayName("套装别名展开为该套装全部部位")
    void buildItemCondition_setAlias_expandsAllParts() {
        assertEquals(List.of("Riot Body", "Riot Boots", "Riot Gloves", "Riot Helmet", "Riot Pants"),
                strategy.buildItemCondition("R套"));

        assertSetAlias("A套", "Assault");
        assertSetAlias("V套", "Vanguard");
        assertSetAlias("先锋套", "Vanguard");
        assertSetAlias("血牛套", "Marauder");
        assertSetAlias("哨兵套", "Sentinel");
    }

    @Test
    @DisplayName("单部位别名仍按物品全名精确匹配")
    void buildItemCondition_partAlias_returnsSingleFullName() {
        assertEquals(List.of("Riot Body"), strategy.buildItemCondition("R甲"));
        assertEquals(List.of("EOD Helmet"), strategy.buildItemCondition("EOD头"));
    }

    @Test
    @DisplayName("未登记别名仍按左起字母模糊匹配多个部位")
    void buildItemCondition_unregisteredPrefix_matchesPartsFuzzily() {
        assertEquals(List.of("EOD Apron", "EOD Boots", "EOD Gloves", "EOD Helmet", "EOD Pants"),
                strategy.buildItemCondition("EOD"));
    }

    /**
     * 断言套装别名展开出的物品全部属于该套装。
     *
     * @param alias   套装别名
     * @param setName 套装基名
     */
    private void assertSetAlias(String alias, String setName) {
        List<String> item = strategy.buildItemCondition(alias);

        assertEquals(5, item.size(), alias + "应展开为该套装全部部位");
        assertTrue(item.stream().allMatch(name -> name.startsWith(setName + " ")),
                alias + "展开结果应全部属于" + setName);
    }
}
