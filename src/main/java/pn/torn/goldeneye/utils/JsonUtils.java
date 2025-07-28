package pn.torn.goldeneye.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import pn.torn.goldeneye.base.exception.BizException;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.TimeZone;

/**
 * Json工具类
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.07.24
 */
@Slf4j
@NoArgsConstructor(access = AccessLevel.NONE)
public class JsonUtils {
    private static final ObjectMapper MAPPER;
    private static final String DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";

    static {
        MAPPER = new ObjectMapper();
        // 忽略空Bean转json的错误
        MAPPER.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
        // 忽略 在json字符串中存在，但是在java对象中不存在对应属性的情况。防止错误
        MAPPER.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        // 取消默认转换timestamps形式
        MAPPER.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        // 指定时区
        MAPPER.setTimeZone(TimeZone.getTimeZone("GMT+8:00"));

        JavaTimeModule module = new JavaTimeModule();
        // yyyy-MM-dd HH:mm:ss
        DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern(DATE_TIME_PATTERN);
        module.addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(dateTimeFormatter));
        module.addDeserializer(LocalDateTime.class, new LocalDateTimeDeserializer(dateTimeFormatter));

        MAPPER.registerModule(module);
    }

    /**
     * Json字符串转对象
     *
     * @param str Json字符串
     */
    public static <T> T jsonToObj(String str, Class<T> clazz) {
        try {
            return MAPPER.readValue(str, clazz);
        } catch (JsonProcessingException e) {
            log.error("Json转对象异常，字符串为: " + str);
            throw new BizException("Json转对象异常", e);
        }
    }

    /**
     * 对象转Json字符串
     *
     * @return Json字符串
     */
    public static <T> String objToJson(T obj) {
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.error("对象转Json异常，对象为: " + obj.toString());
            throw new BizException("对象转Json异常", e);
        }
    }

    /**
     * 判断节点是否存在
     *
     * @param json      Json字符串
     * @param fieldName 字段名称
     * @return true为存在
     */
    public static boolean existsNode(String json, String fieldName) {
        try {
            JsonNode rootNode = MAPPER.readTree(json);
            return rootNode.has(fieldName);
        } catch (JsonProcessingException e) {
            log.error("Json节点是否存在异常，字段名为: " + fieldName + ", 字符串为: " + json);
            throw new BizException("Json节点是否存在异常", e);
        }
    }
}