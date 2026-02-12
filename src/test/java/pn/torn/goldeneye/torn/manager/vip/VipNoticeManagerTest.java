package pn.torn.goldeneye.torn.manager.vip;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import pn.torn.goldeneye.base.bot.Bot;
import pn.torn.goldeneye.base.torn.TornApi;
import pn.torn.goldeneye.base.torn.TornReqParam;
import pn.torn.goldeneye.configuration.TornApiKeyConfig;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.repository.dao.vip.VipNoticeDAO;
import pn.torn.goldeneye.repository.dao.vip.VipSubscribeDAO;
import pn.torn.goldeneye.repository.model.setting.TornApiKeyDO;
import pn.torn.goldeneye.repository.model.vip.VipNoticeDO;
import pn.torn.goldeneye.torn.model.user.cooldown.TornUserCooldownDTO;
import pn.torn.goldeneye.torn.model.user.cooldown.TornUserCooldownDataVO;
import pn.torn.goldeneye.torn.model.user.cooldown.TornUserCooldownVO;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * VIP提醒集成测试
 *
 * @author Bai
 * @version 0.5.0
 * @since 2026.02.12
 */
@ExtendWith(MockitoExtension.class)
class VipNoticeManagerTest {
    @Mock
    private ThreadPoolTaskExecutor virtualThreadExecutor;
    @Mock
    private Bot bot;
    @Mock
    private TornApi tornApi;
    @Mock
    private TornApiKeyConfig apiKeyConfig;
    @Mock
    private VipSubscribeDAO subscribeDao;
    @Mock
    private VipNoticeDAO noticeDao;
    @Mock
    private ProjectProperty projectProperty;

    @InjectMocks
    private VipNoticeManager vipNoticeManager;

    private LocalDateTime checkTime;
    private TornApiKeyDO mockKey;

    @BeforeEach
    void setUp() {
        checkTime = LocalDateTime.of(2024, 6, 15, 12, 0, 0);
        mockKey = new TornApiKeyDO();
    }

    // ===== checkUserCooldown 测试 =====

    @Nested
    @DisplayName("checkUserCooldown - 首次检查（无历史记录）")
    class FirstCheckTests {

        @Test
        @DisplayName("首次检查，drugCd=0，应该提醒并保存记录")
        void shouldNotify_whenFirstCheck_andDrugCdIsZero() {
            long userId = 1001L;
            List<VipNoticeDO> noticeList = Collections.emptyList();

            when(apiKeyConfig.getKeyByUserId(userId)).thenReturn(mockKey);
            mockApiResponse(0L);

            boolean result = vipNoticeManager.checkUserCooldown(userId, noticeList, checkTime);

            assertThat(result).isTrue();
            verify(noticeDao).save(any(VipNoticeDO.class));
            verify(noticeDao, never()).updateById(any());
        }

        @Test
        @DisplayName("首次检查，drugCd>0，不应提醒但应保存记录")
        void shouldNotNotify_whenFirstCheck_andDrugCdIsPositive() {
            long userId = 1001L;
            List<VipNoticeDO> noticeList = Collections.emptyList();

            when(apiKeyConfig.getKeyByUserId(userId)).thenReturn(mockKey);
            mockApiResponse(300L);

            boolean result = vipNoticeManager.checkUserCooldown(userId, noticeList, checkTime);

            assertThat(result).isFalse();
            verify(noticeDao).save(any(VipNoticeDO.class));
        }

        @Test
        @DisplayName("首次检查，API key为null，不应提醒")
        void shouldNotNotify_whenFirstCheck_andKeyIsNull() {
            long userId = 1001L;
            List<VipNoticeDO> noticeList = Collections.emptyList();

            when(apiKeyConfig.getKeyByUserId(userId)).thenReturn(null);

            boolean result = vipNoticeManager.checkUserCooldown(userId, noticeList, checkTime);

            assertThat(result).isFalse();
            verify(tornApi, never()).sendRequest((TornReqParam) any(), any(), any());
            verify(noticeDao, never()).save(any());
        }
    }

    @Nested
    @DisplayName("checkUserCooldown - CD过期后重新检查")
    class CdExpiredTests {

