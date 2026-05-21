package pn.torn.goldeneye.torn.manager.vip;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import pn.torn.goldeneye.base.bot.Bot;
import pn.torn.goldeneye.base.torn.TornApi;
import pn.torn.goldeneye.configuration.TornApiKeyConfig;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.constants.bot.BotConstants;
import pn.torn.goldeneye.constants.bot.enums.VipNoticeTypeEnum;
import pn.torn.goldeneye.constants.torn.SettingConstants;
import pn.torn.goldeneye.constants.torn.enums.user.TornUserStatusEnum;
import pn.torn.goldeneye.repository.dao.vip.VipNoticeConfigDAO;
import pn.torn.goldeneye.repository.dao.vip.VipNoticeStateDAO;
import pn.torn.goldeneye.repository.dao.vip.VipSubscribeDAO;
import pn.torn.goldeneye.repository.model.setting.TornApiKeyDO;
import pn.torn.goldeneye.repository.model.vip.VipNoticeConfigDO;
import pn.torn.goldeneye.repository.model.vip.VipNoticeStateDO;
import pn.torn.goldeneye.repository.model.vip.VipSubscribeDO;
import pn.torn.goldeneye.torn.manager.setting.SysSettingManager;
import pn.torn.goldeneye.torn.manager.user.TornQqUserManager;
import pn.torn.goldeneye.torn.manager.vip.notice.*;
import pn.torn.goldeneye.torn.model.user.TornUserDTO;
import pn.torn.goldeneye.torn.model.user.TornUserProfileVO;
import pn.torn.goldeneye.torn.model.user.TornUserStatusVO;
import pn.torn.goldeneye.torn.model.user.TornUserVO;
import pn.torn.goldeneye.torn.model.user.bar.TornUserBarDTO;
import pn.torn.goldeneye.torn.model.user.bar.TornUserBarDataVO;
import pn.torn.goldeneye.torn.model.user.bar.TornUserBarNumberVO;
import pn.torn.goldeneye.torn.model.user.bar.TornUserBarVO;
import pn.torn.goldeneye.torn.model.user.cooldown.TornUserCooldownDTO;
import pn.torn.goldeneye.torn.model.user.cooldown.TornUserCooldownDataVO;
import pn.torn.goldeneye.torn.model.user.cooldown.TornUserCooldownVO;
import pn.torn.goldeneye.torn.model.user.travel.TornUserTravelDTO;
import pn.torn.goldeneye.torn.model.user.travel.TornUserTravelDataVO;
import pn.torn.goldeneye.torn.model.user.travel.TornUserTravelVO;
import pn.torn.goldeneye.utils.DateTimeUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * VIP提醒集成测试
 *
 * @author Bai
 * @version 1.1.3
 * @since 2026.02.12
 */
@ExtendWith(MockitoExtension.class)
class VipNoticeManagerTest {
    // ==================== shouldCallApi 逻辑测试（通过 checkDontNotice 间接测试）====================
    @Nested
    @DisplayName("BaseVipNoticeChecker#checkDontNotice")
    class CheckDontNoticeTest {

        private final BaseVipNoticeChecker checker = new BaseVipNoticeChecker() {
            @Override
            public List<VipNoticeTypeEnum> getType() {
                return Collections.emptyList();
            }

            @Override
            public List<String> checkAndUpdate(VipNoticeConfigDO config, List<VipNoticeStateDO> stateList, LocalDateTime checkTime) {
                return Collections.emptyList();
            }
        };

        /**
         * 构造只含一个 state 的列表
         */
        private List<VipNoticeStateDO> stateOf(LocalDateTime lastCheckTime, long lastValue) {
            VipNoticeStateDO state = new VipNoticeStateDO();
            state.setLastCheckTime(lastCheckTime);
            state.setLastValue(lastValue);
            return List.of(state);
        }

        @Test
        @DisplayName("lastCheckTime 为 null 时应调用 API → checkDontNotice 返回 false")
        void shouldCallApi_whenLastCheckTimeNull() {
            assertThat(checker.checkDontNotice(stateOf(null, 3600L),
                    LocalDateTime.of(2024, 1, 1, 12, 0))).isFalse();
        }

        @Test
        @DisplayName("剩余时间 > 0 且未过期 → 不调用 API → checkDontNotice 返回 true")
        void shouldNotCallApi_whenCooldownNotExpired() {
            LocalDateTime lastCheck = LocalDateTime.of(2024, 1, 1, 12, 0);
            assertThat(checker.checkDontNotice(stateOf(lastCheck, 3600L),
                    lastCheck.plusSeconds(3599))).isTrue();
        }

