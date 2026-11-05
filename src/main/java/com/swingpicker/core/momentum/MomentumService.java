package com.swingpicker.core.momentum;

import com.swingpicker.core.CostModel;
import com.swingpicker.core.LiveSession;
import com.swingpicker.core.MarketDataProvider;
import com.swingpicker.core.MarketRegime;
import com.swingpicker.core.RecommendationService.Stock;
import com.swingpicker.core.momentum.MomentumBacktest.Params;
import com.swingpicker.core.momentum.MomentumBacktest.Performance;
import com.swingpicker.core.momentum.MomentumBacktest.Period;
import com.swingpicker.core.momentum.MomentumResult.MomentumStock;
import com.swingpicker.core.momentum.MomentumResult.StrategyRow;
import com.swingpicker.domain.Bar;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * Momentum picks sized to the amount, with the strategy's own backtest at that same number of stocks.
 * Plain Java, no Spring.
 */
public class MomentumService {

    /**
     * @param minPositionRupees smallest position worth buying (the ₹15.34 DP charge on sale is 0.3% of ₹5,000)
     * @param maxPositions      the strategy as designed holds this many
     * @param holdDays          sessions between re-checks (about a month)
     * @param buffer            keep a holding while it ranks within positions x buffer
     * @param historyYears      daily history to load (needs a year of lookback before the test starts)
     */
    public record Config(double minPositionRupees, int maxPositions, int holdDays, double buffer, int historyYears) {
    }

    private record Scored(Stock stock, MomentumScore score, double lastClose) {
    }

    private final List<Stock> universe;
    private final MarketDataProvider data;
    private final MarketRegime regime;
    private final CostModel costs;
    private final Config config;
    private final LiveSession session;

    public MomentumService(List<Stock> universe, MarketDataProvider data, MarketRegime regime, CostModel costs,
                           Config config) {
        this(universe, data, regime, costs, config, LiveSession.closed());
    }

    /** With a live session, today's ranking uses live prices while the market is open. Backtests never do. */
    public MomentumService(List<Stock> universe, MarketDataProvider data, MarketRegime regime, CostModel costs,
                           Config config, LiveSession session) {
        this.session = session;
        this.universe = List.copyOf(universe);
        this.data = data;
        this.regime = regime;
        this.costs = costs;
        this.config = config;
    }

