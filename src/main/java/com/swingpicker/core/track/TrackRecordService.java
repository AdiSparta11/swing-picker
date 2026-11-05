package com.swingpicker.core.track;

import com.swingpicker.core.BacktestEdgeEstimator;
import com.swingpicker.core.BacktestEdgeEstimator.Outcome;
import com.swingpicker.core.LiveSession;
import com.swingpicker.core.MarketDataProvider;
import com.swingpicker.core.MarketRegime;
import com.swingpicker.core.momentum.MomentumResult;
import com.swingpicker.core.momentum.MomentumResult.MomentumStock;
import com.swingpicker.domain.Bar;
import com.swingpicker.domain.Considered;
import com.swingpicker.domain.Recommendation;
import com.swingpicker.domain.TradePlan;
import com.swingpicker.store.Store;
import com.swingpicker.store.Store.CallRow;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Every pick the app makes gets written down and graded later against what the market actually did,
 * so you can see whether the app works, not just whether its backtest did.
 *
 *  - Swing: every signal is logged (picked ones as "recommended"), graded with exactly the backtest's rules
 *    ({@link BacktestEdgeEstimator#simulate}): next-open entry, stop first if both touched, time exit.
 *  - Momentum: the top 5 of the ranking, once per holding period (21 sessions), bought at the next open and
 *    valued at the open 21 sessions later, next to the Nifty 50 over the same dates.
 *  - Only completed sessions are logged: picks made from live, still-forming prices aren't final.
 */
public class TrackRecordService {

    public static final int MOMENTUM_TOP = 5;

    /** Results for one group of calls. Returns are fractions; avgR only for swing. */
    public record Summary(String kind, boolean recommendedOnly, int closed, int open, int noFill, double winRate,
                          double avgReturn, Double avgR, double avgBenchmark, double beatBenchmark) {
    }

    public record Report(boolean saved, List<Summary> summaries, List<CallRow> calls) {
    }

    private final Store store;
    private final MarketDataProvider data;
    private final LiveSession session;
    private final int historyYears;

    public TrackRecordService(Store store, MarketDataProvider data, LiveSession session, int historyYears) {
        this.store = store;
        this.data = data;
        this.session = session;
        this.historyYears = historyYears;
    }

    // ---------- logging ----------

    /** Logs swing signals from a completed session. Returns how many were new. */
    public int logSwing(Recommendation rec) {
        if (session.isOpen()) {
            return 0;
        }
        int added = 0;
        for (Considered c : rec.considered()) {   // picked ones come first, so they win the (symbol, date) slot
            TradePlan p = c.candidate().plan();
            if (store.logCall(new CallRow(0, null, "swing", p.symbol(), p.setupName(), p.signalDate(), p.entry(), p.stop(),
                    p.target(), p.maxEntry(), p.maxHoldDays(), c.picked(), "open", null, null, null, null, null, null, null))) {
                added++;
            }
        }
        return added;
    }

    /** Logs the momentum top 5 once per holding period. Returns how many were new. */
    public int logMomentum(MomentumResult r, int holdDays) {
        if (session.isOpen() || r.asOf() == null) {
            return 0;
        }
        LocalDate latest = store.calls().stream().filter(c -> c.kind().equals("momentum")).map(CallRow::signalDate)
                .max(Comparator.naturalOrder()).orElse(null);
        // ~21 sessions is ~29 calendar days; don't start a new batch before the last one has run its course
        if (latest != null && latest.plusDays(Math.round(holdDays * 7 / 5.0)).isAfter(r.asOf())) {
            return 0;
        }
        List<MomentumStock> top = r.ranking().stream().sorted(Comparator.comparingInt(MomentumStock::rank))
                .limit(MOMENTUM_TOP).toList();
        int added = 0;
        for (MomentumStock m : top) {
            if (store.logCall(new CallRow(0, null, "momentum", m.symbol(), "momentum top " + MOMENTUM_TOP, r.asOf(),
                    m.lastClose(), null, null, null, holdDays, r.recommended(), "open",
                    null, null, null, null, null, null, null))) {
                added++;
            }
        }
        return added;
    }

    // ---------- grading ----------

    /** Grades every open call that now has enough data. Returns how many were closed. */
    public int gradeOpen() {
        List<Bar> nifty = safeBars(MarketRegime.NIFTY);
        int closed = 0;
        for (CallRow c : store.calls()) {
            if (!c.open()) {
                continue;
            }
            Optional<CallRow> g = grade(c, safeBars(c.symbol()), nifty);
            if (g.isPresent()) {
                store.saveGrade(g.get());
                closed++;
            }
        }
        return closed;
    }

    /** Pure grading of one call against its bars; empty while it's still running. */
    public static Optional<CallRow> grade(CallRow c, List<Bar> bars, List<Bar> nifty) {
        int i = indexOnOrBefore(bars, c.signalDate());
        if (i < 0) {
            return Optional.empty();
        }
        if (c.kind().equals("swing")) {
            TradePlan plan = new TradePlan(c.symbol(), c.setup(), c.signalDate(), c.refPrice(), c.stop(), c.target(),
                    c.maxEntry(), c.holdDays());
            Optional<Outcome> o = BacktestEdgeEstimator.simulate(plan, bars, i);
            if (o.isEmpty()) {
                return Optional.empty();
            }
            Outcome x = o.get();
            if (x.status().equals("no-fill")) {
                return Optional.of(c.graded("no-fill", bars.get(x.entryIndex()).date(), x.entry(), null, null, null, null, null));
            }
            LocalDate in = bars.get(x.entryIndex()).date();
            LocalDate out = bars.get(x.exitIndex()).date();
            return Optional.of(c.graded(x.status(), in, x.entry(), out, x.exit(), x.exit() / x.entry() - 1, x.r(),
                    niftyReturn(nifty, in, out, false)));
        }
        int in = i + 1;
        int out = in + c.holdDays();
        if (out >= bars.size()) {
            return Optional.empty();
        }
        double entry = bars.get(in).open();
        double exit = bars.get(out).open();
        LocalDate inD = bars.get(in).date();
        LocalDate outD = bars.get(out).date();
        return Optional.of(c.graded("done", inD, entry, outD, exit, exit / entry - 1, null, niftyReturn(nifty, inD, outD, true)));
    }

    // ---------- reporting ----------

    public Report report() {
        gradeOpen();
        List<CallRow> calls = store.calls();
        List<Summary> s = new ArrayList<>();
        for (String kind : List.of("momentum", "swing")) {
            s.add(summarise(kind, true, calls));
            s.add(summarise(kind, false, calls));
        }
        return new Report(store.persistent(), s, calls);
    }

    static Summary summarise(String kind, boolean recommendedOnly, List<CallRow> all) {
        List<CallRow> mine = all.stream().filter(c -> c.kind().equals(kind) && (!recommendedOnly || c.recommended())).toList();
        List<CallRow> closed = mine.stream().filter(c -> !c.open() && c.returnPct() != null).toList();
        int open = (int) mine.stream().filter(CallRow::open).count();
        int noFill = (int) mine.stream().filter(c -> "no-fill".equals(c.status())).count();
        if (closed.isEmpty()) {
            return new Summary(kind, recommendedOnly, 0, open, noFill, 0, 0, null, 0, 0);
        }
        double wins = closed.stream().filter(c -> c.returnPct() > 0).count();
        double avgRet = closed.stream().mapToDouble(CallRow::returnPct).average().orElse(0);
        Double avgR = kind.equals("swing")
                ? closed.stream().filter(c -> c.rMultiple() != null).mapToDouble(CallRow::rMultiple).average().orElse(0) : null;
        List<CallRow> withBench = closed.stream().filter(c -> c.benchmark() != null).toList();
        double avgBench = withBench.stream().mapToDouble(CallRow::benchmark).average().orElse(0);
        double beat = withBench.isEmpty() ? 0
                : withBench.stream().filter(c -> c.returnPct() > c.benchmark()).count() / (double) withBench.size();
        return new Summary(kind, recommendedOnly, closed.size(), open, noFill, wins / closed.size(), avgRet, avgR, avgBench, beat);
    }

    // ---------- helpers ----------

    private List<Bar> safeBars(String symbol) {
        try {
            return data.dailyBars(symbol, historyYears);
        } catch (RuntimeException e) {
            if (e instanceof UnsupportedOperationException) {
                throw e;
            }
            return List.of();
        }
    }

    static int indexOnOrBefore(List<Bar> bars, LocalDate d) {
        int found = -1;
        for (int k = 0; k < bars.size(); k++) {
            if (bars.get(k).date().isAfter(d)) {
                break;
            }
            found = k;
        }
        // the signal day itself must be in the data, or the call can't be graded honestly
        return found >= 0 && bars.get(found).date().equals(d) ? found : -1;
    }

    /** Nifty over the same dates: open-to-open for momentum, entry open to exit close for swing. */
    static Double niftyReturn(List<Bar> nifty, LocalDate in, LocalDate out, boolean openToOpen) {
        Bar a = null;
        Bar b = null;
        for (Bar x : nifty) {
            if (x.date().equals(in)) {
                a = x;
            }
            if (x.date().equals(out)) {
                b = x;
            }
        }
        if (a == null || b == null) {
            return null;
        }
        return (openToOpen ? b.open() : b.close()) / a.open() - 1;
    }
}
