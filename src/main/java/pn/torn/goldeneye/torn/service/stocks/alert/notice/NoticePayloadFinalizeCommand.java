package pn.torn.goldeneye.torn.service.stocks.alert.notice;

/**
 * 通知最终payload冻结命令 - 逐条携带通知的最终payload与哈希
 * <p>
 * 同一合并消息可能对应多条通知,每条通知业务payload不同,因此必须逐条冻结,
 * 禁止用一份JSON覆盖整个noticeIds集合。命令携带领取标识,只有本领取者持有的SENDING通知可被冻结。
 *
 * @param noticeId        通知ID
 * @param payloadSnapshot 最终完整payload(业务字段+messageText+frozenAt)规范化JSON
 * @param payloadHash     最终完整payload的SHA-256摘要
 * @param claimToken      本次发送领取标识
 * @author Bai
 * @version 1.6.1
 * @since 2026.09.12
 */
public record NoticePayloadFinalizeCommand(
        Long noticeId,
        String payloadSnapshot,
        String payloadHash,
        String claimToken) {
}
