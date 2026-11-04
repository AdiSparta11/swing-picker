package com.swingpicker.core.momentum;

import com.swingpicker.core.momentum.MomentumBacktest.Performance;

import java.time.LocalDate;
import java.util.List;

/**
 * The momentum answer for "I want to invest X today".
 *
 * @param recommended  true only if the Nifty is in an uptrend today AND the filtered strategy beat buying all
 *                     stocks equally, both over the whole test and in the last 12 months
 * @param picks        what to buy now (sized), best first; listed even when not recommended, for information
 * @param ranking      the top of the current ranking, used to decide what to keep at the next re-check
 * @param backtest     strategy variants and benchmarks, overall / earlier / last 12 months
 */
public record MomentumResult(
        boolean recommended,
        String reason,
        Boolean niftyUptrend,
        LocalDate asOf,
        int positions,
        double perPosition,
        List<MomentumStock> picks,
        List<MomentumStock> ranking,
        List<StrategyRow> backtest,
        LocalDate recentFrom,
        String howToHold) {

    /** One ranked stock. quantity and amount are 0 outside the picks. */
    public record MomentumStock(int rank, String symbol, String sector, double score, double return6m,
                                double return12m, double lastClose, int quantity, double amount, String note) {
    }

    /** One line of the backtest table. */
    public record StrategyRow(String name, Performance all, Performance earlier, Performance recent) {
    }
}
