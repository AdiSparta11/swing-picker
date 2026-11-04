package com.swingpicker.core.momentum;

import com.swingpicker.domain.Bar;

import java.util.List;
import java.util.Optional;

/**
 * Risk-adjusted momentum, in the spirit of NSE's Nifty 200 Momentum 30 methodology:
 * the average of the 6-month and 12-month price returns, divided by the stock's annualised volatility.
 * A stock that rose 40% calmly ranks above one that rose 40% in violent swings.
 *
 * @param score      (r6 + r12) / 2 / volatility
 * @param return6m   price return over the last 126 sessions
 * @param return12m  price return over the last 252 sessions
 * @param volatility annualised standard deviation of daily log returns over the last 252 sessions
 */
public record MomentumScore(double score, double return6m, double return12m, double volatility) {

    public static final int LOOKBACK = 252;

    /** Score at bar index {@code end}, using only bars up to it. Empty without 252 sessions of history. */
    public static Optional<MomentumScore> at(List<Bar> bars, int end) {
        if (end < LOOKBACK || end >= bars.size()) {
            return Optional.empty();
        }
        double close = bars.get(end).close();
        double r6 = close / bars.get(end - 126).close() - 1;
        double r12 = close / bars.get(end - LOOKBACK).close() - 1;

        double sum = 0;
        double sumSq = 0;
        for (int k = end - LOOKBACK + 1; k <= end; k++) {
            double lr = Math.log(bars.get(k).close() / bars.get(k - 1).close());
            sum += lr;
            sumSq += lr * lr;
        }
        int n = LOOKBACK;
        double variance = (sumSq - sum * sum / n) / (n - 1);
        if (!(variance > 0)) {
            return Optional.empty();
        }
        double vol = Math.sqrt(variance * 252);
        return Optional.of(new MomentumScore((r6 + r12) / 2 / vol, r6, r12, vol));
    }
}
