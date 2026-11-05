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
 * @param livePrices   true while the market is open: today's ranking and prices use the live price
 * @param backtest     strategy variants and benchmarks, overall / earlier / last 12 months
 */
public record MomentumResult(
        boolean recommended,
        String reason,
        Boolean niftyUptrend,
        LocalDate asOf,
        boolean livePrices,
        int positions,
        double perPosition,
        List<MomentumStock> picks,
        List<MomentumStock> ranking,
        List<StrategyRow> backtest,
        LocalDate recentFrom,
        String howToHold,
        Outlook outlook) {

    /**
     * One ranked stock. quantity and amount are 0 outside the picks.
     *
     * @param typicalTarget price if this stock does what a typical momentum pick did in the month after being picked
     * @param goodMonth     this stock's typical 1-month move up: +1 standard deviation of its own volatility (fraction)
     * @param badMonth      the same move down (negative fraction); about 2 months in 3 land between the two
     */
    public record MomentumStock(int rank, String symbol, String sector, double score, double return6m,
                                double return12m, double lastClose, int quantity, double amount, String note,
                                double typicalTarget, double goodMonth, double badMonth) {
    }

    /**
     * What stocks the strategy held went on to do over one holding period, from the backtest (before charges).
     *
     * @param reviewDate   when to re-run the app (about one holding period from today)
     * @param median       middle outcome of all held stock-months
     * @param p25          a quarter of stock-months did worse than this
     * @param p75          a quarter did better than this
     * @param positiveShare share of stock-months that ended up
     * @param recentMedian median over the last 12 months only
     * @param samples      stock-months behind these numbers
     */
    public record Outlook(int holdDays, LocalDate reviewDate, double median, double p25, double p75,
                          double positiveShare, double recentMedian, int samples, int recentSamples) {
    }

    /** One line of the backtest table. */
    public record StrategyRow(String name, Performance all, Performance earlier, Performance recent) {
    }
}
