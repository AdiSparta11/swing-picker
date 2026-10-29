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
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * "I want to invest X today" -> sized picks, or an explicit no.
 * Plain Java on purpose: no Spring here, so every rule is unit-testable by hand.
 */
public class RecommendationService {

    /** A tradable stock and its sector (for the diversification cap). */
    public record Stock(String symbol, String sector) {
    }

    /**
     * @param minExpectedNetR  a candidate must beat this after costs, else it's not worth the risk
     * @param minSampleSize    ignore edges built on too few historical trades
     * @param maxPerSector     at most this many picks from one sector
     * @param historyYears     how much daily history to load
     */
    public record Rules(double minExpectedNetR, int minSampleSize, int maxPerSector, int historyYears) {
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

        // 1. Every stock where some setup fired on the latest bar, scored after costs.
        List<Candidate> candidates = new ArrayList<>();
        for (Stock stock : universe) {
            List<Bar> bars = data.dailyBars(stock.symbol(), rules.historyYears());
            if (bars.isEmpty()) {
                continue;
            }
            for (Setup setup : setups) {
                Optional<TradePlan> plan = setup.detect(stock.symbol(), bars);
                if (plan.isEmpty()) {
                    continue;
                }
                Edge edge = edges.estimate(setup);
                if (edge.sampleSize() < rules.minSampleSize()) {
                    continue;
                }
                double score = ranker.expectedNetR(edge, costInR(plan.get()));
                candidates.add(new Candidate(plan.get(), stock.sector(), edge, score));
            }
        }
        if (candidates.isEmpty()) {
            return Recommendation.noTrade(amount, "No setup fired today on any stock in the universe.");
        }

        // 2. Keep only real edges, best first, capped per sector.
        List<Candidate> ranked = diversify(candidates.stream()
                .filter(c -> c.expectedNetR() > rules.minExpectedNetR())
                .sorted(Comparator.comparingDouble(Candidate::expectedNetR).reversed())
                .toList());
        if (ranked.isEmpty()) {
            return Recommendation.noTrade(amount, candidates.size()
                    + " setup(s) fired, but none has a positive expected return after costs.");
        }

        // 3. Turn them into share quantities that fit the amount.
        List<Pick> picks = sizer.size(ranked, amount);
        if (picks.isEmpty()) {
            return Recommendation.noTrade(amount,
                    "Good setups exist, but this amount is too small to buy them without costs eating the edge.");
        }
        return Recommendation.of(amount, picks);
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
