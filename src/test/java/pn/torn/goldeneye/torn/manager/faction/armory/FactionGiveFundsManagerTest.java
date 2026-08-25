package pn.torn.goldeneye.torn.manager.faction.armory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pn.torn.goldeneye.base.torn.TornApi;
import pn.torn.goldeneye.repository.dao.faction.funds.TornFactionGiveFundsDAO;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionDO;
import pn.torn.goldeneye.torn.model.faction.news.TornFactionNewsListVO;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 帮派取钱记录管理器测试。
 *
 * @author Bai
 * @version 1.4.5
 * @since 2026.08.25
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("帮派取钱记录管理器测试")
class FactionGiveFundsManagerTest {
    @Mock
    private TornApi tornApi;
    @Mock
    private TornFactionGiveFundsDAO giveFundsDao;
    @InjectMocks
    private FactionGiveFundsManager manager;

    @Test
    @DisplayName("Torn API返回空响应时返回失败且不保存数据")
    void spiderGiveFundsData_whenApiResponseIsNull_shouldReturnFalse() {
        TornSettingFactionDO faction = new TornSettingFactionDO();
        faction.setId(1L);
        when(tornApi.sendRequest(eq(1L), any(), eq(TornFactionNewsListVO.class))).thenReturn(null);

        boolean result = manager.spiderGiveFundsData(faction,
                LocalDateTime.now().minusHours(1), LocalDateTime.now());

        assertFalse(result);
        verify(giveFundsDao, never()).saveBatch(any());
    }
}
