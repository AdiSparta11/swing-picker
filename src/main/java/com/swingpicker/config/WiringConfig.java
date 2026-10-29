package com.swingpicker.config;

import com.swingpicker.core.BacktestEdgeEstimator;
import com.swingpicker.core.BreakoutSetup;
import com.swingpicker.core.CostModel;
import com.swingpicker.core.ExpectedNetRRanker;
import com.swingpicker.core.MarketDataProvider;
import com.swingpicker.core.NseDeliveryCostModel;
import com.swingpicker.core.RecommendationService;
import com.swingpicker.core.RecommendationService.Rules;
import com.swingpicker.core.RiskBasedPositionSizer;
import com.swingpicker.core.RiskBasedPositionSizer.Limits;
import com.swingpicker.core.TrendPullbackSetup;
import com.swingpicker.data.CachingMarketDataProvider;
import com.swingpicker.data.UniverseLoader;
import com.swingpicker.data.UniverseLoader.Universe;
import com.swingpicker.data.YahooChartParser;
import com.swingpicker.data.YahooMarketDataProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

/**
 * The only place that knows about Spring AND the core. Every tunable number lives here.
 */
@Configuration
public class WiringConfig {

    private static final Logger log = LoggerFactory.getLogger(WiringConfig.class);

    /** How the whole pipeline behaves. Change numbers here, not in the core. */
    static final int HISTORY_YEARS = 3;
    static final Rules RULES = new Rules(
            0.10,   // a pick must expect > +0.10R per trade after costs
            30,     // and the setup must have >= 30 historical trades across the universe
            2,      // at most 2 picks per industry
            HISTORY_YEARS,
            8);     // parallel Yahoo fetches
    static final Limits LIMITS = new Limits(
            0.02,   // risk 2% of the entered amount per stock
            0.35,   // no stock takes more than 35% of the amount
            10,     // at most 10 picks
            RULES.minExpectedNetR());
    static final double PRIOR_TRADES = 20;   // per-stock stats blended toward the universe as if it were 20 trades

    @Bean
    public Clock clock() {
        return Clock.system(YahooChartParser.IST);
    }

    @Bean
    public HttpClient httpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Bean
    public Universe universe(HttpClient http) throws IOException {
        String bundled = new ClassPathResource("universe.csv").getContentAsString(StandardCharsets.UTF_8);
        Universe u = UniverseLoader.load(http, UniverseLoader.NIFTY_200_URLS, bundled);
        log.info("Universe: {} stocks from {}", u.stocks().size(), u.source());
        return u;
    }

    /** Yahoo daily bars, cached for an hour per symbol. */
    @Bean
    public MarketDataProvider marketDataProvider(HttpClient http, Clock clock) {
        return new CachingMarketDataProvider(new YahooMarketDataProvider(http, clock), Duration.ofHours(1), clock);
    }

    @Bean
    public CostModel costModel() {
        return new NseDeliveryCostModel();
    }

    @Bean
    public RecommendationService recommendationService(Universe universe, MarketDataProvider data,
                                                       CostModel costs, Clock clock) {
        var edges = new BacktestEdgeEstimator(universe.stocks(), data, HISTORY_YEARS, PRIOR_TRADES,
                Duration.ofHours(6), clock);
        return new RecommendationService(universe.stocks(), List.of(new TrendPullbackSetup(), new BreakoutSetup()), data, edges,
                costs, new ExpectedNetRRanker(), new RiskBasedPositionSizer(costs, LIMITS), RULES);
    }
}
