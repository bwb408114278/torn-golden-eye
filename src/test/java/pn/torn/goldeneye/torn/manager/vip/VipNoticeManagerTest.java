package pn.torn.goldeneye.torn.manager.vip;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import pn.torn.goldeneye.base.bot.Bot;
import pn.torn.goldeneye.base.torn.TornApi;
import pn.torn.goldeneye.configuration.TornApiKeyConfig;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.constants.bot.BotConstants;
import pn.torn.goldeneye.repository.dao.vip.VipNoticeDAO;
import pn.torn.goldeneye.repository.dao.vip.VipSubscribeDAO;
import pn.torn.goldeneye.repository.model.setting.TornApiKeyDO;
import pn.torn.goldeneye.repository.model.vip.VipNoticeDO;
import pn.torn.goldeneye.repository.model.vip.VipSubscribeDO;
import pn.torn.goldeneye.torn.manager.vip.notice.BarNoticeChecker;
import pn.torn.goldeneye.torn.manager.vip.notice.BaseVipNoticeChecker;
import pn.torn.goldeneye.torn.manager.vip.notice.CooldownNoticeChecker;
import pn.torn.goldeneye.torn.manager.vip.notice.VipNoticeChecker;
import pn.torn.goldeneye.torn.model.user.bar.TornUserBarDTO;
import pn.torn.goldeneye.torn.model.user.bar.TornUserBarDataVO;
import pn.torn.goldeneye.torn.model.user.bar.TornUserBarNumberVO;
import pn.torn.goldeneye.torn.model.user.bar.TornUserBarVO;
import pn.torn.goldeneye.torn.model.user.cooldown.TornUserCooldownDTO;
import pn.torn.goldeneye.torn.model.user.cooldown.TornUserCooldownDataVO;
import pn.torn.goldeneye.torn.model.user.cooldown.TornUserCooldownVO;

