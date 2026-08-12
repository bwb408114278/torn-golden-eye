package pn.torn.goldeneye.torn.service.stocks.alert;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 股票通知载荷SHA-256哈希工具 - 为通知审计payloadHash提供确定性摘要计算
 * <p>
 * 对稳定的UTF-8中文载荷计算SHA-256,禁止使用{@link String#hashCode()}。
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.26
 */
@NoArgsConstructor(access = AccessLevel.NONE)
public final class StockHashUtils {

    /**
     * 计算输入字符串的SHA-256摘要(64位十六进制小写)
     *
     * @param input 待哈希的字符串(按UTF-8编码)
     * @return 64位十六进制SHA-256摘要
     * @throws IllegalStateException SHA-256算法不可用时抛出(理论上不会发生)
     */
    public static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256不可用", e);
        }
    }
}
