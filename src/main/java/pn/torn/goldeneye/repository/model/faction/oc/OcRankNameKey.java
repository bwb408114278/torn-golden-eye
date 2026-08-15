package pn.torn.goldeneye.repository.model.faction.oc;

/**
 * OC规划键的等级与名称参数对。用于按(rank, name)成对条件批量查询历史收益记录，
 * 保证SQL可以使用普通(rank, name)索引而不是字符串拼接表达式。
 *
 * @param rank   OC等级
 * @param ocName OC名称
 * @author Bai
 * @version 1.3.0
 * @since 2026.08.15
 */
public record OcRankNameKey(
        int rank,
        String ocName) {
}
