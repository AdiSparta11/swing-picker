package com.swingpicker.core.analysis;

import com.swingpicker.domain.SetupRecord;
import com.swingpicker.domain.TradePlan;

import java.time.LocalDate;
import java.util.List;

/**
 * Everything the app can say about one stock, for "should I hold it?" or "should I buy it?".
 * Each number is either a fact about the stock today or a measured historical result. None is a forecast.
 */
public record StockAnalysis(
        String symbol,
        String name,
        String sector,
        boolean inUniverse,
        LocalDate asOf,
        boolean livePrice,
        double price,
        Trend trend,
        Momentum momentum,
        Boolean niftyUptrend,
        List<SetupSignal> setups,
        List<HorizonOutlook> outlook,
        LocalDate historyFrom,
        LocalDate historyTo,
        Verdict verdict,
        Holding holding,
        Consensus consensus,
        Call call) {

    /**
     * The one-word answer, shown in bold, with the one-line reason and the rule that produced it.
     *
     * @param call BUY / WAIT / AVOID for a stock you don't own; HOLD / SELL for one you do
     */
    public record Call(String call, String headline, String rule) {
    }

    /** Where the price sits versus its own history. Returns and distances are fractions. */
    public record Trend(double sma50, double sma200, double vsSma200, boolean aboveSma200, boolean sma50AboveSma200,
                        double high52w, double low52w, double fromHigh, double return1m, double return3m,
                        double return6m, double return1y, double volatility) {
    }

    /**
     * @param shareBeaten fraction of Nifty 200 stocks with a lower momentum score today (0.9 = beats 90%)
     * @param quintile    1 = top 20% ... 5 = bottom 20%
     */
    public record Momentum(double score, double shareBeaten, int quintile, int ranked) {
    }

    /** A swing setup that fired on the latest session, with that setup's own track record. */
    public record SetupSignal(TradePlan plan, SetupRecord record, boolean hasEdge) {
    }

    /**
     * What stocks in the same momentum quintile (and, when there's enough data, the same Nifty state) did next.
     *
     * @param typicalPrice    today's price x (1 + median)
     * @param lowPrice        today's price x (1 + p25): a quarter of cases did worse
     * @param highPrice       today's price x (1 + p75): a quarter did better
     * @param baselineMedian  median for ALL stocks over the same horizon and market state, for comparison
     * @param sameMarketState true if the numbers are from the same Nifty state as today, false if pooled
     * @param chanceAboveCost for a holding: share of similar cases that ended this horizon at or above your
     *                        average price (null when you didn't enter a position)
     */
    public record HorizonOutlook(String label, int sessions, LocalDate around, double typicalPrice, double lowPrice,
                                 double highPrice, double median, double p25, double p75, double positiveShare,
                                 int samples, double baselineMedian, boolean sameMarketState, Double chanceAboveCost) {
    }

    /** points: each reason's contribution; label: plain verdict; action: what the signals suggest doing. */
    public record Verdict(int points, String label, String action, List<Reason> reasons) {
    }

    public record Reason(int points, String text) {
    }

    /**
     * Your position. P&L is before charges and tax.
     *
     * @param breakEvenGain rise needed from today's price to get back to your average price (0 if in profit)
     * @param longTermFrom  first date a sale counts as long-term (held more than 12 months); null without a buy date
     */
    public record Holding(int quantity, double averagePrice, double invested, double value, double pnl, double pnlPct,
                          LocalDate buyDate, Long daysHeld, LocalDate longTermFrom, Boolean longTerm,
                          double breakEvenGain, List<String> notes) {
    }
}
