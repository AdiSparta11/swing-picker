package com.swingpicker.domain;

import java.util.List;

/** The answer to "I want to invest X today". Either sized picks, or an explicit no with the reason. */
public record Recommendation(
        double requestedAmount,
        boolean trade,
        List<Pick> picks,
        double investedAmount,
        double unusedAmount,
        String reason) {

    public static Recommendation noTrade(double requestedAmount, String reason) {
        return new Recommendation(requestedAmount, false, List.of(), 0, requestedAmount, reason);
    }

    public static Recommendation of(double requestedAmount, List<Pick> picks) {
        double invested = picks.stream().mapToDouble(Pick::amount).sum();
        return new Recommendation(requestedAmount, true, List.copyOf(picks), invested,
                requestedAmount - invested, picks.size() + " pick(s) passed every filter");
    }
}