        @Test
        @DisplayName("剩余时间 > 0 且已过期 → 调用 API → checkDontNotice 返回 false")
        void shouldCallApi_whenCooldownExpired() {
            LocalDateTime lastCheck = LocalDateTime.of(2024, 1, 1, 12, 0);
            assertThat(checker.checkDontNotice(stateOf(lastCheck, 3600L),
                    lastCheck.plusSeconds(3601))).isFalse();
        }

        @Test
        @DisplayName("剩余时间 = 0 且不到 30 分钟 → 不调用 API → checkDontNotice 返回 true")
        void shouldNotCallApi_whenZeroAndRecheckNotDue() {
            LocalDateTime lastCheck = LocalDateTime.of(2024, 1, 1, 12, 0);
            assertThat(checker.checkDontNotice(stateOf(lastCheck, 0L),
                    lastCheck.plusMinutes(29))).isTrue();
        }

        @Test
        @DisplayName("剩余时间 = 0 且超过 30 分钟 → 调用 API → checkDontNotice 返回 false")
        void shouldCallApi_whenZeroAndRecheckDue() {
            LocalDateTime lastCheck = LocalDateTime.of(2024, 1, 1, 12, 0);
            assertThat(checker.checkDontNotice(stateOf(lastCheck, 0L),
                    lastCheck.plusMinutes(31))).isFalse();
        }

        @ParameterizedTest
        @DisplayName("边界值：恰好等于过期时间 → 不调用 API → checkDontNotice 返回 true")
        @CsvSource({"3600, 3599",
                "0, 1799"
        })
        void shouldNotCallApi_atExactBoundary(long remainSecond, long elapsedSeconds) {
            LocalDateTime lastCheck = LocalDateTime.of(2024, 1, 1, 12, 0);
            assertThat(checker.checkDontNotice(stateOf(lastCheck, remainSecond),
                    lastCheck.plusSeconds(elapsedSeconds))).isTrue();
        }

        @Test
        @DisplayName("stateList 为空时所有 state 都不需要调 API → checkDontNotice 返回 true")
        void shouldReturnTrue_whenStateListEmpty() {
            assertThat(checker.checkDontNotice(Collections.emptyList(),
                    LocalDateTime.of(2024, 1, 1, 12, 0))).isTrue();
        }

