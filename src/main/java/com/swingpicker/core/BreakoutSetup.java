package com.swingpicker.core;

import com.swingpicker.domain.Bar;
import com.swingpicker.domain.TradePlan;

import java.util.List;
import java.util.Optional;

import static com.swingpicker.core.Indicators.atr;
import static com.swingpicker.core.Indicators.roundDownToTick;
import static com.swingpicker.core.Indicators.roundToTick;
import static com.swingpicker.core.Indicators.sma;

/**
 * A stock in a long-term uptrend closes at a new 55-day high on heavy volume: buyers just took out
 * every seller of the last ~3 months. Buy the follow-through, stop under the breakout day.
 *
 * Fires on the last bar when ALL hold:
 *   Trend:     close > SMA200.
 *   Breakout:  close above the highest high of the previous 55 sessions.
 *   Volume:    today's volume at least 1.5x the 20-session average.
 *   Strength:  close in the top 25% of today's range (it held the move into the close).
 *   Not stretched: close no more than 3 ATR above the SMA20 (late, exhausted breakouts skipped).
 *
 * Plan: stop = higher of (today's low - 0.2 ATR) and (close - 2.5 ATR), but at least 1 ATR away;
 *       target 2R; don't buy above entry + 0.25R; exit after 15 sessions; skip if risk > 8% of price.
 */
public class BreakoutSetup implements Setup {

    public static final String NAME = "55-day-breakout";
    static final int MIN_BARS = 201;

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

        if (close <= sma(bars, i, 200)) {
            return Optional.empty();
        }

        double priorHigh = 0;
        for (int k = i - 55; k <= i - 1; k++) {
            priorHigh = Math.max(priorHigh, bars.get(k).high());
        }
        if (close <= priorHigh) {
            return Optional.empty();
        }

        double avgVolume = 0;
        for (int k = i - 20; k <= i - 1; k++) {
            avgVolume += bars.get(k).volume();
        }
        avgVolume /= 20;
        if (today.volume() < 1.5 * avgVolume) {
            return Optional.empty();
        }

        double range = today.high() - today.low();
        if (range <= 0 || (close - today.low()) / range < 0.75) {
            return Optional.empty();
        }

        double atr = atr(bars, i, 14);
        if (close > sma(bars, i, 20) + 3 * atr) {
            return Optional.empty();
        }

        double stop = Math.max(today.low() - 0.2 * atr, close - 2.5 * atr);
        stop = roundDownToTick(Math.min(stop, close - atr));
        double risk = close - stop;
        if (risk > 0.08 * close) {
            return Optional.empty();
        }

        double target = roundToTick(close + TrendPullbackSetup.TARGET_R * risk);
        double maxEntry = roundDownToTick(close + 0.25 * risk);
        return Optional.of(new TradePlan(symbol, NAME, today.date(), close, stop, target, maxEntry,
                TrendPullbackSetup.MAX_HOLD));
    }
}
