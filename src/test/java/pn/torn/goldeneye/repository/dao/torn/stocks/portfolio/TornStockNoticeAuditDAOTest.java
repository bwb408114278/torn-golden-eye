package pn.torn.goldeneye.repository.dao.torn.stocks.portfolio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import pn.torn.goldeneye.repository.mapper.torn.stocks.portfolio.TornStockNoticeAuditMapper;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

/**
 * 股票通知审计DAO测试,覆盖批量状态更新、领取与租约恢复的空入参保护。
 *
 * @author Bai
 * @version 1.6.1
 * @since 2026.07.28
 */
@DisplayName("股票通知审计DAO测试")
@ExtendWith(MockitoExtension.class)
class TornStockNoticeAuditDAOTest {

    /**
     * 测试用领取标识。
     */
    private static final String CLAIM_TOKEN = "claim-token-1";

    @Mock
    private TornStockNoticeAuditMapper mapper;

    @InjectMocks
    private TornStockNoticeAuditDAO dao;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(dao, "baseMapper", mapper);
    }

    @Test
    @DisplayName("批量标记最终失败_空通知列表不调用Mapper")
    void markFinalByIds_emptyNoticeIds_returnsZeroWithoutMapperCall() {
        assertEquals(0, dao.markFinalByIds(List.of(), "关联批次不存在"));
        verify(mapper, never()).markFinalByIds(List.of(), "关联批次不存在");
    }

    @Test
    @DisplayName("批量标记成功_空通知列表不调用Mapper")
    void markSentByIds_emptyNoticeIds_returnsZeroWithoutMapperCall() {
        assertEquals(0, dao.markSentByIds(List.of(), CLAIM_TOKEN));
        verify(mapper, never()).markSentByIds(List.of(), CLAIM_TOKEN);
    }

    @Test
    @DisplayName("批量标记发送失败_空通知列表不调用Mapper")
    void markSendFailedByIds_emptyNoticeIds_returnsZeroWithoutMapperCall() {
        assertEquals(0, dao.markSendFailedByIds(List.of(), CLAIM_TOKEN, "发送失败"));
        verify(mapper, never()).markSendFailedByIds(List.of(), CLAIM_TOKEN, "发送失败");
    }

    @Test
    @DisplayName("领取通知_空列表或空领取标识不调用Mapper")
    void claimByIds_emptyInput_returnsZeroWithoutMapperCall() {
        assertEquals(0, dao.claimByIds(List.of(), CLAIM_TOKEN));
        assertEquals(0, dao.claimByIds(List.of(1L), null));
        verify(mapper, never()).claimByIds(any(), any());
    }

    @Test
    @DisplayName("关联组领取_空关联标识或空领取标识不调用Mapper")
    void claimByRebalanceAssociationId_blankInput_returnsZeroWithoutMapperCall() {
        assertEquals(0, dao.claimByRebalanceAssociationId(null, CLAIM_TOKEN));
        assertEquals(0, dao.claimByRebalanceAssociationId("ALPHA_REBALANCE:1", " "));
        verify(mapper, never()).claimByRebalanceAssociationId(any(), any());
    }

    @Test
    @DisplayName("释放领取_空领取标识不调用Mapper")
    void releaseClaim_blankToken_returnsZeroWithoutMapperCall() {
        assertEquals(0, dao.releaseClaim(null));
        verify(mapper, never()).releaseClaim(any());
    }

    @Test
    @DisplayName("租约恢复_空截止时间不调用Mapper")
    void recoverStaleClaims_nullStaleBefore_returnsZeroWithoutMapperCall() {
        assertEquals(0, dao.recoverStaleClaims(null));
        verify(mapper, never()).recoverStaleClaims(any());
    }

    @Test
    @DisplayName("批量标记最终失败_非空列表委托Mapper并返回更新行数")
    void markFinalByIds_nonEmptyNoticeIds_delegatesAndReturnsAffectedRows() {
        when(mapper.markFinalByIds(List.of(1L, 2L), "关联批次不存在")).thenReturn(2);

        assertEquals(2, dao.markFinalByIds(List.of(1L, 2L), "关联批次不存在"));
        verify(mapper).markFinalByIds(List.of(1L, 2L), "关联批次不存在");
    }

    @Test
    @DisplayName("批量标记成功_非空列表委托Mapper并返回更新行数")
    void markSentByIds_nonEmptyNoticeIds_delegatesAndReturnsAffectedRows() {
        when(mapper.markSentByIds(List.of(1L, 2L), CLAIM_TOKEN)).thenReturn(2);

        assertEquals(2, dao.markSentByIds(List.of(1L, 2L), CLAIM_TOKEN));
        verify(mapper).markSentByIds(List.of(1L, 2L), CLAIM_TOKEN);
    }

    @Test
    @DisplayName("批量标记发送失败_非空列表委托Mapper并返回更新行数")
    void markSendFailedByIds_nonEmptyNoticeIds_delegatesAndReturnsAffectedRows() {
        when(mapper.markSendFailedByIds(List.of(1L, 2L), CLAIM_TOKEN, "HTTP状态非2xx"))
                .thenReturn(2);

        assertEquals(2, dao.markSendFailedByIds(List.of(1L, 2L), CLAIM_TOKEN, "HTTP状态非2xx"));
        verify(mapper).markSendFailedByIds(List.of(1L, 2L), CLAIM_TOKEN, "HTTP状态非2xx");
    }

    @Test
    @DisplayName("领取通知_非空列表委托Mapper并返回领取行数")
    void claimByIds_nonEmptyNoticeIds_delegatesAndReturnsAffectedRows() {
        when(mapper.claimByIds(List.of(1L, 2L), CLAIM_TOKEN)).thenReturn(2);

        assertEquals(2, dao.claimByIds(List.of(1L, 2L), CLAIM_TOKEN));
        verify(mapper).claimByIds(List.of(1L, 2L), CLAIM_TOKEN);
    }

    @Test
    @DisplayName("查询可发送通知_委托Mapper")
    void selectSendableNotices_delegatesToMapper() {
        when(mapper.selectSendableNotices()).thenReturn(List.of());

        assertTrue(dao.selectSendableNotices().isEmpty());
        verify(mapper).selectSendableNotices();
    }

    @Test
    @DisplayName("存在可发送通知_委托Mapper")
    void existsSendableNotices_delegatesToMapper() {
        when(mapper.existsSendableNotices()).thenReturn(true);

        assertTrue(dao.existsSendableNotices());
        verify(mapper).existsSendableNotices();
    }

    @Test
    @DisplayName("租约恢复_非空截止时间委托Mapper并返回恢复行数")
    void recoverStaleClaims_nonNullStaleBefore_delegatesToMapper() {
        LocalDateTime staleBefore = LocalDateTime.of(2026, 9, 12, 9, 45);
        when(mapper.recoverStaleClaims(staleBefore)).thenReturn(1);

        assertEquals(1, dao.recoverStaleClaims(staleBefore));
        verify(mapper).recoverStaleClaims(staleBefore);
    }
}
