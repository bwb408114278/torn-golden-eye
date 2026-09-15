package pn.torn.goldeneye.torn.service.user;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import pn.torn.goldeneye.base.torn.TornApi;
import pn.torn.goldeneye.repository.dao.user.TornUserDAO;
import pn.torn.goldeneye.torn.model.user.TornUserVO;
import pn.torn.goldeneye.torn.model.user.profile.TornUserProfileDTO;
import pn.torn.goldeneye.torn.model.user.profile.TornUserProfileVO;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 注册时间补齐逻辑测试。
 *
 * @author Bai
 * @version 1.6.3
 * @since 2026.09.15
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("注册时间补齐测试")
class TornUserServiceTest {
    /**
     * 1767225600 = 2026-01-01T00:00:00Z，按项目约定+8小时为北京时间
     */
    private static final long SIGNED_UP = 1767225600L;
    private static final LocalDateTime REGISTER_TIME = LocalDateTime.of(2026, 1, 1, 8, 0, 0);

    @Mock
    private TornApi tornApi;
    @Mock
    private TornUserDAO userDao;
    @Mock
    private ThreadPoolTaskExecutor virtualThreadExecutor;

    private TornUserService userService;

    @BeforeEach
    void setUp() {
        userService = new TornUserService(tornApi, userDao, virtualThreadExecutor);
    }

    @Test
    @DisplayName("没有缺失注册时间的用户时不发起任何请求")
    void backfillRegisterTime_shouldSkipWhenNothingMissing() {
        when(userDao.queryIdListWithoutRegisterTime(List.of(1L, 2L))).thenReturn(List.of());

        userService.backfillRegisterTime(List.of(1L, 2L));

        verifyNoInteractions(tornApi);
    }

    @Test
    @DisplayName("只为缺失注册时间的用户请求并按返回时间回写")
    void backfillRegisterTime_shouldOnlyRefreshMissingUsers() {
        stubInlineExecutor();
        when(userDao.queryIdListWithoutRegisterTime(List.of(1L, 2L, 3L))).thenReturn(List.of(2L));
        when(tornApi.sendRequest(any(TornUserProfileDTO.class), eq(TornUserVO.class))).thenReturn(profile());

        userService.backfillRegisterTime(List.of(1L, 2L, 3L));

        verify(tornApi).sendRequest(any(TornUserProfileDTO.class), eq(TornUserVO.class));
        verify(userDao).updateRegisterTimeIfAbsent(2L, REGISTER_TIME);
    }

    @Test
    @DisplayName("单个用户请求失败不阻断其余用户")
    void backfillRegisterTime_shouldIsolateSingleUserFailure() {
        stubInlineExecutor();
        when(userDao.queryIdListWithoutRegisterTime(List.of(1L, 2L))).thenReturn(List.of(1L, 2L));
        when(tornApi.sendRequest(any(TornUserProfileDTO.class), eq(TornUserVO.class)))
                .thenThrow(new IllegalStateException("请求失败"))
                .thenReturn(profile());

        userService.backfillRegisterTime(List.of(1L, 2L));

        verify(userDao, times(1)).updateRegisterTimeIfAbsent(anyLong(), eq(REGISTER_TIME));
    }

    private void stubInlineExecutor() {
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(virtualThreadExecutor).execute(any(Runnable.class));
    }

    private TornUserVO profile() {
        TornUserProfileVO profile = new TornUserProfileVO();
        profile.setId(2L);
        profile.setSignedUp(SIGNED_UP);
        TornUserVO resp = new TornUserVO();
        resp.setProfile(profile);
        return resp;
    }
}
