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
import pn.torn.goldeneye.torn.service.faction.oc.image.OcImageStatusResolver;
import pn.torn.goldeneye.torn.service.faction.oc.image.OcImageTitleFormatter;
import pn.torn.goldeneye.utils.image.TableImageUtils;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * OC推荐表格构建测试 —— 验证推荐高亮与空闲非推荐岗位置灰
 *
 * @author Bai
 * @version 1.5.2
 * @since 2026.08.29
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OC推荐表格构建测试")
class TornFactionOcMsgManagerTest {
    private static final long FACTION_ID = 2095L;
    private static final Color FULL_GREEN = new Color(122, 167, 56);
    private static final Color RECOMMEND_TURQUOISE = new Color(64, 224, 205);
    private static final Color IDLE_GRAY = new Color(242, 242, 242);

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
                new TornFactionOcMsgTableManager(tableUserDao), ocDao, slotDao, settingDao,
                new OcImageStatusResolver(), new OcImageTitleFormatter());
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
    @DisplayName("推荐表格：同一OC的其他推荐岗位在本块置灰，仅本块推荐岗位高亮")
    void buildRecommendTableData_shouldGrayOtherRecommendedSlotOfSameOc() {
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
        // 另一推荐岗位属于其他块，在本块不是推荐结果，同样置灰
        assertEquals(IDLE_GRAY, config.getCellStyle(2, 2).getBgColor());
        assertEquals(IDLE_GRAY, config.getCellStyle(3, 2).getBgColor());
        assertEquals(IDLE_GRAY, config.getCellStyle(5, 1).getBgColor());
        assertEquals(IDLE_GRAY, config.getCellStyle(6, 1).getBgColor());
    }

    @Test
    @DisplayName("公共接入：标题只有一种时间文案，已加入成员恰好一个状态Emoji，空槽无Emoji")
    void enrichCurrentOcTable_shouldAddOneTimeTextAndOneEmojiPerMember() {
        TornFactionOcDO oc = buildOc();
        oc.setReadyTime(LocalDateTime.now().plusHours(2).plusMinutes(30));
        TornFactionOcSlotDO idle = buildSlot(oc.getId(), 10L, "Idle");
        idle.setProgress(BigDecimal.ZERO);
        TornFactionOcSlotDO preparing = buildSlot(oc.getId(), 11L, "Preparing");
        preparing.setProgress(BigDecimal.valueOf(50));
        TornFactionOcSlotDO ready = buildSlot(oc.getId(), 12L, "Ready");
        ready.setProgress(BigDecimal.valueOf(100));
        TornFactionOcSlotDO missing = buildSlot(oc.getId(), 13L, "Missing");
        missing.setProgress(BigDecimal.valueOf(50));
        missing.setRequiredItemAvailable(false);
        TornFactionOcSlotDO empty = buildSlot(oc.getId(), null, "Empty");
        empty.setProgress(BigDecimal.ZERO);
        when(ocDao.queryListByIdList(eq(FACTION_ID), anyList())).thenReturn(List.of(oc));
        when(slotDao.queryListByOc(anyCollection()))
                .thenReturn(new ArrayList<>(List.of(idle, preparing, ready, missing, empty)));
        when(tableUserDao.queryUserMap(anyCollection())).thenReturn(Map.of(
                10L, user(10L), 11L, user(11L), 12L, user(12L), 13L, user(13L)));

        TableDataBO tableData = msgManager.buildRecommendTableData("测试标题", FACTION_ID,
                List.of(buildEntry(oc, idle)));

        String ocTitle = tableData.getTableData().get(1).getFirst();
        assertTrue(ocTitle.contains("后停转"));
        assertFalse(ocTitle.contains("还需空转"));
        assertFalse(ocTitle.contains("预计"));
        assertFalse(ocTitle.contains("已停转"));

        List<String> memberRow = tableData.getTableData().get(3);
        long occupiedCellEmojiCount = memberRow.subList(1, memberRow.size()).stream()
                .filter(cell -> !cell.equals("空缺"))
                .filter(cell -> cell.contains("💤") || cell.contains("⏳")
                        || cell.contains("✅") || cell.contains("⚠️"))
                .count();
        assertEquals(4, occupiedCellEmojiCount);
        assertFalse(memberRow.stream().anyMatch(cell -> cell.equals("空缺") &&
                (cell.contains("💤") || cell.contains("⏳") || cell.contains("✅") || cell.contains("⚠️"))));
    }

    @Test
    @DisplayName("真实BufferedImage渲染包含四类Emoji且图片非空")
    void renderTable_shouldProduceNonBlankImageWithEmoji() {
        List<List<String>> tableData = List.of(
                List.of("OC表格图片状态展示"),
                List.of("成员", "💤", "⏳", "✅", "⚠️"));
        TableImageUtils.TableConfig config = new TableImageUtils.TableConfig();
        config.addMerge(0, 0, 1, 5)
                .setCellStyle(0, 0, new TableImageUtils.CellStyle()
                        .setPadding(25)
                        .setFont(new Font("微软雅黑", Font.BOLD, 30)));

        BufferedImage image = TableImageUtils.renderTableToImage(tableData, config);
        assertTrue(image.getWidth() > 0);
        assertTrue(image.getHeight() > 0);
        assertTrue(hasNonWhitePixel(image));
    }

    private boolean hasNonWhitePixel(BufferedImage image) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y);
                int red = (rgb >> 16) & 0xFF;
                int green = (rgb >> 8) & 0xFF;
                int blue = rgb & 0xFF;
                if (red < 245 || green < 245 || blue < 245) {
                    return true;
                }
            }
        }
        return false;
    }

    private TornUserDO user(long id) {
        TornUserDO user = new TornUserDO();
        user.setId(id);
        user.setNickname("用户" + id);
        return user;
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
