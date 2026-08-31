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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * OC推荐表格构建测试 —— 验证推荐高亮与空闲非推荐岗位置灰，以及统一状态装配的时间文案和成员Emoji
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
    private static final Color TEAM_STOPPED_COLOR = Color.YELLOW;
    private static final Color TEAM_PREPARING_COLOR = new Color(14, 133, 49);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 1, 12, 0, 0);

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
                List.of(buildEntry(oc, recommendedSlot)), NOW);

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
                List.of(buildEntry(oc, firstRecommend), buildEntry(oc, secondRecommend)), NOW);

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
    @DisplayName("公共接入：标题只追加一种时间文案，已加入成员单元格逐列对应唯一Emoji，空槽无Emoji")
    void enrichCurrentOcTable_shouldAddOneTimeTextAndOneEmojiPerMember() {
        TornFactionOcDO oc = buildOc();
        oc.setReadyTime(NOW.plusHours(2).plusMinutes(30));
        TornFactionOcSlotDO idle = buildSlot(oc.getId(), 10L, "Idle");
        idle.setProgress(BigDecimal.ZERO);
        TornFactionOcSlotDO preparing = buildSlot(oc.getId(), 11L, "Preparing");
        preparing.setProgress(BigDecimal.valueOf(50));
        TornFactionOcSlotDO ready = buildSlot(oc.getId(), 12L, "Ready");
        ready.setProgress(BigDecimal.valueOf(100));
        TornFactionOcSlotDO missing = buildSlot(oc.getId(), 13L, "Missing");
        missing.setProgress(BigDecimal.valueOf(50));
        missing.setRequiredItemId(100);
        missing.setRequiredItemAvailable(false);
        TornFactionOcSlotDO empty = buildSlot(oc.getId(), null, "Empty");
        empty.setProgress(BigDecimal.ZERO);
        when(ocDao.queryListByIdList(eq(FACTION_ID), anyList())).thenReturn(List.of(oc));
        when(slotDao.queryListByOc(anyCollection()))
                .thenReturn(new ArrayList<>(List.of(idle, preparing, ready, missing, empty)));
        when(tableUserDao.queryUserMap(anyCollection())).thenReturn(Map.of(
                10L, user(10L), 11L, user(11L), 12L, user(12L), 13L, user(13L)));

        TableDataBO tableData = msgManager.buildRecommendTableData("测试标题", FACTION_ID,
                List.of(buildEntry(oc, idle)), NOW);

        // 推荐块分隔行以推荐理由开头，时间文案只能作为唯一后缀追加
        String ocTitle = tableData.getTableData().get(1).getFirst();
        assertTrue(ocTitle.endsWith(" 2小时30分后停转"), "标题应只追加停转倒计时文案: " + ocTitle);

        // 排序后列序：Idle(10)、Missing(13)、Preparing(11)、Ready(12)、空槽，成员行=3
        List<String> memberRow = tableData.getTableData().get(3);
        assertEquals("用户10[10] 💤", memberRow.get(1));
        assertEquals("用户13[13] ⚠️", memberRow.get(2));
        assertEquals("用户11[11] ⏳", memberRow.get(3));
        assertEquals("用户12[12] ✅", memberRow.get(4));
        assertEquals("空缺", memberRow.get(5));
    }

    @Test
    @DisplayName("普通OC查询路径：标题写入精确时间文案，成员单元格逐个追加唯一状态Emoji")
    void buildOcTable_shouldEnrichTitleTimeTextAndMemberEmojiCells() {
        TornFactionOcDO recruitingIdleOc = buildOc(1L, "RecruitingOc", "Recruiting", NOW.plusHours(30));
        TornFactionOcDO planningSoonOc = buildOc(2L, "PlanningSoonOc", "Planning", NOW.plusHours(2));
        TornFactionOcDO planningFarOc = buildOc(3L, "PlanningFarOc", "Planning", NOW.plusHours(48));

        TornFactionOcSlotDO idle = slotWithProgress(buildSlot(1L, 10L, "A"), BigDecimal.ZERO);
        TornFactionOcSlotDO preparing = slotWithProgress(buildSlot(1L, 11L, "B"), BigDecimal.valueOf(50));
        TornFactionOcSlotDO ready = slotWithProgress(buildSlot(1L, 12L, "C"), BigDecimal.valueOf(100));
        TornFactionOcSlotDO missing = slotWithProgress(buildSlot(1L, 13L, "D"), BigDecimal.valueOf(50));
        missing.setRequiredItemId(100);
        missing.setRequiredItemAvailable(false);
        TornFactionOcSlotDO idleAndMissing = slotWithProgress(buildSlot(1L, 14L, "E"), BigDecimal.ZERO);
        idleAndMissing.setRequiredItemId(100);
        idleAndMissing.setRequiredItemAvailable(false);
        TornFactionOcSlotDO empty = slotWithProgress(buildSlot(1L, null, "F"), BigDecimal.ZERO);
        TornFactionOcSlotDO planningMember = slotWithProgress(buildSlot(2L, 20L, "A"), BigDecimal.valueOf(30));
        TornFactionOcSlotDO planningIdleMember = slotWithProgress(buildSlot(3L, 30L, "A"), BigDecimal.ZERO);
        when(slotDao.queryListByOc(anyCollection())).thenReturn(new ArrayList<>(
                List.of(idle, preparing, ready, missing, idleAndMissing, empty, planningMember, planningIdleMember)));
        when(tableUserDao.queryUserMap(anyCollection())).thenReturn(Map.of(
                10L, user(10L), 11L, user(11L), 12L, user(12L), 13L, user(13L),
                14L, user(14L), 20L, user(20L), 30L, user(30L)));

        TableDataBO tableData = msgManager.buildOcTableData("测试标题",
                List.of(recruitingIdleOc, planningSoonOc, planningFarOc), NOW);

        // 三个OC块的标题行：Recruiting空转、Planning预计执行、Planning空转，每个标题只有一种时间文案
        List<List<String>> rows = tableData.getTableData();
        assertEquals("RecruitingOc 还需空转30小时00分钟", rows.get(1).getFirst());
        assertEquals("PlanningSoonOc 预计14:01开始执行", rows.get(4).getFirst());
        assertEquals("PlanningFarOc 还需空转48小时00分钟", rows.get(7).getFirst());

        // OC1成员行（列序=排序后槽位下标+1）：每个已加入成员恰好一个对应Emoji，空槽无Emoji
        List<String> memberRow = rows.get(3);
        assertEquals("用户10[10] 💤", memberRow.get(1));
        assertEquals("用户11[11] ⏳", memberRow.get(2));
        assertEquals("用户12[12] ✅", memberRow.get(3));
        assertEquals("用户13[13] ⚠️", memberRow.get(4));
        assertEquals("用户14[14] 💤", memberRow.get(5));
        assertEquals("空缺", memberRow.get(6));
    }

    @Test
    @DisplayName("时间一致性：标题时间文案与团队状态颜色由同一固定now决定")
    void buildOcTableData_titleAndTeamColor_shouldUseSameFixedNow() {
        // 秒级边界：已过readyTime的OC停转变黄，恰好等于now的OC仍未停转保持绿色，
        // 两者只可能由同一个传入now同时判定，系统当前时间无法同时满足两侧断言
        TornFactionOcDO stoppedOc = buildOc(1L, "StoppedOc", "Recruiting", NOW.minusSeconds(1));
        TornFactionOcDO countdownOc = buildOc(2L, "CountdownOc", "Recruiting", NOW);
        TornFactionOcSlotDO stoppedMember = slotWithProgress(buildSlot(1L, 10L, "A"), BigDecimal.valueOf(50));
        TornFactionOcSlotDO countdownMember = slotWithProgress(buildSlot(2L, 20L, "A"), BigDecimal.valueOf(50));
        when(slotDao.queryListByOc(anyCollection()))
                .thenReturn(new ArrayList<>(List.of(stoppedMember, countdownMember)));
        when(tableUserDao.queryUserMap(anyCollection())).thenReturn(Map.of(10L, user(10L), 20L, user(20L)));

        TableDataBO tableData = msgManager.buildOcTableData("测试标题", List.of(stoppedOc, countdownOc), NOW);

        // 块1标题行=1已停转，块2标题行=4仍未停转，均由固定now判定
        assertEquals("StoppedOc 已停转", tableData.getTableData().get(1).getFirst());
        assertEquals("CountdownOc 0小时00分后停转", tableData.getTableData().get(4).getFirst());

        // 团队状态颜色与标题使用同一now：块1岗位行2黄色，块2岗位行5绿色白字
        TableImageUtils.TableConfig config = tableData.getTableConfig();
        assertEquals(TEAM_STOPPED_COLOR, config.getCellStyle(2, 0).getBgColor());
        assertEquals(TEAM_PREPARING_COLOR, config.getCellStyle(5, 0).getBgColor());
        assertEquals(Color.WHITE, config.getCellStyle(5, 0).getTextColor());
    }

    @Test
    @DisplayName("输入含四类Emoji时生成非空图片")
    void renderTable_inputWithFourEmoji_shouldProduceNonBlankImage() {
        List<List<String>> tableData = List.of(
                List.of("OC表格图片状态展示"),
                List.of("成员", "💤", "⏳", "✅", "⚠️"));
        List.of("💤", "⏳", "✅", "⚠️").forEach(emoji ->
                assertTrue(tableData.stream().flatMap(List::stream).anyMatch(cell -> cell.contains(emoji)),
                        "渲染输入应包含" + emoji));

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
        oc.setReadyTime(NOW.plusDays(1));
        return oc;
    }

    private TornFactionOcDO buildOc(long id, String name, String status, LocalDateTime readyTime) {
        TornFactionOcDO oc = new TornFactionOcDO();
        oc.setId(id);
        oc.setFactionId(FACTION_ID);
        oc.setName(name);
        oc.setRank(8);
        oc.setStatus(status);
        oc.setReadyTime(readyTime);
        return oc;
    }

    private TornFactionOcSlotDO slotWithProgress(TornFactionOcSlotDO slot, BigDecimal progress) {
        slot.setProgress(progress);
        return slot;
    }

    private TornFactionOcSlotDO buildSlot(Long ocId, Long userId, String position) {
        TornFactionOcSlotDO slot = new TornFactionOcSlotDO();
        slot.setOcId(ocId);
        slot.setUserId(userId);
        slot.setPosition(position);
        return slot;
    }
}
