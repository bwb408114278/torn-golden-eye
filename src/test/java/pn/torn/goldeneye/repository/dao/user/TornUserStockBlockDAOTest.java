package pn.torn.goldeneye.repository.dao.user;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用户股票屏蔽持久层真实PostgreSQL集成测试
 *
 * <p>通过{@code @Transactional + @Rollback}回滚保证开发库零残留，验证屏蔽写入、
 * 逻辑删除与再次屏蔽后的唯一索引兼容性。使用远离生产数据的专用用户ID隔离测试数据。</p>
 *
 * @author Bai
 * @version 1.9.3
 * @since 2026.10.23
 */
@SpringBootTest
@Tag("shared-db")
@Transactional
@Rollback
@DisplayName("用户股票屏蔽持久层真实PostgreSQL集成测试")
class TornUserStockBlockDAOTest {
    /**
     * 隔离测试用户ID(远离生产数据)
     */
    private static final long TEST_USER_ID = 990231001L;
    /**
     * 隔离测试股票ID(远离生产数据)
     */
    private static final int TEST_STOCKS_ID = 2099102;

    @Autowired
    private TornUserStockBlockDAO stockBlockDao;

    @Test
    @DisplayName("屏蔽写入按用户隔离, 取消屏蔽后可再次屏蔽同一支股票")
    void blockAndUnblockRoundTrip() {
        assertThat(stockBlockDao.selectBlockedStocksIds(TEST_USER_ID)).isEmpty();

        stockBlockDao.saveBlocks(TEST_USER_ID, List.of(TEST_STOCKS_ID));
        assertThat(stockBlockDao.selectBlockedStocksIds(TEST_USER_ID)).containsExactly(TEST_STOCKS_ID);

        stockBlockDao.removeBlocks(TEST_USER_ID, List.of(TEST_STOCKS_ID));
        assertThat(stockBlockDao.selectBlockedStocksIds(TEST_USER_ID)).isEmpty();

        stockBlockDao.saveBlocks(TEST_USER_ID, List.of(TEST_STOCKS_ID));
        assertThat(stockBlockDao.selectBlockedStocksIds(TEST_USER_ID)).containsExactly(TEST_STOCKS_ID);
        assertThat(stockBlockDao.selectBlockedStocksIds(TEST_USER_ID + 1)).isEmpty();
    }
}
