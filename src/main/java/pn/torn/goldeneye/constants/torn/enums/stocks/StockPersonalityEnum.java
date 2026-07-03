package pn.torn.goldeneye.constants.torn.enums.stocks;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 股票个性分类 — 基于历史数据的波动率和趋势特征
 * <p>
 * 每月初根据数据库分析结果更新 sys_setting 表中的 STOCK_PERSONALITY 配置
 *
 * @author Bai
 * @version 1.2.8
 * @since 2026.07.01
 */
@Getter
@RequiredArgsConstructor
public enum StockPersonalityEnum {
    /**
     * 阴跌型 — 月均价持续下降，Z30 长时间为负，不宜低点买入
     */
    DECLINER(65, -1.5, -30, "阴跌型：禁止裸低点买入，仅允许强反弹确认"),
    /**
     * 弱势下跌 — 近期走弱但非长期趋势，提高买入门槛
     */
    WEAK(60, -2.0, -20, "弱势下跌：提高买入阈值，需要反弹确认"),
    /**
     * 窄幅震荡 — 价格带极窄（<4%），Z-Score 虚高，需要打折
     */
    NARROW(55, -2.5, 0, "窄幅震荡：Z-Score 打折计算，降低信号灵敏度"),
    /**
     * 横盘无趋势 — 标准差正常但无明显方向
     */
    RANGING(55, -2.5, 0, "横盘：标准策略但提高阈值"),
    /**
     * 稳步上行 — 月均价温和上涨
     */
    STEADY(50, -2.5, 0, "稳步上行：标准策略"),
    /**
     * 强势上涨 — 月均价显著上涨，专注止盈卖出信号
     */
    STRONG(50, -2.5, 0, "强势上涨：标准买入阈值，但卖出更积极");

    /**
     * 低点买入阈值
     */
    private final int buyThreshold;
    /**
     * 飞刀检测 Z-Score 阈值（<=此值视为飞刀风险）
     */
    private final double fallingKnifeZThreshold;
    /**
     * 飞刀/阴跌惩罚分
     */
    private final int declinePenalty;
    /**
     * 描述
     */
    private final String description;
}
