package pn.torn.goldeneye.torn.model.faction.crime.planning;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * OC岗位候选成员。能力Key格式为 rank:ocName:position。
 */
public record OcMemberCandidate(long userId, String nickname, LocalDateTime availableAt,
                                boolean fixed, Map<String, Integer> passRates,
                                Map<String, BigDecimal> coefficients) {

    public OcMemberCandidate {
        passRates = passRates == null ? Map.of() : Map.copyOf(passRates);
        coefficients = coefficients == null ? Map.of() : Map.copyOf(coefficients);
    }

    public int getPassRate(int rank, String ocName, String position) {
        return passRates.getOrDefault(capabilityKey(rank, ocName, position), -1);
    }

    public BigDecimal getCoefficient(int rank, String ocName, String slotCode) {
        return coefficients.getOrDefault(capabilityKey(rank, ocName, slotCode), BigDecimal.ZERO);
    }

    public int getCapabilityCount() {
        return passRates.size();
    }

    public OcMemberCandidate asAvailableAt(LocalDateTime time) {
        return new OcMemberCandidate(userId, nickname, time, false, passRates, coefficients);
    }

    public OcMemberCandidate asFixed() {
        return new OcMemberCandidate(userId, nickname, availableAt, true, passRates, coefficients);
    }

    public OcMemberCandidate withAvailability(LocalDateTime time, boolean fixedState) {
        return new OcMemberCandidate(userId, nickname, time, fixedState, passRates, coefficients);
    }

    public static String capabilityKey(int rank, String ocName, String position) {
        return rank + ":" + ocName + ":" + position;
    }
}
