package pn.torn.goldeneye.napcat.strategy.user;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pn.torn.goldeneye.base.torn.TornApi;
import pn.torn.goldeneye.base.torn.TornReqParamV2;
import pn.torn.goldeneye.configuration.TornApiKeyConfig;
import pn.torn.goldeneye.napcat.receive.msg.QqRecMsgSender;
import pn.torn.goldeneye.napcat.send.msg.param.QqMsgParam;
import pn.torn.goldeneye.napcat.send.msg.param.TextQqMsg;
import pn.torn.goldeneye.repository.dao.torn.stocks.TornStocksDAO;
import pn.torn.goldeneye.repository.dao.user.TornUserStockBlockDAO;
import pn.torn.goldeneye.repository.model.setting.TornApiKeyDO;
import pn.torn.goldeneye.repository.model.torn.stocks.TornStocksDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.manager.user.StocksBonusAnalyzeManager;
import pn.torn.goldeneye.torn.manager.user.TornUserManager;
import pn.torn.goldeneye.torn.model.user.stocks.TornUserStocksVO;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 股票分红屏蔽子指令测试
 *
 * @author Bai
 * @version 1.9.3
 * @since 2026.10.23
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("股票分红屏蔽子指令测试")
class StocksDividendBuildStrategyImplTest {
    private static final long QQ_ID = 880001L;
    private static final long USER_ID = 990001L;
    private static final long GROUP_ID = 123456L;

    @Mock
    private TornUserManager userManager;
    @Mock
    private TornApiKeyConfig apiKeyConfig;
    @Mock
    private TornApi tornApi;
    @Mock
    private StocksBonusAnalyzeManager stocksBonusAnalyzeManager;
    @Mock
    private TornStocksDAO stocksDao;
    @Mock
    private TornUserStockBlockDAO stockBlockDao;
    @InjectMocks
    private StocksDividendBuildStrategyImpl strategy;

    private QqRecMsgSender sender;
    private TornUserDO user;
    private TornStocksDO hrg;
    private TornStocksDO mun;

    @BeforeEach
    void setUp() {
        sender = new QqRecMsgSender();
        sender.setUserId(QQ_ID);
        user = new TornUserDO();
        user.setId(USER_ID);
        user.setNickname("Bai");
        hrg = createStock(22, "HRG", "Home Retail Group");
        mun = createStock(24, "MUN", "Munster Beverage Corp.");
    }

    @Test
    @DisplayName("无金额：返回用法与屏蔽子指令说明")
    void handle_noMoney_returnsUsage() {
        List<String> replies = texts(strategy.handle(GROUP_ID, sender, ""));

        assertThat(replies).singleElement().asString()
                .contains("请输入计算的金额")
                .contains("屏蔽股票: g#Stock分红购买#屏蔽#HRG")
                .contains("取消屏蔽: g#Stock分红购买#取消屏蔽#HRG");
    }

    @Test
    @DisplayName("屏蔽单支：股票简称大小写不敏感")
    void handle_blockSingle_storesBlockedStock() {
        stubBoundUser();
        when(stocksDao.list()).thenReturn(List.of(hrg, mun));
        when(stockBlockDao.selectBlockedStocksIds(USER_ID)).thenReturn(Set.of());

        List<String> replies = texts(strategy.handle(GROUP_ID, sender, "屏蔽#hrg"));

        assertThat(replies).anyMatch(reply -> reply.contains("已屏蔽: HRG(Home Retail Group)"))
                .anyMatch(reply -> reply.contains("当前屏蔽: HRG(Home Retail Group)"));
        verify(stockBlockDao).saveBlocks(USER_ID, List.of(22));
    }

    @Test
    @DisplayName("屏蔽多支：英文逗号与空格分隔，中文逗号兼容")
    void handle_blockMultiple_storesAllBlockedStocks() {
        stubBoundUser();
        when(stocksDao.list()).thenReturn(List.of(hrg, mun));
        when(stockBlockDao.selectBlockedStocksIds(USER_ID)).thenReturn(Set.of());

        List<String> replies = texts(strategy.handle(GROUP_ID, sender, "屏蔽#HRG, mun"));

        assertThat(replies).anyMatch(reply -> reply.contains("HRG(Home Retail Group), MUN(Munster Beverage Corp.)"));
        verify(stockBlockDao).saveBlocks(USER_ID, List.of(22, 24));
    }

    @Test
    @DisplayName("屏蔽未知简称：提示只需输入股票简称，不写库")
    void handle_blockUnknownShortName_promptsShortName() {
        stubBoundUser();
        when(stocksDao.list()).thenReturn(List.of(hrg));
        when(stockBlockDao.selectBlockedStocksIds(USER_ID)).thenReturn(Set.of());

        List<String> replies = texts(strategy.handle(GROUP_ID, sender, "屏蔽#XYZ"));

        assertThat(replies).anyMatch(reply -> reply.contains("没有找到股票简称: XYZ"))
                .anyMatch(reply -> reply.contains("请输入股票简称, 例如 HRG"));
        verify(stockBlockDao, never()).saveBlocks(any(), any());
    }

