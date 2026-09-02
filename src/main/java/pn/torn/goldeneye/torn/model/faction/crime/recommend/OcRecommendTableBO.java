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

    /**
     * 构建推荐表格副标题的主体文本，评分与推荐理由由徽章单独展示，不混入正文。
     *
     * @return 副标题主体文本
     */
    public String buildSummaryText() {
        StringBuilder sb = new StringBuilder();

        if (user != null) {
            sb.append(user.getNickname())
                    .append(" [")
                    .append(user.getId())
                    .append("]   ");
        }

        sb.append(recommend.getRank()).append("级")
                .append("   ").append(recommend.getOcName())
                .append("   岗位: ").append(recommend.getRecommendedPosition());

        return sb.toString();
    }
}