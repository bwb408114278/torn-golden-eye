package pn.torn.goldeneye.torn.manager.setting;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;
import pn.torn.goldeneye.repository.dao.setting.TornSettingOcDAO;
import pn.torn.goldeneye.repository.dao.setting.TornSettingOcSlotDAO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcSlotDO;
import pn.torn.goldeneye.torn.model.faction.crime.TornFactionCrimeSlotPositionVO;
import pn.torn.goldeneye.torn.model.faction.crime.TornFactionCrimeSlotVO;
import pn.torn.goldeneye.torn.model.faction.crime.TornFactionCrimeVO;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * OC设置目录自动同步单元测试。
 *
 * <p>仅验证构造与编排：缺失目录默认字段、已存在同rank跳过、rank漂移告警、
 * 无效输入不插入、多实例仅取首个完整模板；不以源码字符串断言XML或事务注解。</p>
 *
 * @author Bai
 * @version 1.5.1
 * @since 2026.08.29
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OC设置目录自动同步单元测试")
class TornSettingOcSyncManagerTest {
    @Mock
    private TornSettingOcDAO settingOcDao;
    @Mock
    private TornSettingOcSlotDAO settingOcSlotDao;
    @Mock
    private TornSettingOcManager settingOcManager;
    @Mock
    private TornSettingOcSlotManager settingOcSlotManager;
    @Captor
    private ArgumentCaptor<List<TornSettingOcDO>> ocListCaptor;
    @Captor
    private ArgumentCaptor<List<TornSettingOcSlotDO>> slotListCaptor;

    @Test
    @DisplayName("缺失OC: 按去重岗位生成OC默认字段和全部岗位默认字段并驱逐缓存")
    void missingOc_buildsDedupedCatalogWithDefaults() {
        doReturn(List.of()).when(settingOcDao).list();
        doReturn(1).when(settingOcDao).insertMissingBatch(anyList());
        doReturn(3).when(settingOcSlotDao).insertMissingBatch(anyList());
        TornFactionCrimeVO oc = crime("Auto Sync New OC", 8,
                slot("MUS", 1), slot("MUS", 2), slot("WEA", 1), slot("WEA", 1));

        buildManager().syncMissingAvailable(List.of(oc));

        verify(settingOcDao).insertMissingBatch(ocListCaptor.capture());
        List<TornSettingOcDO> ocList = ocListCaptor.getValue();
        assertEquals(1, ocList.size());
        TornSettingOcDO newOc = ocList.getFirst();
        assertEquals("Auto Sync New OC", newOc.getOcName());
        assertEquals(8, newOc.getRank());
        assertEquals(3, newOc.getRequiredMembers());
        assertEquals(3, newOc.getPrepareDays());
        assertEquals(0L, newOc.getExpectedReward());

        verify(settingOcSlotDao).insertMissingBatch(slotListCaptor.capture());
        List<TornSettingOcSlotDO> slots = slotListCaptor.getValue();
        assertEquals(3, slots.size());
        assertEquals(List.of("MUS#1", "MUS#2", "WEA#1"), slots.stream().map(TornSettingOcSlotDO::getSlotCode).toList());
        assertEquals(List.of("MUS", "MUS", "WEA"), slots.stream().map(TornSettingOcSlotDO::getSlotShortCode).toList());
        assertTrue(slots.stream().allMatch(s -> s.getPassRate() == 60 && s.getPriority() == 0
                && s.getBestSuccess().compareTo(BigDecimal.ZERO) == 0));
        verify(settingOcManager).refreshCache();
        verify(settingOcSlotManager).refreshCache();
    }

    @Test
    @DisplayName("已存在同rank OC: 不插入也不驱逐缓存")
    void existingSameRank_skipsInsertAndCacheEvict() {
        TornSettingOcDO existing = new TornSettingOcDO();
        existing.setOcName("Auto Sync Exists OC");
        existing.setRank(5);
        doReturn(List.of(existing)).when(settingOcDao).list();

        buildManager().syncMissingAvailable(List.of(crime("Auto Sync Exists OC", 5, slot("MUS", 1))));

        verify(settingOcDao, never()).insertMissingBatch(anyList());
        verify(settingOcSlotDao, never()).insertMissingBatch(anyList());
        verify(settingOcManager, never()).refreshCache();
        verify(settingOcSlotManager, never()).refreshCache();
    }

