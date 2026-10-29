package com.swingpicker.core;

import com.swingpicker.domain.Bar;
import com.swingpicker.domain.TradePlan;

import java.util.List;
import java.util.Optional;

import static com.swingpicker.core.Indicators.atr;
import static com.swingpicker.core.Indicators.lowestLow;
import static com.swingpicker.core.Indicators.roundDownToTick;
import static com.swingpicker.core.Indicators.roundToTick;
import static com.swingpicker.core.Indicators.sma;

/**
 * A stock in a healthy uptrend dips to its 20-day average, then turns back up. Buy the turn, stop under the dip.
 *
 * Fires on the last bar when ALL hold:
 *   Trend:    close > SMA200, SMA50 > SMA200, SMA50 higher than 10 sessions ago.
 *   Dip:      in the previous 5 sessions some low came within 1% of (or below) that day's SMA20,
 *             without breaking the SMA50 (a pullback, not a breakdown).
 *   Turn:     today closes above SMA20 and above yesterday's high.
 *
 * Plan:
 *   stop      = lowest low of the last 6 sessions - 0.2 ATR (just under the dip), but at least 1 ATR away
 *   target    = entry + 2R
 *   maxEntry  = entry + 0.25R (skip if tomorrow opens above this)
 *   time exit = 15 sessions (about 3 weeks)
 *   skip if risk is over 3 ATR or over 8% of price (too loose to size sensibly).
 */
public class TrendPullbackSetup implements Setup {

    public static final String NAME = "trend-pullback";
    static final int MIN_BARS = 211;
    static final double TARGET_R = 2.0;
    static final int MAX_HOLD = 15;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Optional<TradePlan> detect(String symbol, List<Bar> bars) {
        int n = bars.size();
        if (n < MIN_BARS) {
            return Optional.empty();
        }
        int i = n - 1;
        Bar today = bars.get(i);
        double close = today.close();

        double sma200 = sma(bars, i, 200);
        double sma50 = sma(bars, i, 50);
        if (!(close > sma200 && sma50 > sma200 && sma50 > sma(bars, i - 10, 50))) {
            return Optional.empty();
        }

        boolean dipped = false;
        for (int j = i - 5; j <= i - 1; j++) {
            Bar b = bars.get(j);
            if (b.low() <= sma(bars, j, 20) * 1.01) {
                dipped = true;
            }
        }
        if (!dipped || lowestLow(bars, i - 5, i) < sma50) {
            return Optional.empty();
        }

        if (!(close > sma(bars, i, 20) && close > bars.get(i - 1).high())) {
            return Optional.empty();
        }

        double atr = atr(bars, i, 14);
        double stop = Math.min(lowestLow(bars, i - 5, i) - 0.2 * atr, close - atr);
        stop = roundDownToTick(stop);
        double risk = close - stop;
        if (risk > 3 * atr || risk > 0.08 * close) {
            return Optional.empty();
        }

        double target = roundToTick(close + TARGET_R * risk);
        double maxEntry = roundDownToTick(close + 0.25 * risk);
        return Optional.of(new TradePlan(symbol, NAME, today.date(), close, stop, target, maxEntry, MAX_HOLD));
    }
}
