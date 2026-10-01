package pn.torn.goldeneye.constants.torn.enums.stocks.portfolio;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

/**
 * 股票年度结算状态枚举 - 描述年度结算台账行的处理状态与终态语义
 *
 * <p>只有非终态状态允许在年度结算的可证窗口内重试;终态表示该年度已无可自动收敛的路径,
 * 需要人工核验或等待上游年度结算完成,自动重试不得覆盖已落库的金额事实。
 * 未知或空的编码一律按"不可重试"处理(fail-closed),避免脏状态被反复结算。
 *
 * @author Bai
 * @version 1.6.6
 * @since 2026.10.01
 */
@Getter
@RequiredArgsConstructor
public enum StockAnnualSettlementStatusEnum {
    /**
     * 待边界就绪 - 已进入结算尝试,但边界桶轮次未完成
     */
    PENDING_BOUNDARY("PENDING_BOUNDARY", "边界未就绪", false),
    /**
     * 已结算 - 全部门禁通过且年末边界权益与提取额已落库
     */
    SETTLED("SETTLED", "已结算", true),
    /**
     * 边界行情缺失 - 任一开放持仓缺少合法边界行情,金额列保持为空,绝不伪造结算
     */
    DEGRADED_PRICE_MISSING("DEGRADED_PRICE_MISSING", "边界行情缺失", false),
    /**
     * 边界状态不可证 - 边界之后已发生资金变动,读到的状态不再是边界状态,只能转人工
     */
    DEGRADED_NOT_PROVABLE("DEGRADED_NOT_PROVABLE", "边界状态不可证", true),
    /**
     * 上一年度未结算 - 上一年度没有相邻的已结算行,禁止跳年结算
     */
    BLOCKED_PRIOR_YEAR("BLOCKED_PRIOR_YEAR", "上一年度未结算", true),
    /**
     * 人工核验 - 槽位缺失、区间起点非法等不可自动收敛的情形
     */
    MANUAL_REVIEW("MANUAL_REVIEW", "人工核验", true),
    ;

    /**
     * 英文编码
     */
    private final String code;
    /**
     * 中文展示
     */
    private final String chineseDisplay;
    /**
     * 是否为终态(终态不允许在可证窗口内自动重试)
     */
    private final boolean terminal;

    /**
     * 判断状态编码是否允许在结算窗口内自动重试。
     *
     * @param code 状态编码,可为空
     * @return 已知且非终态返回true;未知、空或终态返回false
     */
    public static boolean isRetryableStatus(String code) {
        if (code == null) {
            return false;
        }
        return Arrays.stream(values()).anyMatch(e -> e.code.equals(code) && !e.terminal);
    }
}
