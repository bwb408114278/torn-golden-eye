package pn.torn.goldeneye.torn.model.activity;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * Torn 帮派名人堂响应
 *
 * @author Bai
 * @version 1.2.9
 * @since 2026.07.08
 */
@Data
public class TornFactionHofVO {

    @JsonProperty("factionhof")
    private List<FactionHofEntry> factionHof;

    @JsonProperty("_metadata")
    private Metadata metadata;

    @Data
    public static class FactionHofEntry {
        private long id;
        private String name;
        private int members;
        private int position;
        private String rank;
        private Values values;
    }

    @Data
    public static class Values {
        private Long chain;

        @JsonProperty("chain_duration")
        private Long chainDuration;

        private Long respect;
    }

    @Data
    public static class Metadata {
        private Links links;
    }

    @Data
    public static class Links {
        private String prev;
        private String next;
    }
}