    public MomentumResult recommend(double amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
        int n = (int) Math.max(1, Math.min(config.maxPositions(), Math.floor(amount / config.minPositionRupees())));
        double perPosition = amount / n;
        int maxPerSector = Math.max(1, (int) Math.ceil(n * 0.3));

        // Completed bars feed the backtest; completed + today's live bar (market hours only) feed today's ranking.
        Map<String, List<Bar>> bars = new LinkedHashMap<>();
        Map<String, List<Bar>> rankBars = new LinkedHashMap<>();
        fetchAll(bars, rankBars);
        List<Bar> nifty;
        try {
            nifty = data.dailyBars(MarketRegime.NIFTY, config.historyYears());
        } catch (RuntimeException e) {
            if (e instanceof UnsupportedOperationException) {
                throw e;
            }
            nifty = List.of();
        }

        // Backtests at the number of stocks this amount can actually hold.
        double buy = costs.variableCost(100, 0, 1) / 100;
        double sell = costs.variableCost(0, 100, 1) / 100 + costs.fixedCostPerStock() / perPosition;
        List<Period> plain = MomentumBacktest.run(universe, bars, nifty,
                new Params(n, config.holdDays(), config.buffer(), maxPerSector, false, buy, sell));
        List<Period> filtered = MomentumBacktest.run(universe, bars, nifty,
                new Params(n, config.holdDays(), config.buffer(), maxPerSector, true, buy, sell));
        LocalDate recentFrom = filtered.isEmpty() ? null
                : filtered.get(filtered.size() - 1).exitDate().minusYears(1);
        MomentumResult.Outlook outlook = outlook(plain, recentFrom);
        String label = "Top " + n + " momentum";
        List<StrategyRow> table = List.of(
                row(label + " + Nifty filter", filtered, Period::strategy, recentFrom),
                row(label + ", always invested", plain, Period::strategy, recentFrom),
                row("All " + bars.size() + " stocks, equal weight", filtered, Period::equalWeight, recentFrom),
                row("Nifty 50 index", filtered, Period::nifty, recentFrom));

        // Today's ranking: every stock whose latest bar is on the most common latest date
        // (stocks with stale data, e.g. suspended, are left out rather than ranked on old prices).
        LocalDate asOf = commonLatestDate(rankBars);
        boolean live = session.isOpen() && asOf != null && asOf.equals(session.now().toLocalDate());
        List<Scored> scored = new ArrayList<>();
        for (Stock s : universe) {
            List<Bar> b = rankBars.get(s.symbol());
            if (b == null || !b.get(b.size() - 1).date().equals(asOf)) {
                continue;
            }
            MomentumScore.at(b, b.size() - 1).ifPresent(sc -> scored.add(new Scored(s, sc, b.get(b.size() - 1).close())));
        }
        scored.sort(Comparator.comparingDouble((Scored x) -> x.score().score()).reversed());

        List<MomentumStock> picks = new ArrayList<>();
        Map<String, Integer> perSector = new HashMap<>();
        List<MomentumStock> ranking = new ArrayList<>();
        for (int k = 0; k < scored.size(); k++) {
            Scored x = scored.get(k);
            String note = "";
            if (picks.size() < n) {
                int qty = (int) Math.floor(perPosition / x.lastClose());
                if (perSector.getOrDefault(x.stock().sector(), 0) >= maxPerSector) {
                    note = "Skipped: sector limit (" + maxPerSector + " per sector)";
                } else if (qty < 1) {
                    note = "Skipped: one share costs more than " + rupees(perPosition);
                } else {
                    perSector.merge(x.stock().sector(), 1, Integer::sum);
                    MomentumStock pick = stock(k + 1, x, qty, qty * x.lastClose(), "Buy", outlook);
                    picks.add(pick);
                    ranking.add(pick);
                    continue;
                }
            }
            if (k < Math.max(20, 2 * n)) {
                ranking.add(stock(k + 1, x, 0, 0, note, outlook));
            }
        }

        Optional<Map.Entry<LocalDate, Boolean>> today = regime.latest();
        Boolean up = today.map(Map.Entry::getValue).orElse(null);
        StrategyRow strat = table.get(0);
        StrategyRow ew = table.get(2);
        boolean beatAll = strat.all().periods() > 0 && strat.all().annualReturn() > ew.all().annualReturn();
        boolean beatRecent = strat.recent().periods() > 0 && strat.recent().totalReturn() > ew.recent().totalReturn();

        String reason;
        boolean recommended = false;
        if (picks.isEmpty()) {
            reason = "Couldn't rank any stock right now: " + bars.size() + " of " + universe.size()
                    + " stocks loaded, " + scored.size() + " had a full year of prices on " + asOf
                    + ". Try again in a few minutes.";
        } else if (up == null) {
            reason = "Couldn't load Nifty data, so the market filter can't be checked. Treat these as information only.";
        } else if (!up) {
            reason = "Stay in cash: the Nifty 50 is below its 200-day average (or that average is falling). "
                    + "The filtered strategy holds nothing in this condition. Ranking shown for information.";
        } else if (!beatAll || !beatRecent) {
            reason = "The market filter says invest, but in the backtest this strategy (with " + n + " stocks) did NOT beat "
                    + "simply buying all " + bars.size() + " stocks equally" + (beatAll ? " in the last 12 months." : " over the test period.")
                    + " Shown so you can judge; not recommended.";
        } else {
            recommended = true;
            reason = "Nifty is in an uptrend, and with " + n + " stocks this strategy beat buying all " + bars.size() + " equally "
                    + "both over the whole test and in the last 12 months (after charges).";
        }

        if (asOf == null) {
            asOf = session.now().toLocalDate();
        }
        String howToHold = (live ? "Prices are live: buy now, or anytime today near these prices. " : "Buy at the next open. ")
                + "Hold about a month: re-run the app on or after "
                + outlook.reviewDate() + ". Keep anything you hold that still ranks in the top " + Math.round(n * config.buffer())
                + "; sell the rest and buy the new top names. There is no stop-loss: if the app says \"stay in cash\", sell everything.";
        return new MomentumResult(recommended, reason, up, asOf, live, n, perPosition, picks, ranking, table, recentFrom,
                howToHold, outlook);
    }

