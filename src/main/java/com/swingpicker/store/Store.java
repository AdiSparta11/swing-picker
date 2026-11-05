package com.swingpicker.store;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Your saved data: holdings, watchlist, and the log of every pick the app made. */
public interface Store {

    /** A stock you own. buyDate may be null. */
    record HoldingRow(long id, String symbol, int quantity, double averagePrice, LocalDate buyDate, Instant addedAt) {
    }

    record WatchRow(String symbol, Instant addedAt) {
    }

    /**
     * One logged pick and how it turned out.
     *
     * @param kind        "momentum" or "swing"
     * @param recommended true if the app recommended it at the time (not just "would buy")
     * @param status      open, target, stop, time (time exit), no-fill (opened above max entry), done (momentum period over)
     * @param returnPct   result as a fraction, from entry to exit, before charges
     * @param rMultiple   swing only: result in units of the planned risk
     * @param benchmark   Nifty 50 return over the same dates, for comparison
     */
    record CallRow(long id, Instant loggedAt, String kind, String symbol, String setup, LocalDate signalDate,
                   double refPrice, Double stop, Double target, Double maxEntry, int holdDays, boolean recommended,
                   String status, LocalDate entryDate, Double entryPrice, LocalDate exitDate, Double exitPrice,
                   Double returnPct, Double rMultiple, Double benchmark) {

        public CallRow graded(String status, LocalDate entryDate, Double entryPrice, LocalDate exitDate,
                              Double exitPrice, Double returnPct, Double rMultiple, Double benchmark) {
            return new CallRow(id, loggedAt, kind, symbol, setup, signalDate, refPrice, stop, target, maxEntry, holdDays,
                    recommended, status, entryDate, entryPrice, exitDate, exitPrice, returnPct, rMultiple, benchmark);
        }

        public boolean open() {
            return "open".equals(status);
        }
    }

    /** True when data survives a restart (a database is configured). */
    boolean persistent();

    List<HoldingRow> holdings();

    HoldingRow addHolding(String symbol, int quantity, double averagePrice, LocalDate buyDate);

    Optional<HoldingRow> updateHolding(long id, int quantity, double averagePrice, LocalDate buyDate);

    boolean deleteHolding(long id);

    List<WatchRow> watchlist();

    void watch(String symbol);

    boolean unwatch(String symbol);

    /** Logs a pick unless the same kind + symbol + signal date is already logged. Returns true if it was new. */
    boolean logCall(CallRow call);

    List<CallRow> calls();

    void saveGrade(CallRow call);
}
