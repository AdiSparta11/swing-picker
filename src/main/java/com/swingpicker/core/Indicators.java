package com.swingpicker.core;

import com.swingpicker.domain.Bar;

import java.util.List;

/** Plain indicator math on oldest-first daily bars. Each method looks only at bars up to {@code end}. */
public final class Indicators {

    private Indicators() {
    }

    /** Simple moving average of closes over the {@code period} bars ending at index {@code end}. */
    public static double sma(List<Bar> bars, int end, int period) {
        requireWindow(end, period);
        double sum = 0;
        for (int k = end - period + 1; k <= end; k++) {
            sum += bars.get(k).close();
        }
        return sum / period;
    }

    /** Average true range over {@code period} bars ending at {@code end} (simple average of true ranges). */
    public static double atr(List<Bar> bars, int end, int period) {
        requireWindow(end - 1, period); // each true range needs the previous close
        double sum = 0;
        for (int k = end - period + 1; k <= end; k++) {
            Bar b = bars.get(k);
            double prevClose = bars.get(k - 1).close();
            sum += Math.max(b.high() - b.low(), Math.max(Math.abs(b.high() - prevClose), Math.abs(b.low() - prevClose)));
        }
        return sum / period;
    }

    /** Lowest low over bars [from, to], inclusive. */
    public static double lowestLow(List<Bar> bars, int from, int to) {
        double low = Double.MAX_VALUE;
        for (int k = from; k <= to; k++) {
            low = Math.min(low, bars.get(k).low());
        }
        return low;
    }

    /** NSE equity tick size is ₹0.05. */
    public static double roundDownToTick(double price) {
        return Math.floor(price * 20 + 1e-9) / 20.0;
    }

    public static double roundToTick(double price) {
        return Math.round(price * 20) / 20.0;
    }

    private static void requireWindow(int end, int period) {
        if (end - period + 1 < 0) {
            throw new IllegalArgumentException("Not enough bars: need " + period + " ending at " + end);
        }
    }
}