    @Test
    @DisplayName("重复屏蔽：提示之前已经屏蔽")
    void handle_blockDuplicate_reportsAlreadyBlocked() {
        stubBoundUser();
        when(stocksDao.list()).thenReturn(List.of(hrg));
        when(stockBlockDao.selectBlockedStocksIds(USER_ID)).thenReturn(Set.of(22));

        List<String> replies = texts(strategy.handle(GROUP_ID, sender, "屏蔽#HRG"));

        assertThat(replies).anyMatch(reply -> reply.contains("之前已经屏蔽: HRG(Home Retail Group)"));
        verify(stockBlockDao, never()).saveBlocks(any(), any());
    }

    @Test
    @DisplayName("无参数查看屏蔽列表")
    void handle_listBlocked_returnsBlockedStocks() {
        stubBoundUser();
        when(stocksDao.list()).thenReturn(List.of(hrg, mun));
        when(stockBlockDao.selectBlockedStocksIds(USER_ID)).thenReturn(Set.of(22));

        List<String> replies = texts(strategy.handle(GROUP_ID, sender, "屏蔽"));

        assertThat(replies).singleElement().asString()
                .contains("当前屏蔽: HRG(Home Retail Group)")
                .contains("取消屏蔽");
    }

    @Test
    @DisplayName("取消屏蔽：逻辑删除该股票")
    void handle_unblock_removesBlockedStock() {
        stubBoundUser();
        when(stocksDao.list()).thenReturn(List.of(hrg, mun));
        when(stockBlockDao.selectBlockedStocksIds(USER_ID)).thenReturn(Set.of(22));

        List<String> replies = texts(strategy.handle(GROUP_ID, sender, "取消屏蔽#HRG"));

        assertThat(replies).anyMatch(reply -> reply.contains("已取消屏蔽: HRG(Home Retail Group)"));
        verify(stockBlockDao).removeBlocks(USER_ID, List.of(22));
    }

    @Test
    @DisplayName("取消未屏蔽的股票：提示本来就没有屏蔽，不写库")
    void handle_unblockNotBlocked_reportsNotBlocked() {
        stubBoundUser();
        when(stocksDao.list()).thenReturn(List.of(hrg));
        when(stockBlockDao.selectBlockedStocksIds(USER_ID)).thenReturn(Set.of());

        List<String> replies = texts(strategy.handle(GROUP_ID, sender, "取消屏蔽#HRG"));

        assertThat(replies).anyMatch(reply -> reply.contains("这些股票本来就没有屏蔽: HRG(Home Retail Group)"));
        verify(stockBlockDao, never()).removeBlocks(any(), any());
    }

    @Test
    @DisplayName("分红计算：屏蔽股票ID传递给计算层，并在回复中提示已屏蔽")
    void handle_calculate_passesBlockedStocks() {
        stubBoundUser();
        TornApiKeyDO key = new TornApiKeyDO(1L, "api-key");
        when(apiKeyConfig.getKeyByUserId(USER_ID)).thenReturn(key);
        TornUserStocksVO userStocks = new TornUserStocksVO();
        userStocks.setStocks(new ArrayList<>());
        when(tornApi.sendRequest(any(TornReqParamV2.class), any(TornApiKeyDO.class), any())).thenReturn(userStocks);
        when(stocksDao.selectDividendStocks()).thenReturn(List.of(hrg, mun));
        when(stockBlockDao.selectBlockedStocksIds(USER_ID)).thenReturn(Set.of(22));
        StocksBonusAnalyzeManager.OptimalAction action = new StocksBonusAnalyzeManager.OptimalAction(
                StocksBonusAnalyzeManager.OptimalAction.ActionType.BUY, "MUN", 1, 2_000_000L, 100_000L, 0.05D);
        when(stocksBonusAnalyzeManager.calculate(2_000_000_000L, List.of(hrg, mun), userStocks, false, Set.of(22)))
                .thenReturn(List.of(action));

        List<String> replies = texts(strategy.handle(GROUP_ID, sender, "2b"));

        assertThat(replies).anyMatch(reply -> reply.contains("推荐以下操作配置分红股"))
                .anyMatch(reply -> reply.contains("已屏蔽: HRG(Home Retail Group)"));
        verify(stocksBonusAnalyzeManager).calculate(2_000_000_000L, List.of(hrg, mun), userStocks, false, Set.of(22));
    }

    /**
     * 打桩当前发送人为已绑定Torn用户
     */
    private void stubBoundUser() {
        when(userManager.getUserByQq(QQ_ID)).thenReturn(user);
    }

    /**
     * 提取文本消息内容
     *
     * @param messages 回复消息
     * @return 文本内容列表
     */
    private List<String> texts(List<? extends QqMsgParam<?>> messages) {
        return messages.stream()
                .filter(TextQqMsg.class::isInstance)
                .map(TextQqMsg.class::cast)
                .map(message -> message.getData().text())
                .toList();
    }

    /**
     * 构造股票
     *
     * @param id        股票ID
     * @param shortName 股票简称
     * @param name      股票名称
     * @return 股票
     */
    private TornStocksDO createStock(int id, String shortName, String name) {
        TornStocksDO stock = new TornStocksDO();
        stock.setId(id);
        stock.setStocksShortname(shortName);
        stock.setStocksName(name);
        return stock;
    }
}
