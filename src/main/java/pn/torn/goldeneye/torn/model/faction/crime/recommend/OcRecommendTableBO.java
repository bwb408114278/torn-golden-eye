package pn.torn.goldeneye.torn.model.faction.crime.recommend;

import pn.torn.goldeneye.repository.model.user.TornUserDO;

/**
 * OC推荐表格逻辑对象
 *
 * @param user      用户
 * @param recommend 推荐信息
 */
public record OcRecommendTableBO(
        TornUserDO user,
        OcRecommendationVO recommend) {
    public String buildReasonText() {
        StringBuilder sb = new StringBuilder();

        if (user != null) {
            sb.append(user.getNickname())
                    .append(" [")
                    .append(user.getId())
                    .append("]   ");
        }

        sb.append(recommend.getRank()).append("级")
                .append("   ").append(recommend.getOcName())
                .append("   岗位: ").append(recommend.getRecommendedPosition())
                .append("   评分: ").append(recommend.getRecommendScore())
                .append("   推荐理由: ").append(recommend.getReason());

        return sb.toString();
    }
}