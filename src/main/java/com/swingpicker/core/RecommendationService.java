package com.swingpicker.core;

import com.swingpicker.domain.Bar;
import com.swingpicker.domain.Candidate;
import com.swingpicker.domain.Considered;
import com.swingpicker.domain.Edge;
import com.swingpicker.domain.Pick;
import com.swingpicker.domain.Recommendation;
import com.swingpicker.domain.SetupRecord;
import com.swingpicker.domain.TradePlan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "I want to invest X today" -> sized picks, or an explicit no.
 * Plain Java on purpose: no Spring here, so every rule is unit-testable.
 */
public class RecommendationService {

    /** A tradable stock and its sector (for the diversification cap). */
    public record Stock(String symbol, String sector) {
    }

    /**
     * @param minExpectedNetR  a candidate must beat this after costs, else it's not worth the risk
     * @param minPoolTrades    ignore a setup whose whole-universe history has fewer trades than this
     * @param maxPerSector     at most this many picks from one sector
     * @param historyYears     how much daily history to load
     * @param fetchThreads     parallel market-data fetches
     */
    public record Rules(double minExpectedNetR, int minPoolTrades, int maxPerSector, int historyYears,
                        int fetchThreads) {
    }

    private final List<Stock> universe;
    private final List<Setup> setups;
    private final MarketDataProvider data;
    private final EdgeEstimator edges;
    private final CostModel costs;
    private final Ranker ranker;
    private final PositionSizer sizer;
    private final Rules rules;

    public RecommendationService(List<Stock> universe, List<Setup> setups, MarketDataProvider data,
                                 EdgeEstimator edges, CostModel costs, Ranker ranker,
                                 PositionSizer sizer, Rules rules) {
        this.universe = List.copyOf(universe);
        this.setups = List.copyOf(setups);
        this.data = data;
        this.edges = edges;
        this.costs = costs;
        this.ranker = ranker;
        this.sizer = sizer;
        this.rules = rules;
    }

    public Recommendation recommend(double amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }

        Map<Stock, List<Bar>> bars = fetchAll();
        int scanned = bars.size();
        List<SetupRecord> records = setups.stream().map(s -> SetupRecord.of(s.name(), edges.pool(s))).toList();

        // 1. Every stock where some setup fired on the latest bar, scored after costs.
        //    Each one gets a status; anything not picked says why.
        Map<Candidate, String> status = new LinkedHashMap<>();
        for (Map.Entry<Stock, List<Bar>> e : bars.entrySet()) {
            Stock stock = e.getKey();
            for (Setup setup : setups) {
                Optional<TradePlan> plan = setup.detect(stock.symbol(), e.getValue());
                if (plan.isEmpty()) {
                    continue;
                }
                Edge edge = edges.estimate(setup, stock.symbol());
                double score = ranker.expectedNetR(edge, costInR(plan.get()));
                Candidate c = new Candidate(plan.get(), stock.sector(), edge, score);
                if (edge.poolTrades() < rules.minPoolTrades()) {
                    status.put(c, "This setup has too little history to trust (" + edge.poolTrades()
                            + " past trades, needs " + rules.minPoolTrades() + ")");
                } else if (score <= rules.minExpectedNetR()) {
                    status.put(c, String.format("History too weak: %+.2fR per trade after charges (needs above %+.2fR)",
                            score, rules.minExpectedNetR()));
                } else {
                    status.put(c, null); // still in the running
                }
            }
        }
        if (status.isEmpty()) {
            return Recommendation.noTrade(amount, "No setup fired on the latest session across "
                    + scanned + " stocks. Waiting is a position too.", scanned, List.of(), records);
        }

        // 2. Best first; one pick per stock; capped per sector.
        List<Candidate> eligible = status.entrySet().stream()
                .filter(e -> e.getValue() == null)
                .map(Map.Entry::getKey)
                .sorted(Comparator.comparingDouble(Candidate::expectedNetR).reversed())
                .toList();
        List<Candidate> ranked = diversify(eligible, status);

        // 3. Turn them into share quantities that fit the amount.
        List<Pick> picks = ranked.isEmpty() ? List.of() : sizer.size(ranked, amount);
        Set<Candidate> pickedSet = new HashSet<>();
        picks.forEach(p -> pickedSet.add(p.candidate()));
        for (Candidate c : ranked) {
            status.put(c, pickedSet.contains(c) ? "Picked"
                    : "Didn't fit: not enough of the amount left, or charges too high for the quantity it allows");
        }

        List<Considered> considered = status.entrySet().stream()
                .map(e -> new Considered(e.getKey(), pickedSet.contains(e.getKey()), e.getValue()))
                .sorted(Comparator.comparing((Considered c) -> !c.picked())
                        .thenComparing(c -> -c.candidate().expectedNetR()))
                .toList();

        if (picks.isEmpty()) {
            String reason = ranked.isEmpty()
                    ? status.size() + " setup(s) fired, but none has enough historical edge after costs."
                    : "Good setups exist, but this amount is too small to buy them without charges eating the edge.";
            return Recommendation.noTrade(amount, reason, scanned, considered, records);
        }
        return Recommendation.of(amount, picks, scanned, considered, records);
    }

    /** Fetch every stock in parallel. A stock that fails is skipped; if all fail, the last failure surfaces. */
    private Map<Stock, List<Bar>> fetchAll() {
        ExecutorService pool = Executors.newFixedThreadPool(rules.fetchThreads());
        try {
            Map<Stock, CompletableFuture<List<Bar>>> futures = new LinkedHashMap<>();
            for (Stock s : universe) {
                futures.put(s, CompletableFuture.supplyAsync(() -> data.dailyBars(s.symbol(), rules.historyYears()), pool));
            }
            Map<Stock, List<Bar>> out = new LinkedHashMap<>();
            RuntimeException lastFailure = null;
            for (Map.Entry<Stock, CompletableFuture<List<Bar>>> f : futures.entrySet()) {
                try {
                    List<Bar> b = f.getValue().join();
                    if (!b.isEmpty()) {
                        out.put(f.getKey(), b);
                    }
                } catch (RuntimeException e) {
                    RuntimeException cause = (e.getCause() instanceof RuntimeException r) ? r : e;
                    if (cause instanceof UnsupportedOperationException) {
                        throw cause;
                    }
                    lastFailure = cause;
                }
            }
            if (out.isEmpty() && lastFailure != null) {
                throw lastFailure;
            }
            return out;
        } finally {
            pool.shutdown();
        }
    }

    /**
     * Variable round-trip cost per share (exiting at the stop), expressed in R.
     * Size-independent, so it's a fair ranking input. Fixed per-stock charges are the sizer's job.
     */
    private double costInR(TradePlan plan) {
        return costs.variableCost(plan.entry(), plan.stop(), 1) / plan.riskPerShare();
    }

    /** Keeps the best candidate per stock and at most maxPerSector per sector; records why others were dropped. */
    private List<Candidate> diversify(List<Candidate> bestFirst, Map<Candidate, String> status) {
        Map<String, Integer> perSector = new HashMap<>();
        Set<String> symbols = new HashSet<>();
        List<Candidate> kept = new ArrayList<>();
        for (Candidate c : bestFirst) {
            if (!symbols.add(c.plan().symbol())) {
                status.put(c, "Same stock already chosen through a stronger setup");
                continue;
            }
            int count = perSector.merge(c.sector(), 1, Integer::sum);
            if (count > rules.maxPerSector()) {
                status.put(c, "Sector limit: already " + rules.maxPerSector() + " stronger picks in " + c.sector());
                continue;
            }
            kept.add(c);
        }
        return kept;
    }
}
