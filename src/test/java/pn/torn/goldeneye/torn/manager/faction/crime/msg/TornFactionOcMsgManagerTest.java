package pn.torn.goldeneye.torn.manager.faction.crime.msg;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pn.torn.goldeneye.base.model.TableDataBO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcSlotDAO;
import pn.torn.goldeneye.repository.dao.setting.SysSettingDAO;
import pn.torn.goldeneye.repository.dao.user.TornUserDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.OcRecommendTableBO;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.OcRecommendationVO;
import pn.torn.goldeneye.utils.image.TableImageUtils;

import java.awt.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * OC推荐表格构建测试 —— 验证推荐高亮与空闲非推荐岗位置灰
 *
 * @author Bai
 * @version 1.5.1
 * @since 2026.08.29
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OC推荐表格构建测试")
class TornFactionOcMsgManagerTest {
    private static final long FACTION_ID = 2095L;
    private static final Color FULL_GREEN = new Color(122, 167, 56);
    private static final Color RECOMMEND_TURQUOISE = new Color(64, 224, 205);
    private static final Color IDLE_GRAY = new Color(191, 191, 191);
    private static final Color EMPTY_ORANGE = new Color(230, 119, 0);

    @Mock
    private TornUserDAO tableUserDao;
    @Mock
    private TornFactionOcDAO ocDao;
    @Mock
    private TornFactionOcSlotDAO slotDao;
    @Mock
    private SysSettingDAO settingDao;

    private TornFactionOcMsgManager msgManager;

    @BeforeEach
    void setUp() {
        msgManager = new TornFactionOcMsgManager(
                new TornFactionOcMsgTableManager(tableUserDao), ocDao, slotDao, settingDao);
    }

    @Test
    @DisplayName("推荐表格：本轮推荐青绿、空闲非推荐灰、有人绿")
    void buildRecommendTableData_shouldColorRecommendedIdleAndFullSlots() {
        TornFactionOcDO oc = buildOc();
        TornFactionOcSlotDO recommendedSlot = buildSlot(oc.getId(), null, "Kidnap");
        TornFactionOcSlotDO idleSlot = buildSlot(oc.getId(), null, "Muscle");
        TornFactionOcSlotDO fullSlot = buildSlot(oc.getId(), 100L, "Blast");
        when(ocDao.queryListByIdList(eq(FACTION_ID), anyList())).thenReturn(List.of(oc));
        when(slotDao.queryListByOc(anyCollection()))
                .thenReturn(new ArrayList<>(List.of(recommendedSlot, idleSlot, fullSlot)));
        when(tableUserDao.queryUserMap(anyCollection())).thenReturn(Map.of());

        TableDataBO tableData = msgManager.buildRecommendTableData("测试标题", FACTION_ID,
                List.of(buildEntry(oc, recommendedSlot)));

        // 排序后列序：满员Blast列1、推荐Kidnap列2、空闲Muscle列3；岗位行=2、成员行=3
        TableImageUtils.TableConfig config = tableData.getTableConfig();
        assertEquals(RECOMMEND_TURQUOISE, config.getCellStyle(2, 2).getBgColor());
        assertEquals(RECOMMEND_TURQUOISE, config.getCellStyle(3, 2).getBgColor());
        assertEquals(IDLE_GRAY, config.getCellStyle(2, 3).getBgColor());
        assertEquals(IDLE_GRAY, config.getCellStyle(3, 3).getBgColor());
        assertEquals(FULL_GREEN, config.getCellStyle(2, 1).getBgColor());
        assertEquals(FULL_GREEN, config.getCellStyle(3, 1).getBgColor());
    }

    @Test
    @DisplayName("推荐表格：同一OC的另一个推荐岗位不置灰，保持空闲橙色")
    void buildRecommendTableData_shouldKeepOtherRecommendedSlotOrange() {
        TornFactionOcDO oc = buildOc();
        TornFactionOcSlotDO firstRecommend = buildSlot(oc.getId(), null, "Kidnap");
        TornFactionOcSlotDO secondRecommend = buildSlot(oc.getId(), null, "Muscle");
        when(ocDao.queryListByIdList(eq(FACTION_ID), anyList())).thenReturn(List.of(oc));
        when(slotDao.queryListByOc(anyCollection()))
                .thenReturn(new ArrayList<>(List.of(firstRecommend, secondRecommend)));
        when(tableUserDao.queryUserMap(anyCollection())).thenReturn(Map.of());

        TableDataBO tableData = msgManager.buildRecommendTableData("测试标题", FACTION_ID,
                List.of(buildEntry(oc, firstRecommend), buildEntry(oc, secondRecommend)));

        // 块1岗位行=2/成员行=3高亮Kidnap列1；块2岗位行=5/成员行=6高亮Muscle列2
        TableImageUtils.TableConfig config = tableData.getTableConfig();
        assertEquals(RECOMMEND_TURQUOISE, config.getCellStyle(2, 1).getBgColor());
        assertEquals(RECOMMEND_TURQUOISE, config.getCellStyle(3, 1).getBgColor());
        assertEquals(RECOMMEND_TURQUOISE, config.getCellStyle(5, 2).getBgColor());
        assertEquals(RECOMMEND_TURQUOISE, config.getCellStyle(6, 2).getBgColor());
        // 另一推荐岗位在本块未被高亮，但属于本轮推荐，保持橙色不置灰
        assertEquals(EMPTY_ORANGE, config.getCellStyle(2, 2).getBgColor());
        assertEquals(EMPTY_ORANGE, config.getCellStyle(3, 2).getBgColor());
        assertEquals(EMPTY_ORANGE, config.getCellStyle(5, 1).getBgColor());
        assertEquals(EMPTY_ORANGE, config.getCellStyle(6, 1).getBgColor());
    }

    private OcRecommendTableBO buildEntry(TornFactionOcDO oc, TornFactionOcSlotDO slot) {
        TornUserDO user = new TornUserDO();
        user.setId(1001L);
        user.setNickname("测试用户");
        return new OcRecommendTableBO(user, new OcRecommendationVO(oc, slot, BigDecimal.ONE, "测试"));
    }

    private TornFactionOcDO buildOc() {
        TornFactionOcDO oc = new TornFactionOcDO();
        oc.setId(1L);
        oc.setFactionId(FACTION_ID);
        oc.setName("Clinical Precision");
        oc.setRank(8);
        oc.setStatus("Recruiting");
        oc.setReadyTime(LocalDateTime.now().plusDays(1));
        return oc;
    }

    private TornFactionOcSlotDO buildSlot(Long ocId, Long userId, String position) {
        TornFactionOcSlotDO slot = new TornFactionOcSlotDO();
        slot.setOcId(ocId);
        slot.setUserId(userId);
        slot.setPosition(position);
        return slot;
    }
}
