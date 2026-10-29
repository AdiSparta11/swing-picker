package com.swingpicker.config;

import com.swingpicker.core.CostModel;
import com.swingpicker.core.EdgeEstimator;
import com.swingpicker.core.MarketDataProvider;
import com.swingpicker.core.PositionSizer;
import com.swingpicker.core.Ranker;
import com.swingpicker.core.RecommendationService;
import com.swingpicker.core.RecommendationService.Rules;
import com.swingpicker.core.RecommendationService.Stock;
import com.swingpicker.core.Setup;
import com.swingpicker.domain.Bar;
import com.swingpicker.domain.TradePlan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * The only place that knows about Spring AND the core. Each stub below gets replaced
 * by a real implementation as we finish its step.
 */
@Configuration
public class WiringConfig {

    @Bean
    public RecommendationService recommendationService() throws IOException {
        MarketDataProvider data = (symbol, years) -> {
            throw new UnsupportedOperationException("Step 8: market data client");
        };
        Setup pullback = new Setup() {
            public String name() {
                return "trend-pullback";
            }

            public Optional<TradePlan> detect(String symbol, List<Bar> bars) {
                throw new UnsupportedOperationException("Steps 1-2: setup rules and exit plan");
            }
        };
        EdgeEstimator edges = setup -> {
            throw new UnsupportedOperationException("Step 5: backtest");
        };
        CostModel costs = new CostModel() {
            public double variableCost(double buy, double sell, int qty) {
                throw new UnsupportedOperationException("Step 3: NSE delivery cost model");
            }

            public double fixedCostPerStock() {
                throw new UnsupportedOperationException("Step 3: NSE delivery cost model");
            }
        };
        Ranker ranker = (edge, costR) -> {
            throw new UnsupportedOperationException("Ranking drill: expected net R");
        };
        PositionSizer sizer = (ranked, amount) -> {
            throw new UnsupportedOperationException("Step 4: position sizing");
        };

        Rules rules = new Rules(0.10, 30, 2, 3);
        return new RecommendationService(loadUniverse(), List.of(pullback), data, edges, costs, ranker, sizer, rules);
    }

    /** universe.csv: symbol,sector. Starts as a short sample; we replace it with Nifty 200. */
    private static List<Stock> loadUniverse() throws IOException {
        try (var in = new BufferedReader(new InputStreamReader(
                new ClassPathResource("universe.csv").getInputStream(), StandardCharsets.UTF_8))) {
            return in.lines()
                    .skip(1)
                    .filter(line -> !line.isBlank())
                    .map(line -> line.split(","))
                    .map(p -> new Stock(p[0].trim(), p[1].trim()))
                    .toList();
        }
    }
}
