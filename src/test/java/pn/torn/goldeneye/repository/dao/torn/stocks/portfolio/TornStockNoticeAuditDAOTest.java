package pn.torn.goldeneye.repository.dao.torn.stocks.portfolio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import pn.torn.goldeneye.repository.mapper.torn.stocks.portfolio.TornStockNoticeAuditMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

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

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(dao, "baseMapper", mapper);
    }

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

    @Test
    @DisplayName("批量标记失败_非空列表委托Mapper并返回更新行数")
    void markFailedByIds_nonEmptyNoticeIds_delegatesAndReturnsAffectedRows() {
        when(mapper.markFailedByIds(List.of(1L, 2L), "关联批次不存在")).thenReturn(2);

        assertEquals(2, dao.markFailedByIds(List.of(1L, 2L), "关联批次不存在"));
        verify(mapper).markFailedByIds(List.of(1L, 2L), "关联批次不存在");
    }

    @Test
    @DisplayName("批量标记成功_非空列表委托Mapper并返回更新行数")
    void markSentByIds_nonEmptyNoticeIds_delegatesAndReturnsAffectedRows() {
        when(mapper.markSentByIds(List.of(1L, 2L))).thenReturn(2);

        assertEquals(2, dao.markSentByIds(List.of(1L, 2L)));
        verify(mapper).markSentByIds(List.of(1L, 2L));
    }

    @Test
    @DisplayName("批量标记发送失败_非空列表委托Mapper并返回更新行数")
    void markSendFailedByIds_nonEmptyNoticeIds_delegatesAndReturnsAffectedRows() {
        when(mapper.markSendFailedByIds(List.of(1L, 2L), "HTTP状态非2xx"))
                .thenReturn(2);

        assertEquals(2, dao.markSendFailedByIds(List.of(1L, 2L), "HTTP状态非2xx"));
        verify(mapper).markSendFailedByIds(List.of(1L, 2L), "HTTP状态非2xx");
    }
}