        @Test
        @DisplayName("上次drugCd>0且已过期，现在drugCd=0，应该提醒并更新记录")
        void shouldNotify_whenPreviousCdExpired_andNowZero() {
            long userId = 1001L;
            // 上次检查：10分钟前，drugCd=300秒（5分钟），所以CD已于5分钟前过期
            VipNoticeDO previousNotice = createNotice(1L, userId,
                    checkTime.minusMinutes(10), 300L);
            List<VipNoticeDO> noticeList = List.of(previousNotice);

            when(apiKeyConfig.getKeyByUserId(userId)).thenReturn(mockKey);
            mockApiResponse(0L);

            boolean result = vipNoticeManager.checkUserCooldown(userId, noticeList, checkTime);

            assertThat(result).isTrue();
            verify(noticeDao).updateById(any(VipNoticeDO.class));
            verify(noticeDao, never()).save(any());
        }

        @Test
        @DisplayName("上次drugCd>0且已过期，现在drugCd仍>0，不应提醒但应更新记录")
        void shouldNotNotify_whenPreviousCdExpired_andStillPositive() {
            long userId = 1001L;
            VipNoticeDO previousNotice = createNotice(1L, userId,
                    checkTime.minusMinutes(10), 300L);
            List<VipNoticeDO> noticeList = List.of(previousNotice);

            when(apiKeyConfig.getKeyByUserId(userId)).thenReturn(mockKey);
            mockApiResponse(600L);

            boolean result = vipNoticeManager.checkUserCooldown(userId, noticeList, checkTime);

            assertThat(result).isFalse();
            verify(noticeDao).updateById(any(VipNoticeDO.class));
        }
    }

    @Nested
    @DisplayName("checkUserCooldown - CD未过期")
    class CdNotExpiredTests {

        @Test
        @DisplayName("上次drugCd>0且未过期，不应查询API")
        void shouldNotCheck_whenCdNotExpired() {
            long userId = 1001L;
            // 上次检查：1分钟前，drugCd=600秒（10分钟），CD还没过期
            VipNoticeDO previousNotice = createNotice(1L, userId,
                    checkTime.minusMinutes(1), 600L);
            List<VipNoticeDO> noticeList = List.of(previousNotice);

            boolean result = vipNoticeManager.checkUserCooldown(userId, noticeList, checkTime);

            assertThat(result).isFalse();
            verify(apiKeyConfig, never()).getKeyByUserId(anyLong());
            verify(tornApi, never()).sendRequest((TornReqParam) any(), any(), any());
        }
    }

    @Nested
    @DisplayName("checkUserCooldown - 防重复提醒")
    class NoRepeatNotifyTests {

        @Test
        @DisplayName("上次drugCd=0且未超过30分钟，不应查询API也不应提醒")
        void shouldNotCheck_whenPreviousCdZero_andWithinRecheckInterval() {
            long userId = 1001L;
            // 上次检查：10分钟前，drugCd=0
            VipNoticeDO previousNotice = createNotice(1L, userId,
                    checkTime.minusMinutes(10), 0L);
            List<VipNoticeDO> noticeList = List.of(previousNotice);

            boolean result = vipNoticeManager.checkUserCooldown(userId, noticeList, checkTime);

            assertThat(result).isFalse();
            verify(apiKeyConfig, never()).getKeyByUserId(anyLong());
            verify(tornApi, never()).sendRequest((TornReqParam) any(), any(), any());
        }

        @Test
        @DisplayName("上次drugCd=0且已超过30分钟，应重新查询API但drugCd仍为0时不提醒")
        void shouldRecheck_whenPreviousCdZero_andExceedRecheckInterval_butStillZero() {
            long userId = 1001L;
            // 上次检查：35分钟前，drugCd=0
            VipNoticeDO previousNotice = createNotice(1L, userId,
                    checkTime.minusMinutes(35), 0L);
            List<VipNoticeDO> noticeList = List.of(previousNotice);

            when(apiKeyConfig.getKeyByUserId(userId)).thenReturn(mockKey);
            mockApiResponse(0L);

            boolean result = vipNoticeManager.checkUserCooldown(userId, noticeList, checkTime);

            // 关键断言：即使API返回0，因为上次也是0，所以不重复提醒
            assertThat(result).isFalse();
            // 但应更新记录的checkTime
            verify(noticeDao).updateById(any(VipNoticeDO.class));
        }

