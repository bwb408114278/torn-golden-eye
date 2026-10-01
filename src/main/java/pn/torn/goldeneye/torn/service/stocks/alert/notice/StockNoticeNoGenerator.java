package pn.torn.goldeneye.torn.service.stocks.alert.notice;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 股票通知编号生成器 - 通知编号时间戳的唯一实现。
 * <p>
 * 编号把各类型自己的前缀与后缀拼接在同一毫秒时间戳上:时间戳保证跨日跨类型行互不相同,
 * 前后缀由调用方按通知类型给出(批次N、日报D、年报A、继续持有H),本类不解释业务含义。
 *
 * @author Bai
 * @version 1.6.6
 * @since 2026.10.01
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class StockNoticeNoGenerator {
    /**
     * 通知编号时间戳格式(毫秒精度,同一毫秒内多行靠前后缀区分)。
     */
    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    /**
     * 生成通知编号。
     *
     * @param businessNow 本次发送编排的业务时间
     * @param prefix      通知类型前缀
     * @param suffix      类型专属后缀(无后缀传空串)
     * @return 通知编号
     */
    public static String generate(LocalDateTime businessNow, String prefix, String suffix) {
        return prefix + businessNow.format(TIMESTAMP_FORMATTER) + suffix;
    }
}
