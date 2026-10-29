package com.swingpicker.domain;

/**
 * How a setup has historically played out (from the backtest, Step 5).
 * All outcomes are in R, gross of costs.
 *
 * @param winRate     fraction of trades that ended as winners (0..1)
 * @param avgWinR     average R of the winners (positive)
 * @param avgLossR    average R of the losers, as a positive number (usually close to 1, more on gaps)
 * @param sampleSize  number of historical trades behind these numbers
 */
public record Edge(double winRate, double avgWinR, double avgLossR, int sampleSize) {
}