        @Test
        @DisplayName("上次drugCd=0且已超30分钟，用户已吃药drugCd>0，不应提醒但应更新记录")
        void shouldRecheck_whenPreviousCdZero_andUserTookDrug() {
            long userId = 1001L;
            VipNoticeDO previousNotice = createNotice(1L, userId,
                    checkTime.minusMinutes(35), 0L);
            List<VipNoticeDO> noticeList = List.of(previousNotice);

            when(apiKeyConfig.getKeyByUserId(userId)).thenReturn(mockKey);
            mockApiResponse(5000L); // 用户吃了药，CD变为5000秒

            boolean result = vipNoticeManager.checkUserCooldown(userId, noticeList, checkTime);

            assertThat(result).isFalse();
            ArgumentCaptor<VipNoticeDO> captor = ArgumentCaptor.forClass(VipNoticeDO.class);
            verify(noticeDao).updateById(captor.capture());
            assertThat(captor.getValue().getDrugCd()).isEqualTo(5000L);
        }
    }

    @Nested
    @DisplayName("checkUserCooldown - 完整周期测试")
    class FullCycleTests {

        @Test
        @DisplayName("模拟完整周期：首次CD=0提醒 → 不重复提醒 → 用户吃药 → CD再次归零提醒")
        void fullCycleTest() {
            long userId = 1001L;
            when(apiKeyConfig.getKeyByUserId(userId)).thenReturn(mockKey);

            // === 第1步：首次检查，drugCd=0，应提醒 ===
            mockApiResponse(0L);
            boolean result1 = vipNoticeManager.checkUserCooldown(
                    userId, Collections.emptyList(), checkTime);
            assertThat(result1).isTrue();

            // === 第2步：上次drugCd=0，10分钟后，不应重新检查 ===
            VipNoticeDO step1Notice = createNotice(1L, userId, checkTime, 0L);
            LocalDateTime checkTime2 = checkTime.plusMinutes(10);
            boolean result2 = vipNoticeManager.checkUserCooldown(
                    userId, List.of(step1Notice), checkTime2);
            assertThat(result2).isFalse();

            // === 第3步：35分钟后重新检查，用户吃药了，drugCd=5000 ===
            LocalDateTime checkTime3 = checkTime.plusMinutes(35);
            mockApiResponse(5000L);
            boolean result3 = vipNoticeManager.checkUserCooldown(
                    userId, List.of(step1Notice), checkTime3);
            assertThat(result3).isFalse(); // drugCd > 0，不提醒

            // === 第4步：drugCd过期后检查，drugCd=0，应提醒（因为上次是5000，状态转换了）===
            VipNoticeDO step3Notice = createNotice(1L, userId, checkTime3, 5000L);
            LocalDateTime checkTime4 = checkTime3.plusSeconds(5001);
            mockApiResponse(0L);
            boolean result4 = vipNoticeManager.checkUserCooldown(
                    userId, List.of(step3Notice), checkTime4);
            assertThat(result4).isTrue(); // 从非0变为0，提醒！
        }
    }

    // ===== 辅助方法 =====

    private void mockApiResponse(long drugCd) {
        TornUserCooldownVO resp = new TornUserCooldownVO();
        TornUserCooldownDataVO cooldowns = new TornUserCooldownDataVO();
        cooldowns.setDrug(drugCd);
        resp.setCooldowns(cooldowns);
        when(tornApi.sendRequest(any(TornUserCooldownDTO.class), eq(mockKey),
                eq(TornUserCooldownVO.class))).thenReturn(resp);
    }

    private VipNoticeDO createNotice(Long id, Long userId,
                                     LocalDateTime checkTime, Long drugCd) {
        VipNoticeDO notice = new VipNoticeDO();
        notice.setId(id);
        notice.setUserId(userId);
        notice.setCheckTime(checkTime);
        notice.setDrugCd(drugCd);
        return notice;
    }
}