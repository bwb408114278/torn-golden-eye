package pn.torn.goldeneye.torn.service.stocks.alert.alpha.track;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import pn.torn.goldeneye.constants.torn.SettingConstants;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockLedgerTypeEnum;
import pn.torn.goldeneye.torn.manager.setting.SysSettingManager;
import pn.torn.goldeneye.torn.service.stocks.alert.portfolio.StockPortfolioService;

import java.util.List;

/**
 * α相位轨道注册表 - 全部轨道定义与启用判定的唯一宿主。
 * <p>
 * 正式轨道 {@code VIP_ALPHA#1} 恒启用;影子轨道由影子开关统一控制,开关关闭时完全不参与编排,
 * 正式仓因此与多槽相位分散彻底解耦。
 *
 * @author Bai
 * @version 1.6.5
 * @since 2026.09.18
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockAlphaTrackRegistry {

    /**
     * 正式α轨道:单槽10B,恒启用。
     */
    public static final StockAlphaPhaseTrack VIP_ALPHA =
            new StockAlphaPhaseTrack("VIP_ALPHA#1", StockPortfolioService.VIP_ALPHA_PORTFOLIO_CODE, 1, 0);
    /**
     * 影子α1号轨道:相位偏移0。
     */
    public static final StockAlphaPhaseTrack VIP_ALPHA_SHADOW_FIRST =
            new StockAlphaPhaseTrack("VIP_ALPHA_SHADOW#1",
                    StockPortfolioService.VIP_ALPHA_SHADOW_PORTFOLIO_CODE, 1, 0);
    /**
     * 影子α2号轨道:相位偏移2,与1号轨道错开决策日。
     */
    public static final StockAlphaPhaseTrack VIP_ALPHA_SHADOW_SECOND =
            new StockAlphaPhaseTrack("VIP_ALPHA_SHADOW#2",
                    StockPortfolioService.VIP_ALPHA_SHADOW_PORTFOLIO_CODE, 2, 2);

    /**
     * 全部已注册轨道,顺序固定为正式轨道在前。
     */
    private static final List<StockAlphaPhaseTrack> ALL =
            List.of(VIP_ALPHA, VIP_ALPHA_SHADOW_FIRST, VIP_ALPHA_SHADOW_SECOND);
    /**
     * 开关启用标识。
     */
    private static final String SETTING_ENABLED_VALUE = "true";

    private final SysSettingManager sysSettingManager;

    /**
     * 返回当前启用的轨道列表。
     * <p>
     * 影子开关关闭时只返回正式轨道,编排层因此不会为影子组合读取槽位、创建批次或写决策。
     *
     * @return 启用的轨道列表,正式轨道恒在首位
     */
    public List<StockAlphaPhaseTrack> enabledTracks() {
        return isShadowEnabled() ? ALL : List.of(VIP_ALPHA);
    }

    /**
     * 返回正式α轨道。
     *
     * @return 正式轨道
     */
    public static StockAlphaPhaseTrack productionTrack() {
        return VIP_ALPHA;
    }

    /**
     * 判断影子α轨道是否启用。
     *
     * @return 影子开关为true时返回true
     */
    public boolean isShadowEnabled() {
        String value = sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_ALPHA_SHADOW_ENABLED);
        return SETTING_ENABLED_VALUE.equalsIgnoreCase(value);
    }

    /**
     * 返回轨道对应的批次账本类型。
     * <p>
     * 正式轨道复用{@link StockLedgerTypeEnum#FORMAL}(正式账本语义不变),α影子轨道使用
     * {@link StockLedgerTypeEnum#ALPHA_SHADOW}:两者账本隔离,影子批次不会进入正式通知与正式账本口径。
     *
     * @param track 目标轨道
     * @return 该轨道的账本类型编码
     */
    public static String ledgerTypeOf(StockAlphaPhaseTrack track) {
        return StockPortfolioService.VIP_ALPHA_PORTFOLIO_CODE.equals(track.portfolioCode())
                ? StockLedgerTypeEnum.FORMAL.getCode()
                : StockLedgerTypeEnum.ALPHA_SHADOW.getCode();
    }

    /**
     * 按轨道编码查找轨道。
     *
     * @param trackCode 轨道编码
     * @return 对应轨道
     * @throws IllegalArgumentException 编码为空或未注册时抛出
     */
    public static StockAlphaPhaseTrack of(String trackCode) {
        return ALL.stream().filter(track -> track.trackCode().equals(trackCode)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知α相位轨道编码: " + trackCode));
    }
}
