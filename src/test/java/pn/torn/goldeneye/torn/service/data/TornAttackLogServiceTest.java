package pn.torn.goldeneye.torn.service.data;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import pn.torn.goldeneye.base.torn.TornApi;
import pn.torn.goldeneye.configuration.TornApiKeyConfig;
import pn.torn.goldeneye.repository.dao.torn.TornAttackLogDAO;
import pn.torn.goldeneye.repository.model.torn.TornAttackLogDO;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 攻击日志保存服务测试。
 *
 * @author Bai
 * @version 1.3.5
 * @since 2026.08.20
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("攻击日志保存服务测试")
class TornAttackLogServiceTest {

    @Mock
    private ThreadPoolTaskExecutor virtualThreadExecutor;

    @Mock
    private TornApi tornApi;

    @Mock
    private TornApiKeyConfig apiKeyConfig;

    @Mock
    private TornAttackLogDAO attackLogDao;

    @Captor
    private ArgumentCaptor<List<TornAttackLogDO>> logListCaptor;

    @Test
    @DisplayName("保存超过1000条攻击日志_按1000条分片写入")
    void saveLogData_overThousandLogs_savesInChunksOfThousand() {
        TornAttackLogService service = new TornAttackLogService(
                virtualThreadExecutor, tornApi, apiKeyConfig, attackLogDao);
        List<TornAttackLogDO> logs = IntStream.range(0, 2001)
                .mapToObj(index -> new TornAttackLogDO())
                .toList();

        service.saveLogData(List.of(logs));

        verify(attackLogDao, times(3)).insertIgnoreConflict(logListCaptor.capture());
        List<List<TornAttackLogDO>> batches = logListCaptor.getAllValues();
        assertEquals(List.of(1000, 1000, 1), batches.stream().map(List::size).toList());
    }
}
