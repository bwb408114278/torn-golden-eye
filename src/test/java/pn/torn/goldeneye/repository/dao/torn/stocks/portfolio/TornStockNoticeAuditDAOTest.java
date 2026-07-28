package pn.torn.goldeneye.repository.dao.torn.stocks.portfolio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pn.torn.goldeneye.repository.mapper.torn.stocks.portfolio.TornStockNoticeAuditMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 股票通知审计DAO测试,覆盖批量状态更新的空列表保护。
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.28
 */
@DisplayName("股票通知审计DAO测试")
@ExtendWith(MockitoExtension.class)
class TornStockNoticeAuditDAOTest {

    @Mock
    private TornStockNoticeAuditMapper mapper;

    @InjectMocks
    private TornStockNoticeAuditDAO dao;

    @Test
    @DisplayName("批量标记失败_空通知列表不调用Mapper")
    void markFailedByIds_emptyNoticeIds_returnsZeroWithoutMapperCall() {
        assertEquals(0, dao.markFailedByIds(List.of(), "关联批次不存在"));
        verify(mapper, never()).markFailedByIds(List.of(), "关联批次不存在");
    }

    @Test
    @DisplayName("批量标记成功_空通知列表不调用Mapper")
    void markSentByIds_emptyNoticeIds_returnsZeroWithoutMapperCall() {
        assertEquals(0, dao.markSentByIds(List.of()));
        verify(mapper, never()).markSentByIds(List.of());
    }

    @Test
    @DisplayName("批量标记发送失败_空通知列表不调用Mapper")
    void markSendFailedByIds_emptyNoticeIds_returnsZeroWithoutMapperCall() {
        assertEquals(0, dao.markSendFailedByIds(List.of(), "发送失败"));
        verify(mapper, never()).markSendFailedByIds(List.of(), "发送失败");
    }
}
