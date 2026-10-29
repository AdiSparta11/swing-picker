package com.swingpicker.core;

import com.swingpicker.domain.Bar;
import com.swingpicker.domain.Candidate;
import com.swingpicker.domain.Edge;
import com.swingpicker.domain.Pick;
import com.swingpicker.domain.Recommendation;
import com.swingpicker.domain.TradePlan;

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

        // 1. Every stock where some setup fired on the latest bar, scored after costs.
        List<Candidate> candidates = new ArrayList<>();
        for (Map.Entry<Stock, List<Bar>> e : bars.entrySet()) {
            Stock stock = e.getKey();
            for (Setup setup : setups) {
                Optional<TradePlan> plan = setup.detect(stock.symbol(), e.getValue());
                if (plan.isEmpty()) {
                    continue;
                }
                Edge edge = edges.estimate(setup, stock.symbol());
                if (edge.poolTrades() < rules.minPoolTrades()) {
                    continue;
                }
                double score = ranker.expectedNetR(edge, costInR(plan.get()));
                candidates.add(new Candidate(plan.get(), stock.sector(), edge, score));
            }
        }
        if (candidates.isEmpty()) {
            return Recommendation.noTrade(amount, "No setup fired on the latest session across "
                    + bars.size() + " stocks. Waiting is a position too.");
        }

        // 2. Keep only real edges, best first, capped per sector.
        List<Candidate> ranked = diversify(candidates.stream()
                .filter(c -> c.expectedNetR() > rules.minExpectedNetR())
                .sorted(Comparator.comparingDouble(Candidate::expectedNetR).reversed())
                .toList());
        if (ranked.isEmpty()) {
            return Recommendation.noTrade(amount, candidates.size()
                    + " setup(s) fired, but none has enough historical edge after costs.");
        }

        // 3. Turn them into share quantities that fit the amount.
        List<Pick> picks = sizer.size(ranked, amount);
        if (picks.isEmpty()) {
            return Recommendation.noTrade(amount,
                    "Good setups exist, but this amount is too small to buy them without costs eating the edge.");
        }
        return Recommendation.of(amount, picks);
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

    private List<Candidate> diversify(List<Candidate> bestFirst) {
        Map<String, Integer> perSector = new HashMap<>();
        List<Candidate> kept = new ArrayList<>();
        for (Candidate c : bestFirst) {
            int count = perSector.merge(c.sector(), 1, Integer::sum);
            if (count <= rules.maxPerSector()) {
                kept.add(c);
            }
        }
        return kept;
    }
}
