package com.swingpicker.domain;

import java.time.LocalDate;

/**
 * A setup's whole-universe track record from the backtest, before charges, in R.
 * Split in two so a rule that only worked years ago can't hide behind its long average:
 * "earlier" is everything before {@code recentFrom}, "recent" is the last 12 months.
 * Charges typically cost another 0.03-0.10R per trade depending on stop width.
 */
public record SetupRecord(String setup, int trades, double winRate, double avgWinR, double avgLossR,
                          double expectancyR, int earlierTrades, double earlierExpectancyR,
                          int recentTrades, double recentExpectancyR, LocalDate recentFrom) {
}
