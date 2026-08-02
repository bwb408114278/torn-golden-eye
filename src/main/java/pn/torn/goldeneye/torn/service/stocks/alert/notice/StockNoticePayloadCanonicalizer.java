package pn.torn.goldeneye.torn.service.stocks.alert.notice;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import pn.torn.goldeneye.torn.service.stocks.alert.StockHashUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;

/**
 * 股票通知载荷规范化工具 - 为通知审计payload提供确定性JSON规范化与SHA-256摘要
 * <p>
 * 规范化算法冻结如下,创建、发送前合并与数据库复核统一调用,禁止各处自行拼接JSON:
 * <ol>
 *   <li>将payload解析为JsonNode</li>
 *   <li>Object字段按UTF-8字典序递归排序</li>
 *   <li>Array保持业务顺序,不排序</li>
 *   <li>数值使用Jackson标准JSON数值,不转科学计数法字符串;时间字段预先使用ISO-8601字符串</li>
 *   <li>不输出空白和换行</li>
 *   <li>SHA-256(canonicalJson UTF-8 bytes),输出64位小写十六进制</li>
 * </ol>
 * 从数据库读取{@code payload_snapshot}后重新规范化,必须得到相同hash。
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.08.02
 */
@Slf4j
@NoArgsConstructor(access = AccessLevel.NONE)
public final class StockNoticePayloadCanonicalizer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 对payload JSON做确定性规范化并计算SHA-256。
     *
     * @param payloadJson 待规范化payload JSON文本
     * @return 64位小写十六进制SHA-256摘要
     * @throws IllegalStateException JSON无法解析时抛出
     */
    public static String sha256(String payloadJson) {
        return StockHashUtils.sha256(canonicalize(payloadJson));
    }

    /**
     * 将payload JSON规范化为确定性JSON文本(对象键字典序递归排序、无空白)。
     *
     * @param payloadJson 待规范化payload JSON文本
     * @return 规范化后的JSON文本
     * @throws IllegalStateException JSON无法解析时抛出
     */
    public static String canonicalize(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            throw new IllegalStateException("通知payload为空,无法规范化");
        }
        try {
            JsonNode root = MAPPER.readTree(payloadJson);
            Object normalized = normalize(root);
            return MAPPER.writeValueAsString(normalized);
        } catch (JsonProcessingException e) {
            log.error("通知payload规范化失败: {}", payloadJson, e);
            throw new IllegalStateException("通知payload无法解析为JSON", e);
        }
    }

    /**
     * 将创建时业务payload与最终消息文本、冻结时间合并,返回规范化JSON。
     * <p>
     * 复制原payload全部业务字段,再写入{@code messageText}与{@code frozenAt},
     * 禁止新建空对象后只写两个字段覆盖原业务快照。
     *
     * @param originalPayload 创建时业务payload JSON文本
     * @param messageText     最终发送的中文消息文本
     * @param frozenAt        发送前冻结时间
     * @return 合并后的规范化payload JSON文本
     * @throws IllegalStateException 原payload不是JSON对象或无法解析时抛出
     */
    public static String mergeAndCanonicalize(String originalPayload, String messageText, LocalDateTime frozenAt) {
        if (originalPayload == null || originalPayload.isBlank()) {
            throw new IllegalStateException("创建时业务payload为空,无法合并");
        }
        try {
            JsonNode root = MAPPER.readTree(originalPayload);
            if (!root.isObject()) {
                throw new IllegalStateException("创建时业务payload不是JSON对象,无法合并");
            }
            ObjectNode merged = root.deepCopy();
            merged.put("messageText", messageText);
            merged.put("frozenAt", frozenAt.toString());
            return canonicalize(merged.toString());
        } catch (JsonProcessingException e) {
            log.error("通知payload合并失败: originalPayload={}", originalPayload, e);
            throw new IllegalStateException("通知payload无法解析为JSON", e);
        }
    }

    /**
     * 递归规范化JsonNode: 对象键排序为字典序TreeMap,数组保持顺序,标量原样保留。
     *
     * @param node 待规范化节点
     * @return 规范化后的容器对象或标量
     */
    private static Object normalize(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            TreeMap<String, Object> sorted = new TreeMap<>(Comparator.naturalOrder());
            node.properties().forEach(entry -> sorted.put(entry.getKey(), normalize(entry.getValue())));
            return sorted;
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            node.forEach(child -> list.add(normalize(child)));
            return list;
        }
        if (node.isNumber()) {
            return node.numberValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        return node.asText();
    }
}
