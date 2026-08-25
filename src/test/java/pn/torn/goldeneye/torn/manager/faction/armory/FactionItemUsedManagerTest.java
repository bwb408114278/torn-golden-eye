package pn.torn.goldeneye.torn.manager.faction.armory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pn.torn.goldeneye.base.bot.Bot;
import pn.torn.goldeneye.base.torn.TornApi;
import pn.torn.goldeneye.repository.dao.faction.armory.TornFactionItemUsedDAO;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionDO;
import pn.torn.goldeneye.torn.manager.torn.TornItemsManager;
import pn.torn.goldeneye.torn.manager.user.TornUserManager;
import pn.torn.goldeneye.torn.model.faction.news.TornFactionNewsListVO;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 帮派物品使用记录管理器测试。
 *
 * @author Bai
 * @version 1.4.5
 * @since 2026.08.25
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("帮派物品使用记录管理器测试")
class FactionItemUsedManagerTest {
    @Mock
    private Bot bot;
    @Mock
    private TornApi tornApi;
    @Mock
    private TornItemsManager itemsManager;
    @Mock
    private TornUserManager userManager;
    @Mock
    private TornFactionItemUsedDAO usedDao;
    @InjectMocks
    private FactionItemUsedManager manager;

    @Test
    @DisplayName("Torn API返回空响应时返回失败且不保存数据")
    void spiderItemUseData_whenApiResponseIsNull_shouldReturnFalse() {
        TornSettingFactionDO faction = faction();
        when(tornApi.sendRequest(eq(1L), any(), eq(TornFactionNewsListVO.class))).thenReturn(null);

        boolean result = manager.spiderItemUseData(faction, LocalDateTime.now().minusHours(1), LocalDateTime.now());

        assertFalse(result);
        verify(usedDao, never()).saveBatch(any());
        verify(bot, never()).sendRequest(any(), any());
    }

    @Test
    @DisplayName("Torn API返回有效空新闻时视为成功")
    void spiderItemUseData_whenApiResponseHasEmptyNews_shouldReturnTrue() {
        TornSettingFactionDO faction = faction();
        TornFactionNewsListVO response = new TornFactionNewsListVO();
        response.setNews(List.of());
        when(tornApi.sendRequest(eq(1L), any(), eq(TornFactionNewsListVO.class))).thenReturn(response);

        boolean result = manager.spiderItemUseData(faction, LocalDateTime.now().minusHours(1), LocalDateTime.now());

        assertTrue(result);
    }

    private TornSettingFactionDO faction() {
        TornSettingFactionDO faction = new TornSettingFactionDO();
        faction.setId(1L);
        faction.setGroupId(0L);
        return faction;
    }
}
