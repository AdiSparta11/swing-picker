package com.swingpicker.domain;

/**
 * A setup's whole-universe track record from the backtest, before charges.
 * Charges typically cost another 0.03-0.10R per trade depending on stop width.
 */
public record SetupRecord(String setup, int trades, double winRate, double avgWinR, double avgLossR,
                          double expectancyR) {

    public static SetupRecord of(String setup, Edge pool) {
        return new SetupRecord(setup, pool.poolTrades(), pool.winRate(), pool.avgWinR(), pool.avgLossR(),
                pool.expectancyR());
    }
}
