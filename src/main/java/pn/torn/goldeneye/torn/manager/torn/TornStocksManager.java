package pn.torn.goldeneye.torn.manager.torn;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.constants.torn.SettingConstants;
import pn.torn.goldeneye.repository.model.torn.TornItemsDO;
import pn.torn.goldeneye.repository.model.torn.TornStocksDO;
import pn.torn.goldeneye.torn.manager.setting.SysSettingManager;
import pn.torn.goldeneye.torn.model.torn.stocks.TornStocksBenefitVO;
import pn.torn.goldeneye.torn.model.torn.stocks.TornStocksDetailVO;

import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Torn股票公共逻辑层
 *
 * @author Bai
 * @version 0.2.0
 * @since 2025.09.26
 */
@Component
@RequiredArgsConstructor
public class TornStocksManager {
    private final SysSettingManager settingManager;
    private final TornItemsManager itemsManager;

    private static final Pattern CURRENCY_PATTERN = Pattern.compile("\\$(\\d{1,3}(?:,\\d{3})*)");
    private static final Pattern ITEM_PATTERN = Pattern.compile("1x (.+)");

    /**
     * 计算日利润
     */
    public TornStocksDO convert2DO(TornStocksDetailVO stock) {
        TornStocksBenefitVO benefit = stock.getBenefit();
        long profit = parseBenefitValue(benefit.getDescription(), stock.getAcronym());
        long yearProfit = profit / benefit.getFrequency() * 365;
        long baseCost = stock.getCurrentPrice()
                .multiply(BigDecimal.valueOf(stock.getBenefit().getRequirement()))
                .longValue();
        return stock.convert2DO(profit, yearProfit, baseCost);
    }

    /**
     * 转换分红价值
     */
    private long parseBenefitValue(String description, String acronym) {
        // 特殊股票处理
        if ("PTS".equals(acronym)) {
            long pointValue = Long.parseLong(settingManager.getSettingValue(SettingConstants.KEY_POINT_VALUE));
            return pointValue * 100;
        } else if ("HRG".equals(acronym)) {
            return 50000000;
        }

        // 货币类型处理
        Matcher currencyMatcher = CURRENCY_PATTERN.matcher(description);
        if (currencyMatcher.find()) {
            String amount = currencyMatcher.group(1).replace(",", "");
            return Long.parseLong(amount);
        }

        // 物品类型处理
        Matcher itemMatcher = ITEM_PATTERN.matcher(description);
        if (itemMatcher.find()) {
            TornItemsDO item = itemsManager.getList().stream()
                    .filter(i -> i.getItemName().equals(itemMatcher.group(1)))
                    .findAny().orElse(null);
            return item == null ? 0L : item.getMarketPrice();
        }

        // 特殊股票处理
        return 0L;
    }
}