package com.swingpicker.config;

import com.swingpicker.core.BacktestEdgeEstimator;
import com.swingpicker.core.BreakoutSetup;
import com.swingpicker.core.CostModel;
import com.swingpicker.core.ExpectedNetRRanker;
import com.swingpicker.core.LiveSession;
import com.swingpicker.core.MarketDataProvider;
import com.swingpicker.core.MarketRegime;
import com.swingpicker.core.NseDeliveryCostModel;
import com.swingpicker.core.RecommendationService;
import com.swingpicker.core.RegimeFilteredSetup;
import com.swingpicker.core.Setup;
import com.swingpicker.core.RecommendationService.Rules;
import com.swingpicker.core.RiskBasedPositionSizer;
import com.swingpicker.core.RiskBasedPositionSizer.Limits;
import com.swingpicker.core.TrendPullbackSetup;
import com.swingpicker.core.analysis.StockAnalysisService;
import com.swingpicker.core.momentum.MomentumService;
import com.swingpicker.data.CachingMarketDataProvider;
import com.swingpicker.data.SymbolSearch;
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
import java.util.Map;

/**
 * The only place that knows about Spring AND the core. Every tunable number lives here.
 */
@Configuration
public class WiringConfig {

    private static final Logger log = LoggerFactory.getLogger(WiringConfig.class);

    /** How the whole pipeline behaves. Change numbers here, not in the core. */
    static final int HISTORY_YEARS = 5;     // momentum needs 1 year of lookback before its test starts
    static final Rules RULES = new Rules(
            0.10,   // a pick must expect > +0.10R per trade after costs
            30,     // and the setup must have >= 30 historical trades across the universe
            2,      // at most 2 picks per industry
            HISTORY_YEARS,
            8,      // parallel Yahoo fetches
            true);  // a setup must have made money both in earlier years and in the last 12 months
    static final MomentumService.Config MOMENTUM = new MomentumService.Config(
            5000,   // smallest position worth buying (DP charge is 0.3% of ₹5,000)
            10,     // at most 10 stocks
            21,     // re-check about monthly
            2.0,    // keep a holding while it ranks within 2x the number held
            HISTORY_YEARS);
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
        String bundled = new ClassPathResource("nifty200.csv").getContentAsString(StandardCharsets.UTF_8);
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

    /** Market hours + today's live bar (09:15-15:30 IST on weekdays). */
    @Bean
    public LiveSession liveSession(MarketDataProvider data, Clock clock) {
        return new LiveSession(data, clock);
    }

    @Bean
    public MarketRegime marketRegime(MarketDataProvider data, LiveSession session) {
        return new MarketRegime(data, HISTORY_YEARS, session);
    }

    /** The swing setups, each one plain and with the Nifty uptrend filter, so the table shows what the filter changes. */
    public record SwingSetups(List<Setup> all) {
    }

    /** Company names by symbol, from NSE's file (for search and display). */
    public record CompanyNames(Map<String, String> bySymbol) {
    }

    @Bean
    public SwingSetups swingSetups(MarketRegime regime) {
        Setup pullback = new TrendPullbackSetup();
        Setup breakout = new BreakoutSetup();
        return new SwingSetups(List.of(new RegimeFilteredSetup(pullback, regime), new RegimeFilteredSetup(breakout, regime),
                pullback, breakout));
    }

    /** Swing setups' track records, shared by "Find trades" and "Check a stock" so both show the same numbers. */
    @Bean
    public BacktestEdgeEstimator edgeEstimator(Universe universe, MarketDataProvider data, Clock clock) {
        return new BacktestEdgeEstimator(universe.stocks(), data, HISTORY_YEARS, PRIOR_TRADES, Duration.ofHours(6), clock);
    }

    @Bean
    public RecommendationService recommendationService(Universe universe, MarketDataProvider data, CostModel costs,
                                                       SwingSetups setups, BacktestEdgeEstimator edges,
                                                       LiveSession session) {
        return new RecommendationService(universe.stocks(), setups.all(), data, edges,
                costs, new ExpectedNetRRanker(), new RiskBasedPositionSizer(costs, LIMITS), RULES, session);
    }

    @Bean
    public CompanyNames companyNames() throws IOException {
        String csv = new ClassPathResource("nifty200.csv").getContentAsString(StandardCharsets.UTF_8);
        return new CompanyNames(UniverseLoader.companyNames(csv));
    }

    @Bean
    public SymbolSearch symbolSearch(CompanyNames names, HttpClient http) {
        return new SymbolSearch(names.bySymbol(), http);
    }

    @Bean
    public StockAnalysisService stockAnalysisService(Universe universe, CompanyNames names, MarketDataProvider data,
                                                     MarketRegime regime, SwingSetups setups,
                                                     BacktestEdgeEstimator edges, LiveSession session, Clock clock) {
        return new StockAnalysisService(universe.stocks(), names.bySymbol(), data, regime, setups.all(), edges, session,
                clock, new StockAnalysisService.Config(HISTORY_YEARS, 21, Duration.ofHours(6)));
    }

    @Bean
    public MomentumService momentumService(Universe universe, MarketDataProvider data, MarketRegime regime,
                                           CostModel costs, LiveSession session) {
        return new MomentumService(universe.stocks(), data, regime, costs, MOMENTUM, session);
    }
}