    private MomentumStock stock(int rank, Scored x, int qty, double amount, String note, MomentumResult.Outlook o) {
        // This stock's own one-period swing: annualised volatility scaled to the holding period.
        double periodVol = x.score().volatility() * Math.sqrt(config.holdDays() / 252.0);
        return new MomentumStock(rank, x.stock().symbol(), x.stock().sector(), x.score().score(), x.score().return6m(),
                x.score().return12m(), x.lastClose(), qty, amount, note,
                x.lastClose() * (1 + o.median()), Math.exp(periodVol) - 1, Math.exp(-periodVol) - 1);
    }

    private MomentumResult.Outlook outlook(List<Period> periods, LocalDate recentFrom) {
        List<Double> all = new ArrayList<>();
        List<Double> recent = new ArrayList<>();
        for (Period p : periods) {
            all.addAll(p.holdingReturns());
            if (recentFrom != null && !p.entryDate().isBefore(recentFrom)) {
                recent.addAll(p.holdingReturns());
            }
        }
        LocalDate review = session.now().toLocalDate().plusDays(Math.round(config.holdDays() * 7 / 5.0));
        double up = all.isEmpty() ? 0 : all.stream().filter(r -> r > 0).count() / (double) all.size();
        return new MomentumResult.Outlook(config.holdDays(), review, quantile(all, 0.5), quantile(all, 0.25),
                quantile(all, 0.75), up, quantile(recent, 0.5), all.size(), recent.size());
    }

    static double quantile(List<Double> values, double q) {
        if (values.isEmpty()) {
            return 0;
        }
        List<Double> v = new ArrayList<>(values);
        v.sort(null);
        double pos = q * (v.size() - 1);
        int lo = (int) Math.floor(pos);
        int hi = (int) Math.ceil(pos);
        return v.get(lo) + (v.get(hi) - v.get(lo)) * (pos - lo);
    }

    private StrategyRow row(String name, List<Period> periods, Function<Period, Double> f, LocalDate recentFrom) {
        int hold = config.holdDays();
        List<Double> all = periods.stream().map(f).toList();
        List<Double> earlier = periods.stream()
                .filter(p -> recentFrom == null || p.entryDate().isBefore(recentFrom)).map(f).toList();
        List<Double> recent = periods.stream()
                .filter(p -> recentFrom != null && !p.entryDate().isBefore(recentFrom)).map(f).toList();
        return new StrategyRow(name, Performance.of(all, hold), Performance.of(earlier, hold), Performance.of(recent, hold));
    }

    /** The latest bar date shared by the most stocks; null if nothing loaded. */
    static LocalDate commonLatestDate(Map<String, List<Bar>> bars) {
        Map<LocalDate, Integer> counts = new HashMap<>();
        bars.values().forEach(b -> counts.merge(b.get(b.size() - 1).date(), 1, Integer::sum));
        return counts.entrySet().stream()
                .max(Map.Entry.<LocalDate, Integer>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey).orElse(null);
    }

    private void fetchAll(Map<String, List<Bar>> completed, Map<String, List<Bar>> withLive) {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            Map<String, CompletableFuture<List<Bar>[]>> futures = new LinkedHashMap<>();
            for (Stock s : universe) {
                futures.put(s.symbol(), CompletableFuture.supplyAsync(() -> {
                    List<Bar> c = data.dailyBars(s.symbol(), config.historyYears());
                    @SuppressWarnings("unchecked")
                    List<Bar>[] pair = new List[]{c, LiveSession.extend(c, session.live(s.symbol()))};
                    return pair;
                }, pool));
            }
            for (Map.Entry<String, CompletableFuture<List<Bar>[]>> f : futures.entrySet()) {
                try {
                    List<Bar>[] pair = f.getValue().join();
                    if (!pair[0].isEmpty()) {
                        completed.put(f.getKey(), pair[0]);
                        withLive.put(f.getKey(), pair[1]);
                    }
                } catch (RuntimeException e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    if (cause instanceof UnsupportedOperationException u) {
                        throw u;
                    }
                    // a stock we can't load is simply not ranked
                }
            }
        } finally {
            pool.shutdown();
        }
    }

    private static String rupees(double v) {
        return "₹" + java.text.NumberFormat.getIntegerInstance(java.util.Locale.forLanguageTag("en-IN")).format(Math.round(v));
    }
}
