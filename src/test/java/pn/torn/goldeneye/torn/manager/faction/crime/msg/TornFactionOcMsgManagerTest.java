package pn.torn.goldeneye.torn.manager.faction.crime.msg;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pn.torn.goldeneye.constants.torn.SettingConstants;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcSlotDAO;
import pn.torn.goldeneye.repository.dao.setting.SysSettingDAO;
import pn.torn.goldeneye.repository.dao.user.TornUserDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.OcRecommendTableBO;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.OcRecommendationVO;
import pn.torn.goldeneye.torn.service.faction.oc.image.OcImageStatusResolver;
import pn.torn.goldeneye.torn.service.faction.oc.image.OcImageTitleFormatter;
import pn.torn.goldeneye.torn.service.faction.oc.image.OcTableDocumentAssembler;
import pn.torn.goldeneye.utils.image.document.TableCell;
import pn.torn.goldeneye.utils.image.document.TableCellStyleEnum;
import pn.torn.goldeneye.utils.image.document.TableDocument;
import pn.torn.goldeneye.utils.image.render.TableImageRenderer;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OC消息公共入口测试，验证文档委托和固定当前时间传递。
 *
 * @author Bai
 * @version 1.6.0
 * @since 2026.08.31
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OC消息公共入口测试")
class TornFactionOcMsgManagerTest {
    private static final long FACTION_ID = 2095L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 31, 12, 0);

    @Mock
    private TornFactionOcDAO ocDao;
    @Mock
    private TornFactionOcSlotDAO slotDao;
    @Mock
    private TornUserDAO userDao;
    @Mock
    private SysSettingDAO settingDao;
    @Mock
    private TableImageRenderer imageRenderer;

    private TornFactionOcMsgManager msgManager;
    private OcTableDocumentAssembler assembler;

    @BeforeEach
    void setUp() {
        assembler = new OcTableDocumentAssembler(new OcImageStatusResolver(), new OcImageTitleFormatter());
        msgManager = new TornFactionOcMsgManager(ocDao, slotDao, userDao, settingDao, assembler, imageRenderer);
    }

    @Test
    @DisplayName("当前OC表格：标题和团队状态使用固定now，成员只显示唯一Emoji")
    void buildOcTableData_shouldUseFixedNowAndUniqueMemberEmoji() {
        TornFactionOcDO oc = buildOc("Recruiting", NOW.plusHours(1));
        TornFactionOcSlotDO idle = buildSlot(oc.getId(), 10L, "Kidnap", BigDecimal.ZERO);
        TornFactionOcSlotDO preparing = buildSlot(oc.getId(), 11L, "Muscle", BigDecimal.valueOf(50));
        TornFactionOcSlotDO ready = buildSlot(oc.getId(), 12L, "Blast", BigDecimal.valueOf(100));
        TornFactionOcSlotDO missing = buildSlot(oc.getId(), 13L, "Hacker", BigDecimal.valueOf(50));
        missing.setRequiredItemId(100);
        missing.setRequiredItemAvailable(false);
        TornFactionOcSlotDO empty = buildSlot(oc.getId(), null, "Rob", BigDecimal.ZERO);
        when(slotDao.queryListByOc(List.of(oc))).thenReturn(
                new ArrayList<>(List.of(empty, missing, ready, preparing, idle)));
        when(userDao.queryUserMap(anyCollection())).thenReturn(Map.of(
                10L, buildUser(10L), 11L, buildUser(11L), 12L, buildUser(12L), 13L, buildUser(13L)));
        when(settingDao.querySettingValue(eq(SettingConstants.KEY_OC_LOAD))).thenReturn("2026-08-31 11:00:00");

        TableDocument document = msgManager.buildOcTableData("OC查询", List.of(oc), NOW);

        assertEquals("OC查询", document.title());
        assertEquals(5, document.rows().size());
        assertEquals(6, document.rows().get(0).cells().get(0).colSpan());
        assertEquals(TableCellStyleEnum.SECTION, document.rows().get(1).cells().get(0).style());
        assertTrue(document.rows().get(1).cells().get(0).text().contains("1小时")
                && document.rows().get(1).cells().get(0).text().contains("后停转"));
        assertEquals(TableCellStyleEnum.TEAM_READY, document.rows().get(2).cells().get(0).style());
        assertEquals("测试用户[12] ✅", document.rows().get(3).cells().get(0).text());
        assertEquals("测试用户[13] ⚠️", document.rows().get(3).cells().get(1).text());
        assertEquals("测试用户[10] 💤", document.rows().get(3).cells().get(2).text());
        assertEquals("测试用户[11] ⏳", document.rows().get(3).cells().get(3).text());
        assertEquals("空缺", document.rows().get(3).cells().get(4).text());
        assertEquals(TableCellStyleEnum.FOOTER, document.rows().get(4).cells().get(0).style());
    }

    @Test
    @DisplayName("推荐表格：推荐空槽高亮，非推荐空槽置灰，已有人保持填充样式")
    void buildRecommendTableData_shouldAssembleRecommendationStyles() {
        TornFactionOcDO oc = buildOc("Planning", NOW.plusHours(1));
        TornFactionOcSlotDO recommended = buildSlot(oc.getId(), null, "Kidnap", BigDecimal.ZERO);
        TornFactionOcSlotDO idle = buildSlot(oc.getId(), null, "Muscle", BigDecimal.ZERO);
        TornFactionOcSlotDO filled = buildSlot(oc.getId(), 10L, "Blast", BigDecimal.valueOf(50));
        when(ocDao.queryListByIdList(FACTION_ID, List.of(oc.getId()))).thenReturn(List.of(oc));
        when(slotDao.queryListByOc(List.of(oc))).thenReturn(
                new ArrayList<>(List.of(recommended, idle, filled)));
        when(userDao.queryUserMap(anyCollection())).thenReturn(Map.of(10L, buildUser(10L)));
        OcRecommendTableBO entry = new OcRecommendTableBO(buildUser(20L),
                new OcRecommendationVO(oc, recommended, BigDecimal.ONE, "测试"));

        TableDocument document = msgManager.buildRecommendTableData("推荐", FACTION_ID, List.of(entry), NOW);

        List<TableCell> positions = document.rows().get(2).cells();
        assertEquals(TableCellStyleEnum.SLOT_RECOMMENDED, positions.get(2).style());
        assertEquals(TableCellStyleEnum.SLOT_IDLE, positions.get(3).style());
        assertEquals(TableCellStyleEnum.SLOT_FILLED, positions.get(1).style());
        verify(ocDao).queryListByIdList(FACTION_ID, List.of(oc.getId()));
    }

    @Test
    @DisplayName("固定now跨过准备时间时标题和团队颜色同时切换")
    void buildOcTableData_shouldUseSameNowForTitleAndTeamStyle() {
        TornFactionOcDO oc = buildOc("Recruiting", NOW.plusSeconds(1));
        TornFactionOcSlotDO slot = buildSlot(oc.getId(), 10L, "Kidnap", BigDecimal.valueOf(50));
        when(slotDao.queryListByOc(List.of(oc))).thenReturn(List.of(slot));
        when(userDao.queryUserMap(anyCollection())).thenReturn(Map.of(10L, buildUser(10L)));
        when(settingDao.querySettingValue(eq(SettingConstants.KEY_OC_LOAD))).thenReturn("刷新时间");

        TableDocument document = msgManager.buildOcTableData("OC查询", List.of(oc), NOW.plusSeconds(2));

        assertEquals("Clinical Precision 已停转", document.rows().get(1).cells().getFirst().text());
        assertEquals(TableCellStyleEnum.TEAM_WARNING, document.rows().get(2).cells().getFirst().style());
    }

    private TornFactionOcDO buildOc(String status, LocalDateTime readyTime) {
        TornFactionOcDO oc = new TornFactionOcDO();
        oc.setId(1L);
        oc.setFactionId(FACTION_ID);
        oc.setName("Clinical Precision");
        oc.setRank(8);
        oc.setStatus(status);
        oc.setReadyTime(readyTime);
        return oc;
    }

    private TornFactionOcSlotDO buildSlot(Long ocId, Long userId, String position, BigDecimal progress) {
        TornFactionOcSlotDO slot = new TornFactionOcSlotDO();
        slot.setOcId(ocId);
        slot.setUserId(userId);
        slot.setPosition(position);
        slot.setProgress(progress);
        return slot;
    }

    private TornUserDO buildUser(long id) {
        TornUserDO user = new TornUserDO();
        user.setId(id);
        user.setNickname("测试用户");
        return user;
    }
}
