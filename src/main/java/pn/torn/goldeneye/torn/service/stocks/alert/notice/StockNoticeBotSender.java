package pn.torn.goldeneye.torn.service.stocks.alert.notice;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.base.bot.Bot;
import pn.torn.goldeneye.base.bot.BotHttpReqParam;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.napcat.send.msg.GroupMsgHttpBuilder;
import pn.torn.goldeneye.napcat.send.msg.param.TextQqMsg;
import pn.torn.goldeneye.utils.JsonUtils;

/**
 * 股票通知Bot发送器 - 构建群消息请求并判定NapCat业务结果。
 * <p>
 * 普通通知与α换仓关联组共用本发送器,保证成功判定口径只有一套:HTTP 2xx、响应body非null
 * 且NapCat业务结果retcode=0、status=ok同时成立才算发送成功,其余情况一律返回可审计的失败原因。
 * 发送过程中抛出的异常被捕获并转为失败结果,不向上抛出,由调用方负责回写通知终态。
 *
 * @author Bai
 * @version 1.6.1
 * @since 2026.09.12
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockNoticeBotSender {
    /**
     * Bot返回null响应时的统一失败原因。
     */
    public static final String NULL_RESPONSE_FAILURE_MESSAGE = "Bot返回null响应";

    private final Bot bot;
    private final ProjectProperty projectProperty;

    /**
     * 发送单条消息并返回可审计的失败原因。
     * <p>
     * 成功判定须同时满足以下条件,任一不满足即视为失败并返回失败原因:
     * <ol>
     *   <li>{@link ResponseEntity} 非null</li>
     *   <li>HTTP状态码为2xx({@link org.springframework.http.HttpStatusCode#is2xxSuccessful()})</li>
     *   <li>响应body非null</li>
     *   <li>NapCat业务结果retcode == 0且status == "ok"</li>
     * </ol>
     *
     * @param text 待发送的中文消息文本
     * @return 发送结果;失败时携带实际失败原因
     */
    public SendResult send(String text) {
        try {
            BotHttpReqParam param = new GroupMsgHttpBuilder()
                    .setGroupId(projectProperty.getVipGroupId())
                    .addMsg(new TextQqMsg(text))
                    .build();
            ResponseEntity<String> response = bot.sendRequest(param, String.class);
            if (response == null) {
                log.warn("股票通知发送-Bot返回null响应, 发送失败");
                return SendResult.failure(NULL_RESPONSE_FAILURE_MESSAGE);
            }
            if (!response.getStatusCode().is2xxSuccessful()) {
                log.warn("股票通知发送-HTTP状态非2xx, 发送失败, statusCode={}", response.getStatusCode());
                return SendResult.failure("HTTP状态非2xx: " + response.getStatusCode());
            }
            String body = response.getBody();
            if (body == null) {
                log.warn("股票通知发送-响应body为空, 无法确认发送成功");
                return SendResult.failure("响应body为空");
            }
            if (!isNapCatSuccess(body)) {
                log.warn("股票通知发送-NapCat业务结果非成功, body={}", body);
                return SendResult.failure("NapCat业务结果非成功");
            }
            return SendResult.successful();
        } catch (Exception e) {
            log.error("股票通知发送-单条消息发送异常", e);
            return SendResult.failure("发送异常: " + e.getClass().getSimpleName());
        }
    }

    /**
     * 解析NapCat响应body判断业务是否成功。
     * <p>
     * NapCat返回JSON格式: {@code {"status":"ok","retcode":0,"data":...}},
     * 当retcode为0且status为"ok"时视为业务成功,其他情况视为失败。
     * 使用项目统一的 {@link JsonUtils#getNode} 解析JSON,避免暴露内部ObjectMapper。
     *
     * @param body NapCat响应body文本
     * @return true表示retcode=0且status=ok;false表示业务失败或解析异常
     */
    private boolean isNapCatSuccess(String body) {
        try {
            com.fasterxml.jackson.databind.JsonNode retcodeNode = JsonUtils.getNode(body, "retcode");
            int retcode = retcodeNode != null ? retcodeNode.asInt(-1) : -1;
            com.fasterxml.jackson.databind.JsonNode statusNode = JsonUtils.getNode(body, "status");
            String status = statusNode != null ? statusNode.asText() : null;
            return retcode == 0 && "ok".equals(status);
        } catch (Exception e) {
            log.warn("股票通知发送-NapCat响应解析异常,视为失败: body={}", body, e);
            return false;
        }
    }

    /**
     * 单条消息发送结果。
     *
     * @param success       是否发送成功
     * @param failureReason 实际失败原因;成功时为null
     */
    public record SendResult(boolean success, String failureReason) {
        /**
         * 构建成功结果。
         *
         * @return 成功结果
         */
        public static SendResult successful() {
            return new SendResult(true, null);
        }

        /**
         * 构建失败结果。
         *
         * @param reason 实际失败原因
         * @return 失败结果
         */
        public static SendResult failure(String reason) {
            return new SendResult(false, reason);
        }
    }
}