        @Test
        @DisplayName("多个 state 中只要有一个需要调 API → checkDontNotice 返回 false")
        void shouldReturnFalse_whenAnyStateNeedsApiCall() {
            LocalDateTime lastCheck = LocalDateTime.of(2024, 1, 1, 12, 0);
            LocalDateTime now = lastCheck.plusSeconds(3601);
            VipNoticeStateDO notExpired = new VipNoticeStateDO();
            notExpired.setLastCheckTime(lastCheck);
            notExpired.setLastValue(7200L); // 未过期

            VipNoticeStateDO expired = new VipNoticeStateDO();
            expired.setLastCheckTime(lastCheck);
            expired.setLastValue(3600L); // 已过期

            assertThat(checker.checkDontNotice(List.of(notExpired, expired), now)).isFalse();
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
        private VipNoticeStateDAO stateDao;
        @InjectMocks
        private CooldownNoticeChecker checker;

        private VipNoticeConfigDO config;
        private List<VipNoticeStateDO> stateList;
        private final LocalDateTime now = LocalDateTime.of(2024, 6, 15, 12, 0);

        @BeforeEach
        void setUp() {
            config = new VipNoticeConfigDO();
            config.setId(1L);
            config.setUserId(100L);
            config.setEnableTypes(1);

            VipNoticeStateDO state = new VipNoticeStateDO();
            state.setUserId(100L);
            state.setNoticeType(1);
            state.setLastCheckTime(now.minusHours(1));
            state.setLastValue(0L);
            stateList = List.of(state);
        }

        @Test
        @DisplayName("冷却未过期时不调用 API，返回空列表")
        void returnsEmpty_whenCooldownNotExpired() {
            VipNoticeStateDO state = new VipNoticeStateDO();
            state.setLastCheckTime(now.minusMinutes(10));
            state.setLastValue(7200L); // 2 小时 CD，未过期
            List<String> result = checker.checkAndUpdate(config, List.of(state), now);
            assertThat(result).isEmpty();
            verifyNoInteractions(tornApi);
        }

        @Test
        @DisplayName("没有 API Key 时返回空列表")
        void returnsEmpty_whenNoApiKey() {
            when(apiKeyConfig.getKeyByUserId(100L)).thenReturn(null);
            List<String> result = checker.checkAndUpdate(config, stateList, now);
            assertThat(result).isEmpty();
            verifyNoInteractions(tornApi);
        }

        @Test
        @DisplayName("Drug CD = 0 时返回吃药提醒消息")
        void returnsMessage_whenDrugCdIsZero() {
            TornApiKeyDO key = new TornApiKeyDO();
            when(apiKeyConfig.getKeyByUserId(100L)).thenReturn(key);
            TornUserCooldownVO resp = mockCooldownResponseFull(0, 5000);
            when(tornApi.sendRequest(any(TornUserCooldownDTO.class), eq(key), eq(TornUserCooldownVO.class)))
                    .thenReturn(resp);
            mockStateUpdate();
            List<String> result = checker.checkAndUpdate(config, stateList, now);
            assertThat(result).containsExactly("大郎, 该吃药了");
        }

        @Test
        @DisplayName("Drug CD > 0 时返回空列表")
        void returnsEmpty_whenDrugCdGreaterThanZero() {
            TornApiKeyDO key = new TornApiKeyDO();
            when(apiKeyConfig.getKeyByUserId(100L)).thenReturn(key);
            TornUserCooldownVO resp = mockCooldownResponseFull(3600, 300);
            when(tornApi.sendRequest(any(TornUserCooldownDTO.class), eq(key), eq(TornUserCooldownVO.class)))
                    .thenReturn(resp);
            mockStateUpdate();
            List<String> result = checker.checkAndUpdate(config, stateList, now);
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Booster CD = 0 时返回 Booster 提醒消息")
        void returnsMessage_whenBoosterCdIsZero() {
            config.setEnableTypes(VipNoticeTypeEnum.BOOSTER.getBit());
            VipNoticeStateDO boosterState = new VipNoticeStateDO();
            boosterState.setUserId(100L);
            boosterState.setNoticeType(VipNoticeTypeEnum.BOOSTER.getBit());
            boosterState.setLastCheckTime(now.minusHours(1));
            boosterState.setLastValue(0L);
            TornApiKeyDO key = new TornApiKeyDO();
            when(apiKeyConfig.getKeyByUserId(100L)).thenReturn(key);
            TornUserCooldownVO resp = mockCooldownResponseFull(3600, 0);
            when(tornApi.sendRequest(any(TornUserCooldownDTO.class), eq(key), eq(TornUserCooldownVO.class))).thenReturn(resp);
            mockStateUpdate();
            List<String> result = checker.checkAndUpdate(config, List.of(boosterState), now);
            assertThat(result).containsExactly("Booster CD空了");
        }

        @Test
        @DisplayName("Drug 和 Booster CD 都为 0 时同时返回两条消息")
        void returnsBoth_whenDrugAndBoosterCdZero() {
            config.setEnableTypes(VipNoticeTypeEnum.DRUG.getBit() | VipNoticeTypeEnum.BOOSTER.getBit());
            VipNoticeStateDO drugState = new VipNoticeStateDO();
            drugState.setNoticeType(VipNoticeTypeEnum.DRUG.getBit());
            drugState.setLastCheckTime(now.minusHours(1));
            drugState.setLastValue(0L);
            VipNoticeStateDO boosterState = new VipNoticeStateDO();
            boosterState.setNoticeType(VipNoticeTypeEnum.BOOSTER.getBit());
            boosterState.setLastCheckTime(now.minusHours(1));
            boosterState.setLastValue(0L);
            TornApiKeyDO key = new TornApiKeyDO();
            when(apiKeyConfig.getKeyByUserId(100L)).thenReturn(key);
            TornUserCooldownVO resp = mockCooldownResponseFull(0, 0);
            when(tornApi.sendRequest(any(TornUserCooldownDTO.class), eq(key), eq(TornUserCooldownVO.class))).thenReturn(resp);
            mockStateUpdate();
            List<String> result = checker.checkAndUpdate(config, List.of(drugState, boosterState), now);
            assertThat(result).containsExactlyInAnyOrder("大郎, 该吃药了", "Booster CD空了");
        }

        @Test
        @DisplayName("调用API后应更新 DB")
        void shouldUpdateDatabaseAfterApiCall() {
            TornApiKeyDO key = new TornApiKeyDO();
            when(apiKeyConfig.getKeyByUserId(100L)).thenReturn(key);
            TornUserCooldownVO resp = mockCooldownResponseFull(500, 300);
            when(tornApi.sendRequest(any(TornUserCooldownDTO.class), eq(key), eq(TornUserCooldownVO.class)))
                    .thenReturn(resp);
            mockStateUpdate();
            checker.checkAndUpdate(config, stateList, now);
            verify(stateDao).lambdaUpdate();
        }

        private TornUserCooldownVO mockCooldownResponseFull(int drugCd, int boosterCd) {
            TornUserCooldownVO resp = mock(TornUserCooldownVO.class);
            TornUserCooldownDataVO cooldowns = mock(TornUserCooldownDataVO.class);
            when(resp.getCooldowns()).thenReturn(cooldowns);
            when(cooldowns.getDrug()).thenReturn(drugCd);
            when(cooldowns.getBooster()).thenReturn(boosterCd);
            return resp;
        }

        @SuppressWarnings("unchecked")
        private void mockStateUpdate() {
            var wrapper = mock(com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper.class);
            when(stateDao.lambdaUpdate()).thenReturn(wrapper);
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
        private VipNoticeConfigDAO configDao;
        @Mock
        private VipNoticeStateDAO stateDao;
        @InjectMocks
        private BarNoticeChecker checker;

        private VipNoticeConfigDO config;
        private List<VipNoticeStateDO> stateList;
        private final LocalDateTime now = LocalDateTime.of(2024, 6, 15, 12, 0);

        @BeforeEach
        void setUp() {
            config = new VipNoticeConfigDO();
            config.setId(1L);
            config.setUserId(100L);
            config.setEnableTypes(VipNoticeTypeEnum.ENERGY.getBit() + VipNoticeTypeEnum.NERVE.getBit());

            // 两个 state：energy 和 nerve，均已过期（1小时前检查，lastValue=60s）
            VipNoticeStateDO energyState = new VipNoticeStateDO();
            energyState.setLastCheckTime(now.minusHours(1));
            energyState.setNoticeType(VipNoticeTypeEnum.ENERGY.getBit());
            energyState.setLastValue(60L);

            VipNoticeStateDO nerveState = new VipNoticeStateDO();
            nerveState.setLastCheckTime(now.minusHours(1));
            nerveState.setNoticeType(VipNoticeTypeEnum.NERVE.getBit());
            nerveState.setLastValue(60L);

            stateList = List.of(energyState, nerveState);
        }

        @Test
        @DisplayName("Energy 和 Nerve 都未过期时不调 API")
        void returnsEmpty_whenBothNotExpired() {
            VipNoticeStateDO energyState = new VipNoticeStateDO();
            energyState.setLastCheckTime(now.minusMinutes(10));
            energyState.setNoticeType(VipNoticeTypeEnum.ENERGY.getBit());
            energyState.setLastValue(7200L);

            VipNoticeStateDO nerveState = new VipNoticeStateDO();
            nerveState.setLastCheckTime(now.minusMinutes(10));
            nerveState.setNoticeType(VipNoticeTypeEnum.NERVE.getBit());
            nerveState.setLastValue(7200L);

            List<String> result = checker.checkAndUpdate(config, List.of(energyState, nerveState), now);
            assertThat(result).isEmpty();
            verifyNoInteractions(tornApi);
        }

        @Test
        @DisplayName("只有 Energy 满了")
        void returnsEnergyOnly() {
            config.setEnableTypes(VipNoticeTypeEnum.ENERGY.getBit());
            TornApiKeyDO key = new TornApiKeyDO();
            when(apiKeyConfig.getKeyByUserId(100L)).thenReturn(key);
            TornUserBarVO resp = mockBarResponse(0, 3600);
            when(tornApi.sendRequest(any(TornUserBarDTO.class), eq(key), eq(TornUserBarVO.class)))
                    .thenReturn(resp);
            mockStateUpdate();
            List<String> result = checker.checkAndUpdate(config, stateList, now);
            assertThat(result).containsExactly("Energy满了");
        }

        @Test
        @DisplayName("只有 Nerve 满了")
        void returnsNerveOnly() {
            config.setEnableTypes(VipNoticeTypeEnum.NERVE.getBit());
            TornApiKeyDO key = new TornApiKeyDO();
            when(apiKeyConfig.getKeyByUserId(100L)).thenReturn(key);
            TornUserBarVO resp = mockBarResponse(3600, 0);
            when(tornApi.sendRequest(any(TornUserBarDTO.class), eq(key), eq(TornUserBarVO.class)))
                    .thenReturn(resp);
            mockStateUpdate();
            List<String> result = checker.checkAndUpdate(config, stateList, now);
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
            mockStateUpdate();
            List<String> result = checker.checkAndUpdate(config, stateList, now);
            assertThat(result).containsExactlyInAnyOrder("Energy满了", "Nerve满了");
        }

        @Test
        @DisplayName("Energy 满了但在暂停期内，不发送 Energy 通知")
        void returnsEmpty_whenEnergyPaused() {
            config.setEnableTypes(VipNoticeTypeEnum.ENERGY.getBit() | VipNoticeTypeEnum.NERVE.getBit());
            config.setPauseEnergyUntil(LocalDateTime.now().plusHours(1));
            TornApiKeyDO key = new TornApiKeyDO();
            when(apiKeyConfig.getKeyByUserId(100L)).thenReturn(key);
            TornUserBarVO resp = mockBarResponse(0, 3600);
            when(tornApi.sendRequest(any(TornUserBarDTO.class), eq(key), eq(TornUserBarVO.class))).thenReturn(resp);
            mockStateUpdate();
            List<String> result = checker.checkAndUpdate(config, stateList, now);
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Energy 暂停已过期，正常发送通知")
        void returnsEnergy_whenPauseExpired() {
            config.setEnableTypes(VipNoticeTypeEnum.ENERGY.getBit());
            config.setPauseEnergyUntil(now.minusMinutes(1));
            TornApiKeyDO key = new TornApiKeyDO();
            when(apiKeyConfig.getKeyByUserId(100L)).thenReturn(key);
            TornUserBarVO resp = mockBarResponse(0, 3600);
            when(tornApi.sendRequest(any(TornUserBarDTO.class), eq(key), eq(TornUserBarVO.class))).thenReturn(resp);
            mockStateUpdate();
            List<String> result = checker.checkAndUpdate(config, stateList, now);
            assertThat(result).containsExactly("Energy满了");
        }

        @Test
        @DisplayName("都不为 0 时返回空")
        void returnsEmpty_whenNeitherZero() {
            TornApiKeyDO key = new TornApiKeyDO();
            when(apiKeyConfig.getKeyByUserId(100L)).thenReturn(key);
            TornUserBarVO resp = mockBarResponse(1800, 3600);
            when(tornApi.sendRequest(any(TornUserBarDTO.class), eq(key), eq(TornUserBarVO.class)))
                    .thenReturn(resp);
            mockStateUpdate();
            List<String> result = checker.checkAndUpdate(config, stateList, now);
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("没有 API Key 时返回空")
        void returnsEmpty_whenNoApiKey() {
            when(apiKeyConfig.getKeyByUserId(100L)).thenReturn(null);
            List<String> result = checker.checkAndUpdate(config, stateList, now);
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

        @SuppressWarnings("unchecked")
        private void mockStateUpdate() {
            var wrapper = mock(com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper.class);
            when(stateDao.lambdaUpdate()).thenReturn(wrapper);
            when(wrapper.set(any(), any())).thenReturn(wrapper);
            when(wrapper.eq(any(), any())).thenReturn(wrapper);
            when(wrapper.update()).thenReturn(true);
        }
    }

    // ==================== TravelNoticeChecker 测试 ====================
    @Nested
    @DisplayName("TravelNoticeChecker")
    class TravelNoticeCheckerTest {
        @Mock
        private TornApi tornApi;
        @Mock
        private TornApiKeyConfig apiKeyConfig;
        @Mock
        private VipNoticeStateDAO stateDao;
        @InjectMocks
        private TravelNoticeChecker checker;

        private VipNoticeConfigDO config;
        private VipNoticeStateDO state;
        private List<VipNoticeStateDO> stateList;
        private final LocalDateTime now = LocalDateTime.of(2024, 6, 15, 12, 0);

        @BeforeEach
        void setUp() {
            config = new VipNoticeConfigDO();
            config.setUserId(100L);
            config.setEnableTypes(VipNoticeTypeEnum.TRAVEL.getBit());

            state = new VipNoticeStateDO();
            state.setId(1L);
            state.setLastCheckTime(now.minusHours(1));
            state.setLastValue(0L);
            stateList = List.of(state);
        }

        @Test
        @DisplayName("未过期时不调用 API，返回空列表")
        void returnsEmpty_whenNotExpired() {
            state.setLastCheckTime(now.minusMinutes(5));
            state.setLastValue(7200L);
            List<String> result = checker.checkAndUpdate(config, stateList, now);
            assertThat(result).isEmpty();
            verifyNoInteractions(tornApi);
        }

        @Test
        @DisplayName("没有 API Key 时返回空列表")
        void returnsEmpty_whenNoApiKey() {
            when(apiKeyConfig.getKeyByUserId(100L)).thenReturn(null);
            List<String> result = checker.checkAndUpdate(config, stateList, now);
            assertThat(result).isEmpty();
            verifyNoInteractions(tornApi);
        }

        @Test
        @DisplayName("状态为 ABROAD 且未暂停时，返回滞留消息")
        void returnsMessage_whenAbroadAndNotPaused() {
            TornApiKeyDO key = new TornApiKeyDO();
            when(apiKeyConfig.getKeyByUserId(100L)).thenReturn(key);
            TornUserVO userResp = mockUserStatus(TornUserStatusEnum.ABROAD.getCode());
            when(tornApi.sendRequest(any(TornUserDTO.class), eq(key), eq(TornUserVO.class))).thenReturn(userResp);
            mockStateUpdate();
            List<String> result = checker.checkAndUpdate(config, stateList, now);
            assertThat(result).containsExactly("在海外滞留了");
        }

        @Test
        @DisplayName("状态为 ABROAD 且暂停已过期，返回滞留消息")
        void returnsMessage_whenAbroadAndPauseExpired() {
            config.setPauseTravelUntil(now.minusMinutes(1));
            TornApiKeyDO key = new TornApiKeyDO();
            when(apiKeyConfig.getKeyByUserId(100L)).thenReturn(key);
            TornUserVO userResp = mockUserStatus(TornUserStatusEnum.ABROAD.getCode());
            when(tornApi.sendRequest(any(TornUserDTO.class), eq(key), eq(TornUserVO.class))).thenReturn(userResp);
            mockStateUpdate();
            List<String> result = checker.checkAndUpdate(config, stateList, now);
            assertThat(result).containsExactly("在海外滞留了");
        }

        @Test
        @DisplayName("状态为 TRAVELING，目的地非Torn，存入到达剩余秒数")
        void savesArrivalSeconds_whenTravelingToForeign() {
            TornApiKeyDO key = new TornApiKeyDO();
            when(apiKeyConfig.getKeyByUserId(100L)).thenReturn(key);
            TornUserVO userResp = mockUserStatus(TornUserStatusEnum.TRAVELING.getCode());
            when(tornApi.sendRequest(any(TornUserDTO.class), eq(key), eq(TornUserVO.class))).thenReturn(userResp);

            LocalDateTime arrivalTime = now.plusSeconds(1800);
            TornUserTravelVO cayman = mockTravelResponse("Cayman Islands", arrivalTime);
            when(tornApi.sendRequest(any(TornUserTravelDTO.class), eq(key), eq(TornUserTravelVO.class)))
                    .thenReturn(cayman);
            mockStateUpdate();

            List<String> result = checker.checkAndUpdate(config, stateList, now);
            assertThat(result).isEmpty();
            verify(stateDao.lambdaUpdate().set(any(), eq(1800L)).eq(any(), eq(1L))).update();
        }

        @Test
        @DisplayName("状态为 TRAVELING，目的地是 Torn，nextCheckSecond 额外加 600")
        void addsExtraDelay_whenTravelingBackToTorn() {
            TornApiKeyDO key = new TornApiKeyDO();
            when(apiKeyConfig.getKeyByUserId(100L)).thenReturn(key);
            TornUserVO userResp = mockUserStatus(TornUserStatusEnum.TRAVELING.getCode());
            when(tornApi.sendRequest(any(TornUserDTO.class), eq(key), eq(TornUserVO.class))).thenReturn(userResp);

            LocalDateTime arrivalTime = now.plusSeconds(1800);
            TornUserTravelVO torn = mockTravelResponse("Torn", arrivalTime);
            when(tornApi.sendRequest(any(TornUserTravelDTO.class), eq(key), eq(TornUserTravelVO.class))).thenReturn(torn);

            mockStateUpdate();
            checker.checkAndUpdate(config, stateList, now);
            ArgumentCaptor<Object> valueCaptor = ArgumentCaptor.forClass(Object.class);
            verify(stateDao.lambdaUpdate(), atLeastOnce()).set(any(), valueCaptor.capture());
            assertThat(valueCaptor.getAllValues()).contains(2400L);
        }

        @Test
        @DisplayName("状态为 TRAVELING，到达时间已过（负数），nextCheckSecond 归零")
        void clampsToZero_whenArrivalAlreadyPassed() {
            TornApiKeyDO key = new TornApiKeyDO();
            when(apiKeyConfig.getKeyByUserId(100L)).thenReturn(key);
            TornUserVO userResp = mockUserStatus(TornUserStatusEnum.TRAVELING.getCode());
            when(tornApi.sendRequest(any(TornUserDTO.class), eq(key), eq(TornUserVO.class))).thenReturn(userResp);

            LocalDateTime arrivalTime = now.minusSeconds(300);
            TornUserTravelVO cayman = mockTravelResponse("Cayman Islands", arrivalTime);
            when(tornApi.sendRequest(any(TornUserTravelDTO.class), eq(key), eq(TornUserTravelVO.class)))
                    .thenReturn(cayman);
            mockStateUpdate();
            checker.checkAndUpdate(config, stateList, now);
            ArgumentCaptor<Object> valueCaptor = ArgumentCaptor.forClass(Object.class);
            verify(stateDao.lambdaUpdate(), atLeastOnce()).set(any(), valueCaptor.capture());
            assertThat(valueCaptor.getAllValues()).contains(0L);
        }

        @Test
        @DisplayName("其他状态（如在国内）存入 600 秒，返回空列表")
        void saves600_whenStatusIsOther() {
            TornApiKeyDO key = new TornApiKeyDO();
            when(apiKeyConfig.getKeyByUserId(100L)).thenReturn(key);
            TornUserVO userResp = mockUserStatus("Okay");
            when(tornApi.sendRequest(any(TornUserDTO.class), eq(key), eq(TornUserVO.class))).thenReturn(userResp);
            mockStateUpdate();

            List<String> result = checker.checkAndUpdate(config, stateList, now);
            assertThat(result).isEmpty();
            ArgumentCaptor<Object> valueCaptor = ArgumentCaptor.forClass(Object.class);
            verify(stateDao.lambdaUpdate(), atLeastOnce()).set(any(), valueCaptor.capture());
            assertThat(valueCaptor.getAllValues()).contains(600);
        }

        private TornUserVO mockUserStatus(String statusCode) {
            TornUserVO resp = mock(TornUserVO.class);
            TornUserProfileVO profile = mock(TornUserProfileVO.class);
            TornUserStatusVO status = mock(TornUserStatusVO.class);
            when(resp.getProfile()).thenReturn(profile);
            when(profile.getStatus()).thenReturn(status);
            when(status.getState()).thenReturn(statusCode);
            return resp;
        }

        private TornUserTravelVO mockTravelResponse(String destination, LocalDateTime arrivalTime) {
            TornUserTravelVO resp = mock(TornUserTravelVO.class);
            TornUserTravelDataVO travel = mock(TornUserTravelDataVO.class);
            when(resp.getTravel()).thenReturn(travel);
            when(travel.getDestination()).thenReturn(destination);
            when(travel.getArrivalAt()).thenReturn(DateTimeUtils.convertToTimestamp(arrivalTime));
            return resp;
        }

        @SuppressWarnings("unchecked")
        private com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper<VipNoticeStateDO> mockStateUpdate() {
            var wrapper = mock(com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper.class);
            when(stateDao.lambdaUpdate()).thenReturn(wrapper);
            when(wrapper.set(any(), any())).thenReturn(wrapper);
            when(wrapper.eq(any(), any())).thenReturn(wrapper);
            when(wrapper.update()).thenReturn(true);
            return wrapper;
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
        private TornQqUserManager qqUserManager;
        @Mock
        private SysSettingManager settingManager;
        @Mock
        private VipSubscribeDAO subscribeDao;
        @Mock
        private VipNoticeConfigDAO noticeConfigDao;
        @Mock
        private VipNoticeStateDAO noticeStateDao;
        @Mock
        private ProjectProperty projectProperty;

        @Test
        @DisplayName("非生产环境不执行")
        void shouldSkip_whenNotProd() {
            when(projectProperty.getEnv()).thenReturn("dev");
            when(settingManager.getSettingValue(SettingConstants.KEY_VIP_NOTICE)).thenReturn("true");
            VipNoticeManager manager = new VipNoticeManager(
                    virtualThreadExecutor, bot, List.of(), qqUserManager, settingManager,
                    subscribeDao, noticeConfigDao, noticeStateDao, projectProperty);
            manager.notice();
            verifyNoInteractions(subscribeDao);
        }

        @Test
        @SuppressWarnings("unchecked")
        @DisplayName("VIP列表为空时不查询notice表")
        void shouldSkip_whenNoVipUsers() {
            when(projectProperty.getEnv()).thenReturn(BotConstants.ENV_PROD);
            when(settingManager.getSettingValue(SettingConstants.KEY_VIP_NOTICE)).thenReturn("true");
            var queryWrapper = mock(com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper.class);
            when(subscribeDao.lambdaQuery()).thenReturn(queryWrapper);
            when(queryWrapper.ge(any(), any())).thenReturn(queryWrapper);
            when(queryWrapper.list()).thenReturn(Collections.emptyList());
            VipNoticeManager manager = new VipNoticeManager(
                    virtualThreadExecutor, bot, List.of(), qqUserManager, settingManager,
                    subscribeDao, noticeConfigDao, noticeStateDao, projectProperty);
            manager.notice();
            verifyNoInteractions(noticeConfigDao);
        }

        @Test
        @SuppressWarnings("unchecked")
        @DisplayName("Checker 抛异常不影响其他用户")
        void shouldHandleExceptionGracefully() {
            when(projectProperty.getEnv()).thenReturn(BotConstants.ENV_PROD);
            when(settingManager.getSettingValue(SettingConstants.KEY_VIP_NOTICE)).thenReturn("true");

            VipSubscribeDO vip = new VipSubscribeDO();
            vip.setUserId(100L);
            vip.setQqId(200L);
            vip.setEndDate(LocalDate.of(2099, 12, 31));

            var subQueryWrapper = mock(com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper.class);
            when(subscribeDao.lambdaQuery()).thenReturn(subQueryWrapper);
            when(subQueryWrapper.ge(any(), any())).thenReturn(subQueryWrapper);
            when(subQueryWrapper.list()).thenReturn(List.of(vip));

            var noticeQueryWrapper = mock(com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper.class);
            when(noticeConfigDao.lambdaQuery()).thenReturn(noticeQueryWrapper);
            when(noticeQueryWrapper.in(any(), anyList())).thenReturn(noticeQueryWrapper);
            when(noticeQueryWrapper.list()).thenReturn(Collections.emptyList());

            var stateQueryWrapper = mock(com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper.class);
            when(noticeStateDao.lambdaQuery()).thenReturn(stateQueryWrapper);
            when(stateQueryWrapper.in(any(), anyList())).thenReturn(stateQueryWrapper);
            when(stateQueryWrapper.list()).thenReturn(Collections.emptyList());

            // 适配新接口签名
            VipNoticeManager manager = buildFailApiCall();
            assertDoesNotThrow(manager::notice);
        }

        private VipNoticeManager buildFailApiCall() {
            VipNoticeChecker failingChecker = new VipNoticeChecker() {
                @Override
                public List<VipNoticeTypeEnum> getType() {
                    return Collections.emptyList();
                }

                @Override
                public List<String> checkAndUpdate(VipNoticeConfigDO config, List<VipNoticeStateDO> stateList, LocalDateTime checkTime) {
                    throw new RuntimeException("API boom!");
                }
            };

            return new VipNoticeManager(virtualThreadExecutor, bot, List.of(failingChecker), qqUserManager,
                    settingManager, subscribeDao, noticeConfigDao, noticeStateDao, projectProperty);
        }
    }

    // ==================== 扩展性验证 ====================
    @Nested
    @DisplayName("扩展性验证 - 添加新 Checker 无需修改 Manager")
    class ExtensibilityTest {
        @Test
        @DisplayName("新的 NoticeChecker 实现可以被 Manager 自动收集")
        void newCheckerIsCollectedAutomatically() {
            VipNoticeChecker travelChecker = new VipNoticeChecker() {
                @Override
                public List<VipNoticeTypeEnum> getType() {
                    return List.of(VipNoticeTypeEnum.TRAVEL);
                }

                @Override
                public List<String> checkAndUpdate(VipNoticeConfigDO config, List<VipNoticeStateDO> stateList, LocalDateTime checkTime) {
                    return List.of("你的旅行结束了");
                }
            };
            VipNoticeChecker drugChecker = new VipNoticeChecker() {
                @Override
                public List<VipNoticeTypeEnum> getType() {
                    return List.of(VipNoticeTypeEnum.DRUG);
                }

                @Override
                public List<String> checkAndUpdate(VipNoticeConfigDO config, List<VipNoticeStateDO> stateList, LocalDateTime checkTime) {
                    return List.of("大郎, 该吃药了");
                }
            };

            VipNoticeConfigDO config = new VipNoticeConfigDO();
            config.setUserId(100L);
            LocalDateTime now = LocalDateTime.of(2024, 6, 15, 12, 0);

            List<String> allMessages = Stream.of(drugChecker, travelChecker)
                    .flatMap(c -> c.checkAndUpdate(config, Collections.emptyList(), now).stream())
                    .toList();
            assertThat(allMessages).containsExactlyInAnyOrder("大郎, 该吃药了", "你的旅行结束了");
        }
    }
}