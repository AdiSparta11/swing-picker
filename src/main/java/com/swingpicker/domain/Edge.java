package com.swingpicker.domain;

/**
 * How a setup has historically played out for one stock (from the backtest), gross of costs, in R.
 * Per-stock history is short, so these numbers are the stock's own results blended toward the
 * whole universe's results; the fewer trades the stock has, the more weight the universe gets.
 *
 * @param winRate     fraction of trades that ended positive (0..1)
 * @param avgWinR     average R of the winners (positive)
 * @param avgLossR    average R of the losers, as a positive number (about 1, more when a gap skips the stop)
 * @param stockTrades historical trades on this stock
 * @param poolTrades  historical trades across the whole universe
 */
public record Edge(double winRate, double avgWinR, double avgLossR, int stockTrades, int poolTrades) {

    public double expectancyR() {
        return winRate * avgWinR - (1 - winRate) * avgLossR;
    }
}
