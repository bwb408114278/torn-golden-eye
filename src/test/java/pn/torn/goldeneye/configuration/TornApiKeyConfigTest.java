package pn.torn.goldeneye.configuration;

import com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pn.torn.goldeneye.repository.dao.setting.TornApiKeyDAO;
import pn.torn.goldeneye.repository.model.setting.TornApiKeyDO;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

/**
 * Torn Api Key配置类排序与并发回归测试
 * <p>
 * 验证全局池/帮派池候选快照排序契约:使用次数快照升序、并列按Key ID升序、
 * 帮派池保留needFactionAccess过滤; 并发"获取→归还"循环不触发
 * TimSort比较器契约异常(Comparison method violates its general contract),
 * 且循环结束后Key可再次获取、无泄漏。使用次数的持久化更新由DAO深度Mock隔离,
 * 不触达真实数据库。
 *
 * @author Bai
 * @version 1.3.5
 * @since 2026.08.20
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Torn Api Key配置类快照排序与并发回归测试")
class TornApiKeyConfigTest {

    @Mock
    private TornApiKeyDAO keyDao;

    @InjectMocks
    private TornApiKeyConfig apiKeyConfig;

    @BeforeEach
    void reloadKeys() {
        // 使用次数持久化更新链显式桩化: set/eq链式返回自身, update返回true, 不触达真实数据库
        @SuppressWarnings("unchecked")
        LambdaUpdateChainWrapper<TornApiKeyDO> updateWrapper = mock(LambdaUpdateChainWrapper.class);
        doReturn(updateWrapper).when(updateWrapper).set(any(), any());
        doReturn(updateWrapper).when(updateWrapper).eq(any(), any());
        doReturn(true).when(updateWrapper).update();
        doReturn(updateWrapper).when(keyDao).lambdaUpdate();
        doReturn(List.of(
                key(1L, 5, 100L, true),
                key(2L, 5, 100L, true),
                key(3L, 1, 100L, true))).when(keyDao).list();
        apiKeyConfig.reloadKeyData();
    }

    @Test
    @DisplayName("全局池_最少使用优先, 并列按ID升序, 全部占用时返回null")
    void getEnableKey_leastUsedFirstThenSmallerId() {
        TornApiKeyDO leastUsedKey = apiKeyConfig.getEnableKey();
        assertNotNull(leastUsedKey, "存在可用Key时不得返回null");
        assertEquals(3L, leastUsedKey.getId(), "使用次数1的Key必须最先返回");

        TornApiKeyDO tieKey = apiKeyConfig.getEnableKey();
        assertEquals(1L, tieKey.getId(), "使用次数并列5时必须按Key ID升序返回较小ID");

        TornApiKeyDO lastKey = apiKeyConfig.getEnableKey();
        assertEquals(2L, lastKey.getId(), "第三个可用Key依次返回");
        assertNull(apiKeyConfig.getEnableKey(), "全部Key占用时必须返回null");

        apiKeyConfig.returnKey(tieKey);
        TornApiKeyDO reusedKey = apiKeyConfig.getEnableKey();
        assertEquals(1L, reusedKey.getId(), "占用中的Key必须被跳过, 仅归还的Key可再次被获取");
        apiKeyConfig.returnKey(reusedKey);
    }

    @Test
    @DisplayName("帮派池_保留帮派权限过滤, 仅在该帮派池内选择")
    void getFactionKey_factionPoolFilterAndSelection() {
        doReturn(List.of(
                key(1L, 5, 100L, true),
                key(10L, 2, 100L, true),
                key(11L, 1, 100L, false),
                key(12L, 0, 200L, true))).when(keyDao).list();
        apiKeyConfig.reloadKeyData();

        TornApiKeyDO factionKey = apiKeyConfig.getFactionKey(100L, true);
        assertNotNull(factionKey, "帮派池存在可用Key时不得返回null");
        assertEquals(10L, factionKey.getId(), "needFactionAccess时无帮派权限Key必须被过滤");
        apiKeyConfig.returnKey(factionKey);

        TornApiKeyDO noFilterKey = apiKeyConfig.getFactionKey(100L, false);
        assertEquals(11L, noFilterKey.getId(), "无权限过滤时按使用次数选择, 且不越出帮派池");
        apiKeyConfig.returnKey(noFilterKey);

        assertNull(apiKeyConfig.getFactionKey(999L, true), "不存在的帮派池必须返回null");
        assertEquals(12L, apiKeyConfig.getFactionKey(200L, true).getId(), "其他帮派池Key互不串扰");
    }

    @Test
    @DisplayName("并发循环_全局与帮派获取归还不抛比较器异常且Key无泄漏")
    void concurrentGetAndReturn_noTimSortViolationAndNoLeak() throws InterruptedException {
        int threadCount = 8;
        int iterations = 2000;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        try {
            List<Callable<Void>> taskList = new ArrayList<>();
            for (int t = 0; t < threadCount; t++) {
                boolean useFaction = t % 2 == 0;
                taskList.add(() -> {
                    for (int i = 0; i < iterations; i++) {
                        TornApiKeyDO key = useFaction
                                ? apiKeyConfig.getFactionKey(100L, true)
                                : apiKeyConfig.getEnableKey();
                        if (key != null) {
                            apiKeyConfig.returnKey(key);
                        }
                    }
                    return null;
                });
            }

            List<Future<Void>> futureList = executor.invokeAll(taskList);
            for (Future<Void> future : futureList) {
                // 显式Executable消解方法引用与ThrowingSupplier重载的二义性
                assertDoesNotThrow((Executable) future::get, "并发获取/归还不得抛出TimSort比较器契约异常");
            }
        } finally {
            executor.shutdownNow();
        }

        TornApiKeyDO key = apiKeyConfig.getEnableKey();
        assertNotNull(key, "并发循环结束后Key必须可再次获取, 不存在泄漏");
        apiKeyConfig.returnKey(key);
    }

    /**
     * 构造测试Key
     *
     * @param id               Key ID
     * @param useCount         初始使用次数
     * @param factionId        所属帮派ID
     * @param hasFactionAccess 是否有帮派权限
     * @return 测试Key对象
     */
    private TornApiKeyDO key(long id, int useCount, Long factionId, boolean hasFactionAccess) {
        TornApiKeyDO keyDO = new TornApiKeyDO();
        keyDO.setId(id);
        keyDO.setUserId(id * 1000);
        keyDO.setFactionId(factionId);
        keyDO.setApiKey("rw-test-key-" + id);
        keyDO.setUseCount(useCount);
        keyDO.setHasFactionAccess(hasFactionAccess);
        return keyDO;
    }
}