    @Test
    @DisplayName("rank漂移: 不插入不驱逐缓存且按名称去重记录一条warn")
    void rankDrift_skipsInsertAndWarnsOnce() {
        ListAppender<ILoggingEvent> appender = attachAppender();
        TornSettingOcDO existing = new TornSettingOcDO();
        existing.setOcName("Auto Sync Drift OC");
        existing.setRank(6);
        doReturn(List.of(existing)).when(settingOcDao).list();

        try {
            buildManager().syncMissingAvailable(List.of(
                    crime("Auto Sync Drift OC", 7, slot("MUS", 1)),
                    crime("Auto Sync Drift OC", 7, slot("WEA", 1))));
        } finally {
            detachAppender(appender);
        }

        verify(settingOcDao, never()).insertMissingBatch(anyList());
        verify(settingOcSlotDao, never()).insertMissingBatch(anyList());
        verify(settingOcManager, never()).refreshCache();
        verify(settingOcSlotManager, never()).refreshCache();
        List<ILoggingEvent> warnEvents = appender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN).toList();
        assertEquals(1, warnEvents.size(), "同一名称去重后只记录一条漂移warn");
        String message = warnEvents.getFirst().getFormattedMessage();
        assertTrue(message.contains("Auto Sync Drift OC"), "warn必须包含ocName");
        assertTrue(message.contains("catalogRank=[6]"), "warn必须包含目录rank");
        assertTrue(message.contains("availableRank=7"), "warn必须包含API rank");
    }

    @Test
    @DisplayName("空slots/无效岗位编码/必要字段缺失: 均不插入")
    void invalidInput_neverInserts() {
        doReturn(List.of()).when(settingOcDao).list();

        buildManager().syncMissingAvailable(List.of(
                crime("Auto Sync Empty OC", 3),
                crime("Auto Sync Invalid OC", 3, brokenSlot("MUS")),
                crime("Auto Sync NoRank OC", null, slot("MUS", 1))));

        verify(settingOcDao, never()).insertMissingBatch(anyList());
        verify(settingOcSlotDao, never()).insertMissingBatch(anyList());
        verify(settingOcManager, never()).refreshCache();
    }

    @Test
    @DisplayName("同快照相同name+rank多实例: 仅取首个有效完整模板不合并岗位")
    void multiInstance_usesFirstCompleteTemplateOnly() {
        doReturn(List.of()).when(settingOcDao).list();
        doReturn(1).when(settingOcDao).insertMissingBatch(anyList());
        doReturn(2).when(settingOcSlotDao).insertMissingBatch(anyList());

        buildManager().syncMissingAvailable(List.of(
                crime("Auto Sync Multi OC", 4, brokenSlot("MUS")),
                crime("Auto Sync Multi OC", 4, slot("WEA", 1), slot("WEA", 1), slot("MUS", 2))));

        verify(settingOcSlotDao).insertMissingBatch(slotListCaptor.capture());
        List<TornSettingOcSlotDO> slots = slotListCaptor.getValue();
        assertEquals(List.of("WEA#1", "MUS#2"),
                slots.stream().map(TornSettingOcSlotDO::getSlotCode).toList(), "只使用首个完整模板的岗位");
        verify(settingOcDao).insertMissingBatch(ocListCaptor.capture());
        assertEquals(2, ocListCaptor.getValue().getFirst().getRequiredMembers());
    }

    @Test
    @DisplayName("名称缺失且同快照多个rank: 跳过并告警无法确认首次目录rank")
    void newOcWithMultipleRanks_skipsAndWarns() {
        ListAppender<ILoggingEvent> appender = attachAppender();
        doReturn(List.of()).when(settingOcDao).list();

        try {
            buildManager().syncMissingAvailable(List.of(
                    crime("Auto Sync Ambiguous OC", 4, slot("MUS", 1)),
                    crime("Auto Sync Ambiguous OC", 6, slot("MUS", 1))));
        } finally {
            detachAppender(appender);
        }

        verify(settingOcDao, never()).insertMissingBatch(anyList());
        verify(settingOcSlotDao, never()).insertMissingBatch(anyList());
        List<ILoggingEvent> warnEvents = appender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN).toList();
        assertEquals(1, warnEvents.size());
        String message = warnEvents.getFirst().getFormattedMessage();
        assertTrue(message.contains("Auto Sync Ambiguous OC"));
        assertTrue(message.contains("无法确认首次目录rank"));
    }

    /**
     * 构造被测对象并注入自代理引用，模拟Spring容器的自调用代理路径。
     *
     * @return 被测对象
     */
    private TornSettingOcSyncManager buildManager() {
        TornSettingOcSyncManager manager =
                new TornSettingOcSyncManager(settingOcDao, settingOcSlotDao, settingOcManager, settingOcSlotManager);
        ReflectionTestUtils.setField(manager, "syncManager", manager);
        return manager;
    }

    /**
     * 挂载捕获warn日志的ListAppender。
     *
     * @return 已启动的appender
     */
    static ListAppender<ILoggingEvent> attachAppender() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(TornSettingOcSyncManager.class)).addAppender(appender);
        return appender;
    }

    /**
     * 卸载ListAppender。
     *
     * @param appender 已挂载的appender
     */
    static void detachAppender(ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(TornSettingOcSyncManager.class)).detachAppender(appender);
    }

    /**
     * 构造available OC实例。
     *
     * @param name  OC名称
     * @param rank  OC级别
     * @param slots 岗位列表
     * @return OC实例
     */
    static TornFactionCrimeVO crime(String name, Integer rank, TornFactionCrimeSlotVO... slots) {
        TornFactionCrimeVO oc = new TornFactionCrimeVO();
        oc.setName(name);
        oc.setDifficulty(rank);
        oc.setSlots(List.of(slots));
        return oc;
    }

    /**
     * 构造可生成有效编码的岗位。
     *
     * @param position 岗位名称
     * @param number   岗位编号
     * @return 岗位实例
     */
    static TornFactionCrimeSlotVO slot(String position, int number) {
        TornFactionCrimeSlotVO slot = new TornFactionCrimeSlotVO();
        slot.setPosition(position);
        TornFactionCrimeSlotPositionVO positionInfo = new TornFactionCrimeSlotPositionVO();
        positionInfo.setNumber(number);
        slot.setPositionInfo(positionInfo);
        return slot;
    }

    /**
     * 构造缺少岗位信息无法生成有效编码的岗位。
     *
     * @param position 岗位名称
     * @return 岗位实例
     */
    static TornFactionCrimeSlotVO brokenSlot(String position) {
        TornFactionCrimeSlotVO slot = new TornFactionCrimeSlotVO();
        slot.setPosition(position);
        return slot;
    }
}
