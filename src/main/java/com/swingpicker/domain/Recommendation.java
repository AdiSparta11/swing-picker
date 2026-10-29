package com.swingpicker.domain;

import java.util.List;

/**
 * The answer to "I want to invest X today": sized picks (or an explicit no), plus everything that was
 * considered and each setup's track record, so the decision is yours with the numbers in front of you.
 */
public record Recommendation(
        double requestedAmount,
        boolean trade,
        List<Pick> picks,
        double investedAmount,
        double unusedAmount,
        String reason,
        int stocksScanned,
        List<Considered> considered,
        List<SetupRecord> setups) {

    public static Recommendation noTrade(double requestedAmount, String reason, int scanned,
                                         List<Considered> considered, List<SetupRecord> setups) {
        return new Recommendation(requestedAmount, false, List.of(), 0, requestedAmount, reason, scanned,
                List.copyOf(considered), List.copyOf(setups));
    }

    public static Recommendation of(double requestedAmount, List<Pick> picks, int scanned,
                                    List<Considered> considered, List<SetupRecord> setups) {
        double invested = picks.stream().mapToDouble(Pick::amount).sum();
        return new Recommendation(requestedAmount, true, List.copyOf(picks), invested, requestedAmount - invested,
                picks.size() + " pick(s) passed every filter", scanned, List.copyOf(considered), List.copyOf(setups));
    }
}
