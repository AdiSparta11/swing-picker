package com.swingpicker.core;

import com.swingpicker.core.RecommendationService.Rules;
import com.swingpicker.core.RecommendationService.Stock;
import com.swingpicker.core.RiskBasedPositionSizer.Limits;
import com.swingpicker.domain.Bar;
import com.swingpicker.domain.Considered;
import com.swingpicker.domain.Edge;
import com.swingpicker.domain.Recommendation;
import com.swingpicker.domain.TradePlan;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecommendationServiceTest {

    static final List<Bar> ONE_BAR = List.of(new Bar(LocalDate.of(2026, 10, 28), 500, 505, 495, 500, 1000));

    /** Fires for the given symbols with entry 500, stop 480. */
    static Setup firing(String name, Set<String> symbols) {
        return new Setup() {
            public String name() {
                return name;
            }

            public Optional<TradePlan> detect(String symbol, List<Bar> bars) {
                return symbols.contains(symbol)
                        ? Optional.of(new TradePlan(symbol, name, LocalDate.of(2026, 10, 28), 500, 480, 540, 505, 15))
                        : Optional.empty();
            }
        };
    }

    /** Edge per symbol (pool size per setup name). */
    static EdgeEstimator edges(Map<String, Double> winRateBySymbol, Map<String, Integer> poolBySetup) {
        return new EdgeEstimator() {
            public Edge estimate(Setup setup, String symbol) {
                int pool = poolBySetup.get(setup.name());
                return new Edge(winRateBySymbol.get(symbol), 2.0, 1.0, 10, pool);
            }

            public Edge pool(Setup setup) {
                int pool = poolBySetup.get(setup.name());
                return new Edge(0.45, 2.0, 1.0, pool, pool);
            }
        };
    }

    static String statusOf(Recommendation r, String symbol, String setup) {
        return r.considered().stream()
                .filter(c -> c.candidate().plan().symbol().equals(symbol) && c.candidate().plan().setupName().equals(setup))
                .map(Considered::status).findFirst().orElse("<not considered>");
    }

    @Test
    void everyCandidateGetsAPlainReason() {
        List<Stock> uni = List.of(new Stock("BANK1", "Banks"), new Stock("BANK2", "Banks"), new Stock("BANK3", "Banks"),
                new Stock("WEAK", "IT"), new Stock("NEWSETUP", "Pharma"));
        // win rates -> expectancy 3p - 1: 0.50 -> +0.50R, 0.45 -> +0.35R, 0.40 -> +0.20R, 0.30 -> -0.10R
        Map<String, Double> p = Map.of("BANK1", 0.50, "BANK2", 0.45, "BANK3", 0.40, "WEAK", 0.30, "NEWSETUP", 0.50);
        Setup pullback = firing("pullback", Set.of("BANK1", "BANK2", "BANK3", "WEAK"));
        Setup breakout = firing("breakout", Set.of("BANK2", "NEWSETUP"));
        CostModel costs = new NseDeliveryCostModel();
        RecommendationService svc = new RecommendationService(uni, List.of(pullback, breakout), (s, y) -> ONE_BAR,
                edges(p, Map.of("pullback", 400, "breakout", 12)), costs, new ExpectedNetRRanker(),
                new RiskBasedPositionSizer(costs, new Limits(0.02, 0.35, 10, 0.10)), new Rules(0.10, 30, 2, 3, 2));

        Recommendation r = svc.recommend(30_000);

        assertTrue(r.trade());
        assertEquals(5, r.stocksScanned());
        assertEquals(List.of("BANK1", "BANK2"), r.picks().stream().map(x -> x.candidate().plan().symbol()).toList());
        assertEquals("Picked", statusOf(r, "BANK1", "pullback"));
        assertTrue(statusOf(r, "BANK3", "pullback").startsWith("Sector limit"));
        assertTrue(statusOf(r, "WEAK", "pullback").startsWith("History too weak"));
        assertTrue(statusOf(r, "NEWSETUP", "breakout").contains("too little history"));
        assertTrue(statusOf(r, "BANK2", "breakout").contains("too little history"));
        assertEquals(6, r.considered().size());
        assertTrue(r.considered().get(0).picked() && r.considered().get(1).picked());
        assertEquals(2, r.setups().size());
        assertEquals(400, r.setups().get(0).trades());
    }

    @Test
    void sameStockFromTwoSetupsBecomesOnePick() {
        List<Stock> uni = List.of(new Stock("DUAL", "Auto"));
        Setup a = firing("a", Set.of("DUAL"));
        Setup b = firing("b", Set.of("DUAL"));
        CostModel costs = new NseDeliveryCostModel();
        RecommendationService svc = new RecommendationService(uni, List.of(a, b), (s, y) -> ONE_BAR,
                edges(Map.of("DUAL", 0.5), Map.of("a", 100, "b", 100)), costs, new ExpectedNetRRanker(),
                new RiskBasedPositionSizer(costs, new Limits(0.02, 0.35, 10, 0.10)), new Rules(0.10, 30, 2, 3, 2));

        Recommendation r = svc.recommend(30_000);

        assertEquals(1, r.picks().size());
        assertEquals(1, r.considered().stream().filter(c -> c.status().startsWith("Same stock")).count());
    }

    @Test
    void noTradeStillExplainsWhatWasSeen() {
        List<Stock> uni = List.of(new Stock("WEAK", "IT"));
        CostModel costs = new NseDeliveryCostModel();
        RecommendationService svc = new RecommendationService(uni, List.of(firing("pullback", Set.of("WEAK"))),
                (s, y) -> ONE_BAR, edges(Map.of("WEAK", 0.30), Map.of("pullback", 400)), costs,
                new ExpectedNetRRanker(), new RiskBasedPositionSizer(costs, new Limits(0.02, 0.35, 10, 0.10)),
                new Rules(0.10, 30, 2, 3, 2));

        Recommendation r = svc.recommend(10_000);

        assertFalse(r.trade());
        assertEquals(1, r.considered().size());
        assertTrue(r.considered().get(0).status().startsWith("History too weak"));
        assertEquals(1, r.setups().size());
    }
}