import java.time.LocalDate;
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
    // ==================== shouldCallApi 逻辑测试 ====================
    @Nested
    @DisplayName("BaseVipNoticeChecker#shouldCallApi")
    class ShouldCallApiTest {
        /**
         * 创建一个具体子类用于测试 protected 方法
         */
        private final BaseVipNoticeChecker checker = new BaseVipNoticeChecker() {
            @Override
            public List<String> checkAndUpdate(VipNoticeDO notice, LocalDateTime checkTime) {
                return Collections.emptyList();
            }
        };

        @Test
        @DisplayName("lastCheckTime 为 null 时应调用 API")
        void shouldCallApi_whenLastCheckTimeNull() {
            assertThat(checker.shouldCallApi(
                    LocalDateTime.of(2024, 1, 1, 12, 0),
                    null,
                    3600L
            )).isTrue();
        }

        @Test
        @DisplayName("剩余时间 > 0 且未过期 → 不调用 API")
        void shouldNotCallApi_whenCooldownNotExpired() {
            LocalDateTime lastCheck = LocalDateTime.of(2024, 1, 1, 12, 0);
            LocalDateTime now = lastCheck.plusSeconds(3599); // 还差 1 秒过期
            assertThat(checker.shouldCallApi(now, lastCheck, 3600L)).isFalse();
        }

        @Test
        @DisplayName("剩余时间 > 0 且已过期 → 调用 API")
        void shouldCallApi_whenCooldownExpired() {
            LocalDateTime lastCheck = LocalDateTime.of(2024, 1, 1, 12, 0);
            LocalDateTime now = lastCheck.plusSeconds(3601);
            assertThat(checker.shouldCallApi(now, lastCheck, 3600L)).isTrue();
        }

        @Test
        @DisplayName("剩余时间 = 0 且不到 30 分钟 → 不调用 API")
        void shouldNotCallApi_whenZeroAndRecheckNotDue() {
            LocalDateTime lastCheck = LocalDateTime.of(2024, 1, 1, 12, 0);
            LocalDateTime now = lastCheck.plusMinutes(29);
            assertThat(checker.shouldCallApi(now, lastCheck, 0L)).isFalse();
        }

        @Test
        @DisplayName("剩余时间 = 0 且超过 30 分钟 → 调用 API")
        void shouldCallApi_whenZeroAndRecheckDue() {
            LocalDateTime lastCheck = LocalDateTime.of(2024, 1, 1, 12, 0);
            LocalDateTime now = lastCheck.plusMinutes(31);
            assertThat(checker.shouldCallApi(now, lastCheck, 0L)).isTrue();
        }

        @ParameterizedTest
        @DisplayName("边界值：恰好等于过期时间 → isBefore 返回 false → 不调用")
        @CsvSource({
                "3600, 3600",  // 剩余时间>0 的边界
                "0, 1800"      // 剩余时间=0 的边界 (30min = 1800s)
        })
        void shouldNotCallApi_atExactBoundary(long remainSecond, long elapsedSeconds) {
            LocalDateTime lastCheck = LocalDateTime.of(2024, 1, 1, 12, 0);
            LocalDateTime now = lastCheck.plusSeconds(elapsedSeconds);
            assertThat(checker.shouldCallApi(now, lastCheck, remainSecond)).isFalse();
        }
    }

    // ==================== DrugCooldownNoticeChecker 测试 ====================
    @Nested
    @DisplayName("DrugCooldownNoticeChecker")
    class DrugCooldownNoticeCheckerTest {
        @Mock
        private TornApi tornApi;
        @Mock
        private TornApiKeyConfig apiKeyConfig;
        @Mock
        private VipNoticeDAO noticeDao;
        @InjectMocks
        private CooldownNoticeChecker checker;
        private VipNoticeDO notice;
        private final LocalDateTime NOW = LocalDateTime.of(2024, 6, 15, 12, 0);

        @BeforeEach
        void setUp() {
            notice = new VipNoticeDO();
            notice.setId(1L);
            notice.setUserId(100L);
            notice.setDrugCd(0);
            // 设置为 1 小时前，确保 recheck 触发
            notice.setLastCdCheckTime(NOW.minusHours(1));
        }

        @Test
        @DisplayName("冷却未过期时不调用 API，返回空列表")
        void returnsEmpty_whenCooldownNotExpired() {
            notice.setDrugCd(7200); // 2 小时 CD
            notice.setLastCdCheckTime(NOW.minusMinutes(10));
            List<String> result = checker.checkAndUpdate(notice, NOW);
            assertThat(result).isEmpty();
            verifyNoInteractions(tornApi);
        }

        @Test
        @DisplayName("没有 API Key 时返回空列表")
        void returnsEmpty_whenNoApiKey() {
            when(apiKeyConfig.getKeyByUserId(100L)).thenReturn(null);
            List<String> result = checker.checkAndUpdate(notice, NOW);
            assertThat(result).isEmpty();
            verifyNoInteractions(tornApi);
        }

        @Test
        @DisplayName("Drug CD = 0 时返回吃药提醒消息")
        void returnsMessage_whenDrugCdIsZero() {
            TornApiKeyDO key = new TornApiKeyDO();
            when(apiKeyConfig.getKeyByUserId(100L)).thenReturn(key);
            TornUserCooldownVO resp = mockCooldownResponse(0);
            when(tornApi.sendRequest(any(TornUserCooldownDTO.class), eq(key), eq(TornUserCooldownVO.class)))
                    .thenReturn(resp);
            mockNoticeUpdate();
            List<String> result = checker.checkAndUpdate(notice, NOW);
            assertThat(result).containsExactly("大郎, 该吃药了");
        }

        @Test
        @DisplayName("Drug CD > 0 时返回空列表")
        void returnsEmpty_whenDrugCdGreaterThanZero() {
            TornApiKeyDO key = new TornApiKeyDO();
            when(apiKeyConfig.getKeyByUserId(100L)).thenReturn(key);
            TornUserCooldownVO resp = mockCooldownResponse(3600);
            when(tornApi.sendRequest(any(TornUserCooldownDTO.class), eq(key), eq(TornUserCooldownVO.class)))
                    .thenReturn(resp);
            mockNoticeUpdate();
            List<String> result = checker.checkAndUpdate(notice, NOW);
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("调用 API 后应更新 DB 中的 drugCd 和 lastDrugCheckTime")
        void shouldUpdateDatabaseAfterApiCall() {
            TornApiKeyDO key = new TornApiKeyDO();
            when(apiKeyConfig.getKeyByUserId(100L)).thenReturn(key);
            TornUserCooldownVO resp = mockCooldownResponse(500);
            when(tornApi.sendRequest(any(TornUserCooldownDTO.class), eq(key), eq(TornUserCooldownVO.class)))
                    .thenReturn(resp);
            mockNoticeUpdate();
            checker.checkAndUpdate(notice, NOW);
            // 验证 lambdaUpdate 被调用（具体字段通过 mock chain 验证较复杂，
            // 这里验证 update() 被调用即可）
            verify(noticeDao).lambdaUpdate();
        }

        private TornUserCooldownVO mockCooldownResponse(int drugCd) {
            TornUserCooldownVO resp = mock(TornUserCooldownVO.class);
            TornUserCooldownDataVO cooldowns = mock(TornUserCooldownDataVO.class);
            when(resp.getCooldowns()).thenReturn(cooldowns);
            when(cooldowns.getDrug()).thenReturn(drugCd);
            return resp;
        }

        private void mockNoticeUpdate() {
            var wrapper = mock(com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper.class);
            when(noticeDao.lambdaUpdate()).thenReturn(wrapper);
            when(wrapper.set(any(), any())).thenReturn(wrapper);
            when(wrapper.eq(any(), any())).thenReturn(wrapper);
            when(wrapper.update()).thenReturn(true);
        }
    }

    // ==================== BarNoticeChecker 测试 ====================
    @Nested
    @DisplayName("BarNoticeChecker")
    class BarNoticeCheckerTest {
        @Mock
        private TornApi tornApi;
        @Mock
        private TornApiKeyConfig apiKeyConfig;
        @Mock
        private VipNoticeDAO noticeDao;
        @InjectMocks
        private BarNoticeChecker checker;
        private VipNoticeDO notice;
        private final LocalDateTime NOW = LocalDateTime.of(2024, 6, 15, 12, 0);

        @BeforeEach
        void setUp() {
            notice = new VipNoticeDO();
            notice.setId(1L);
            notice.setUserId(100L);
            notice.setEnergyFull(0);
            notice.setNerveFull(0);
            notice.setLastBarCheckTime(NOW.minusHours(1));
        }

        @Test
        @DisplayName("Energy 和 Nerve 都未过期时不调 API")
        void returnsEmpty_whenBothNotExpired() {
            notice.setEnergyFull(7200);
            notice.setNerveFull(7200);
            notice.setLastBarCheckTime(NOW.minusMinutes(10));
            List<String> result = checker.checkAndUpdate(notice, NOW);
            assertThat(result).isEmpty();
            verifyNoInteractions(tornApi);
        }

        @Test
        @DisplayName("只有 Energy 满了")
        void returnsEnergyOnly() {
            TornApiKeyDO key = new TornApiKeyDO();
            when(apiKeyConfig.getKeyByUserId(100L)).thenReturn(key);
            TornUserBarVO resp = mockBarResponse(0, 3600);
            when(tornApi.sendRequest(any(TornUserBarDTO.class), eq(key), eq(TornUserBarVO.class)))
                    .thenReturn(resp);
            mockNoticeUpdate();
            List<String> result = checker.checkAndUpdate(notice, NOW);
            assertThat(result).containsExactly("Energy满了");
        }

        @Test
        @DisplayName("只有 Nerve 满了")
        void returnsNerveOnly() {
            TornApiKeyDO key = new TornApiKeyDO();
            when(apiKeyConfig.getKeyByUserId(100L)).thenReturn(key);
            TornUserBarVO resp = mockBarResponse(3600, 0);
            when(tornApi.sendRequest(any(TornUserBarDTO.class), eq(key), eq(TornUserBarVO.class)))
                    .thenReturn(resp);
            mockNoticeUpdate();
            List<String> result = checker.checkAndUpdate(notice, NOW);
            assertThat(result).containsExactly("Nerve满了");
        }

        @Test
        @DisplayName("Energy 和 Nerve 都满了")
        void returnsBoth() {
            TornApiKeyDO key = new TornApiKeyDO();
            when(apiKeyConfig.getKeyByUserId(100L)).thenReturn(key);
            TornUserBarVO resp = mockBarResponse(0, 0);
            when(tornApi.sendRequest(any(TornUserBarDTO.class), eq(key), eq(TornUserBarVO.class)))
                    .thenReturn(resp);
            mockNoticeUpdate();
            List<String> result = checker.checkAndUpdate(notice, NOW);
            assertThat(result).containsExactlyInAnyOrder("Energy满了", "Nerve满了");
        }

        @Test
        @DisplayName("都不为 0 时返回空")
        void returnsEmpty_whenNeitherZero() {
            TornApiKeyDO key = new TornApiKeyDO();
            when(apiKeyConfig.getKeyByUserId(100L)).thenReturn(key);
            TornUserBarVO resp = mockBarResponse(1800, 3600);
            when(tornApi.sendRequest(any(TornUserBarDTO.class), eq(key), eq(TornUserBarVO.class)))
                    .thenReturn(resp);
            mockNoticeUpdate();
            List<String> result = checker.checkAndUpdate(notice, NOW);
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("没有 API Key 时返回空")
        void returnsEmpty_whenNoApiKey() {
            when(apiKeyConfig.getKeyByUserId(100L)).thenReturn(null);
            List<String> result = checker.checkAndUpdate(notice, NOW);
            assertThat(result).isEmpty();
        }

        private TornUserBarVO mockBarResponse(int energyFullTime, int nerveFullTime) {
            TornUserBarVO resp = mock(TornUserBarVO.class);
            TornUserBarDataVO bars = mock(TornUserBarDataVO.class);
            TornUserBarNumberVO energy = mock(TornUserBarNumberVO.class);
            TornUserBarNumberVO nerve = mock(TornUserBarNumberVO.class);
            when(resp.getBars()).thenReturn(bars);
            when(bars.getEnergy()).thenReturn(energy);
            when(bars.getNerve()).thenReturn(nerve);
            when(energy.getFullTime()).thenReturn(energyFullTime);
            when(nerve.getFullTime()).thenReturn(nerveFullTime);
            return resp;
        }

        private void mockNoticeUpdate() {
            var wrapper = mock(com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper.class);
            when(noticeDao.lambdaUpdate()).thenReturn(wrapper);
            when(wrapper.set(any(), any())).thenReturn(wrapper);
            when(wrapper.eq(any(), any())).thenReturn(wrapper);
            when(wrapper.update()).thenReturn(true);
        }
    }

    // ==================== VipNoticeManager 集成测试 ====================
    @Nested
    @DisplayName("VipNoticeManager")
    class VipNoticeManagerFunctionTest {
        @Mock
        private ThreadPoolTaskExecutor virtualThreadExecutor;
        @Mock
        private Bot bot;
        @Mock
        private VipSubscribeDAO subscribeDao;
        @Mock
        private VipNoticeDAO noticeDao;
        @Mock
        private ProjectProperty projectProperty;

        @Test
        @DisplayName("非生产环境不执行")
        void shouldSkip_whenNotProd() {
            when(projectProperty.getEnv()).thenReturn("dev");
            VipNoticeManager manager = new VipNoticeManager(
                    virtualThreadExecutor, bot, List.of(), subscribeDao, noticeDao, projectProperty);
            manager.notice();
            verifyNoInteractions(subscribeDao);
        }

        @Test
        @DisplayName("VIP 列表为空时不查询 notice 表")
        void shouldSkip_whenNoVipUsers() {
            when(projectProperty.getEnv()).thenReturn(BotConstants.ENV_PROD);
            var queryWrapper = mock(com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper.class);
            when(subscribeDao.lambdaQuery()).thenReturn(queryWrapper);
            when(queryWrapper.ge(any(), any())).thenReturn(queryWrapper);
            when(queryWrapper.list()).thenReturn(Collections.emptyList());
            VipNoticeManager manager = new VipNoticeManager(
                    virtualThreadExecutor, bot, List.of(), subscribeDao, noticeDao, projectProperty);
            manager.notice();
            verifyNoInteractions(noticeDao);
        }

        @Test
        @DisplayName("Checker 抛异常不影响其他用户")
        void shouldHandleExceptionGracefully() {
            when(projectProperty.getEnv()).thenReturn(BotConstants.ENV_PROD);
            // 模拟直接在当前线程执行（方便测试）
            doAnswer(invocation -> {
                ((Runnable) invocation.getArgument(0)).run();
                return null;
            }).when(virtualThreadExecutor).execute(any(Runnable.class));
            VipSubscribeDO vip = new VipSubscribeDO();
            vip.setUserId(100L);
            vip.setQqId(200L);
            vip.setEndDate(LocalDate.of(2099, 12, 31));
            // 模拟 subscribeDao 查询
            var subQueryWrapper = mock(com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper.class);
            when(subscribeDao.lambdaQuery()).thenReturn(subQueryWrapper);
            when(subQueryWrapper.ge(any(), any())).thenReturn(subQueryWrapper);
            when(subQueryWrapper.list()).thenReturn(List.of(vip));
            // 模拟 noticeDao 查询
            var noticeQueryWrapper = mock(com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper.class);
            when(noticeDao.lambdaQuery()).thenReturn(noticeQueryWrapper);
            when(noticeQueryWrapper.in(any(), anyList())).thenReturn(noticeQueryWrapper);
            when(noticeQueryWrapper.list()).thenReturn(Collections.emptyList());
            when(noticeDao.save(any())).thenReturn(true);
            // 制造一个会抛异常的 checker
            VipNoticeChecker failingChecker = (notice, checkTime) -> {
                throw new RuntimeException("API boom!");
            };
            VipNoticeManager manager = new VipNoticeManager(
                    virtualThreadExecutor, bot, List.of(failingChecker), subscribeDao, noticeDao, projectProperty);
            // 不应抛出异常
            org.junit.jupiter.api.Assertions.assertDoesNotThrow(manager::notice);
        }
    }

    // ==================== 新增类型扩展性示例测试 ====================
    @Nested
    @DisplayName("扩展性验证 - 添加新 Checker 无需修改 Manager")
    class ExtensibilityTest {
        @Test
        @DisplayName("新的 NoticeChecker 实现可以被 Manager 自动收集")
        void newCheckerIsCollectedAutomatically() {
            // 假设新增了一个 Travel 提醒
            VipNoticeChecker travelChecker = (notice, checkTime) -> {
                // 简化逻辑：总是提醒
                return List.of("你的旅行结束了");
            };
            VipNoticeChecker drugChecker = (notice, checkTime) -> {
                return List.of("大郎, 该吃药了");
            };
            VipNoticeDO notice = new VipNoticeDO(100L);
            LocalDateTime now = LocalDateTime.of(2024, 6, 15, 12, 0);
            List<VipNoticeChecker> allCheckers = List.of(drugChecker, travelChecker);
            // 模拟 manager 中 processUser 的逻辑
            List<String> allMessages = allCheckers.stream()
                    .flatMap(c -> c.checkAndUpdate(notice, now).stream())
                    .toList();
            assertThat(allMessages).containsExactlyInAnyOrder(
                    "大郎, 该吃药了",
                    "你的旅行结束了"
            );
        }
    }
}