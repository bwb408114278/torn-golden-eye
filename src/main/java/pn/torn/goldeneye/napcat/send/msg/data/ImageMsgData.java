package pn.torn.goldeneye.napcat.send.msg.data;

/**
 * 图片群聊数据
 *
 * @param file 图片路径，或base64
 * @param url  图片网络地址
 * @author Bai
 * @version 0.5.0
 * @since 2025.08.05
 */
public record ImageMsgData(String file, String url) {
}