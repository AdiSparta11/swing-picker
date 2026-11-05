package com.swingpicker.core.analysis;

import com.swingpicker.core.BacktestEdgeEstimator;
import com.swingpicker.core.LiveSession;
import com.swingpicker.core.MarketDataProvider;
import com.swingpicker.core.MarketRegime;
import com.swingpicker.core.RecommendationService.Stock;
import com.swingpicker.core.TrendPullbackSetup;
import com.swingpicker.core.analysis.StockAnalysis.HorizonOutlook;
import com.swingpicker.core.analysis.StockAnalysis.Momentum;
import com.swingpicker.core.analysis.StockAnalysis.Trend;
import com.swingpicker.core.analysis.StockAnalysis.Verdict;
import com.swingpicker.core.analysis.StockAnalysisService.Position;
import com.swingpicker.data.MarketDataException;
import com.swingpicker.domain.Bar;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StockAnalysisTest {

    static final LocalDate START = LocalDate.of(2021, 11, 1);

    /** Weekday calendar random walk with drift. */
    static List<Bar> walk(long seed, double drift, int days) {
        Random r = new Random(seed);
        List<Bar> bars = new ArrayList<>();
        double c = 100 + r.nextInt(400);
        LocalDate d = START;
        while (bars.size() < days) {
            if (d.getDayOfWeek().getValue() < 6) {
                double o = c * (1 + r.nextGaussian() * 0.003);
                double nc = o * (1 + drift + r.nextGaussian() * 0.015);
                bars.add(new Bar(d, o, Math.max(o, nc) * 1.004, Math.min(o, nc) * 0.996, nc, 100_000));
                c = nc;
            }
            d = d.plusDays(1);
        }
        return bars;
    }

    // ---------- the model ----------

    @Test
    void quintilesSplitTheRankingIntoFifths() {
        assertEquals(1, ForwardReturnModel.quintileOf(0.95));
        assertEquals(1, ForwardReturnModel.quintileOf(1.0));
        assertEquals(2, ForwardReturnModel.quintileOf(0.7));
        assertEquals(3, ForwardReturnModel.quintileOf(0.5));
        assertEquals(5, ForwardReturnModel.quintileOf(0.0));
    }

    @Test
    void modelMeasuresForwardReturnsByQuintileWithABaseline() {
        // Persistent drifts: yesterday's leaders keep leading, so quintile 1 should beat quintile 5 and the baseline.
        Map<String, List<Bar>> bars = new HashMap<>();
        List<Stock> uni = new ArrayList<>();
        for (int k = 0; k < 50; k++) {
            uni.add(new Stock("S" + k, "x"));
            bars.put("S" + k, walk(k, -0.001 + 0.00004 * k, 1240));
        }
        List<Bar> nifty = walk(999, 0.0005, 1240);

        ForwardReturnModel m = ForwardReturnModel.build(uni, bars, nifty, 21);

        for (int h = 0; h < ForwardReturnModel.HORIZONS.length; h++) {
            ForwardReturnModel.Stats top = m.get(1, null, h);
            ForwardReturnModel.Stats bottom = m.get(5, null, h);
            ForwardReturnModel.Stats all = m.get(0, null, h);
            assertTrue(top.n() > 0 && bottom.n() > 0);
            assertTrue(top.median() > bottom.median(), "horizon " + h);
            assertTrue(top.median() > all.median());
            assertTrue(top.p25() <= top.median() && top.median() <= top.p75());
            assertEquals(all.n(), m.get(1, null, h).n() + m.get(2, null, h).n() + m.get(3, null, h).n()
                    + m.get(4, null, h).n() + m.get(5, null, h).n());
        }
        // Longer horizons have fewer samples (less future available at the end of the history).
        assertTrue(m.get(0, null, 3).n() < m.get(0, null, 0).n());
    }

    // ---------- the verdict ----------

    static Trend trend(boolean above, boolean cross) {
        return new Trend(100, 95, above ? 0.05 : -0.08, above, cross, 120, 80, -0.1, 0.01, 0.02, 0.05, 0.2, 0.3);
    }

    static HorizonOutlook sixMonths(double median, double baseline) {
        return new HorizonOutlook("6 months", 126, LocalDate.now(), 110, 95, 120, median, -0.05, 0.2, 0.6, 500,
                baseline, true, null);
    }

    @Test
    void verdictAddsUpExplainedPoints() {
        Verdict strong = StockAnalysisService.verdict(new Momentum(2, 0.92, 1, 200), trend(true, true), true,
                List.of(sixMonths(0.08, 0.04)), List.of(), true);
        assertEquals(2 + 1 + 0 + 1, strong.points());
        assertEquals("Strong", strong.label());
        assertTrue(strong.action().startsWith("Hold"));
        assertEquals(4, strong.reasons().size());

        Verdict weak = StockAnalysisService.verdict(new Momentum(-1, 0.10, 5, 200), trend(false, false), false,
                List.of(sixMonths(-0.02, 0.03)), List.of(), false);
        assertEquals(-2 - 1 - 1 - 1, weak.points());
        assertEquals("Very weak", weak.label());
        assertTrue(weak.action().startsWith("Avoid"));
        assertTrue(weak.reasons().get(1).text().contains("8.0% below its 200-day average"), weak.reasons().get(1).text());
    }

    // ---------- the holding ----------

    @Test
    void holdingAtALossExplainsBreakEvenAndShortTermLoss() {
        Verdict v = new Verdict(-1, "Weak", "x", List.of());
        StockAnalysis.Holding h = StockAnalysisService.holding(
                new Position(29, 4230.52, LocalDate.now().minusMonths(5)), 3187.50, v);

        assertEquals(29 * 4230.52, h.invested(), 1e-6);
        assertEquals(29 * 3187.50, h.value(), 1e-6);
        assertEquals(4230.52 / 3187.50 - 1, h.breakEvenGain(), 1e-12);   // +32.7% needed
        assertFalse(h.longTerm());
        assertTrue(h.notes().stream().anyMatch(n -> n.contains("short-term capital loss")));
        assertTrue(h.notes().stream().anyMatch(n -> n.contains("would you buy this stock")));
    }

    @Test
    void holdingInProfitNearTheOneYearMarkSuggestsWaitingForLongTermTax() {
        Verdict v = new Verdict(0, "Neutral", "x", List.of());
        LocalDate bought = LocalDate.now().minusYears(1).plusDays(20);
        StockAnalysis.Holding h = StockAnalysisService.holding(new Position(10, 100, bought), 130, v);

        assertEquals(0.0, h.breakEvenGain(), 1e-12);
        assertEquals(bought.plusYears(1).plusDays(1), h.longTermFrom());
        assertTrue(h.notes().stream().anyMatch(n -> n.contains("short-term (20%)") && n.contains("waiting may save tax")));
    }

    @Test
    void longTermGainNoteWithoutBuyDateFallsBackToBothRates() {
        StockAnalysis.Holding h = StockAnalysisService.holding(new Position(10, 100, null), 130,
                new Verdict(0, "Neutral", "x", List.of()));

        assertNull(h.longTerm());
        assertTrue(h.notes().stream().anyMatch(n -> n.contains("20%") && n.contains("12.5%")));
    }

    // ---------- end to end ----------

    @Test
    void analysesAStockOutsideTheUniverseAndRanksItAgainstTheUniverse() {
        Map<String, List<Bar>> bars = new HashMap<>();
        List<Stock> uni = new ArrayList<>();
        for (int k = 0; k < 40; k++) {
            uni.add(new Stock("S" + k, "sec" + (k % 5)));
            bars.put("S" + k, walk(k, -0.0005 + 0.00003 * k, 1240));
        }
        bars.put("KAYNES", walk(500, 0.002, 1240));     // a strong stock that isn't in the universe
        bars.put(MarketRegime.NIFTY, walk(999, 0.0006, 1240));
        MarketDataProvider data = (sym, y) -> Optional.ofNullable(bars.get(sym))
                .orElseThrow(() -> new MarketDataException("Unknown NSE symbol: " + sym));
        Clock clock = Clock.fixed(Instant.parse("2026-11-05T12:00:00Z"), ZoneOffset.UTC);
        MarketRegime regime = new MarketRegime(data, 5);
        var edges = new BacktestEdgeEstimator(uni, data, 5, 20, Duration.ofHours(6), clock);
        StockAnalysisService svc = new StockAnalysisService(uni, Map.of("S1", "Stock One Ltd."), data, regime,
                List.of(new TrendPullbackSetup()), edges, LiveSession.closed(), clock,
                new StockAnalysisService.Config(5, 21, Duration.ofHours(6)));

        StockAnalysis a = svc.analyze(" kaynes.ns ", new Position(29, 4230.52, null));

        assertEquals("KAYNES", a.symbol());
        assertFalse(a.inUniverse());
        assertTrue(a.momentum().shareBeaten() > 0.8, "beats " + a.momentum().shareBeaten());
        assertEquals(1, a.momentum().quintile());
        assertEquals(4, a.outlook().size());
        assertEquals(List.of("3 months", "6 months", "9 months", "1 year"),
                a.outlook().stream().map(HorizonOutlook::label).toList());
        for (HorizonOutlook o : a.outlook()) {
            assertEquals(a.price() * (1 + o.median()), o.typicalPrice(), 1e-6);
            assertTrue(o.lowPrice() <= o.typicalPrice() && o.typicalPrice() <= o.highPrice());
        }
        assertTrue(a.verdict().reasons().size() >= 4);
        assertEquals(29, a.holding().quantity());
        assertEquals("Stock One Ltd.", svc.analyze("S1", null).name());
    }

    // ---------- recovery chance, analysts and the bold call ----------

    @Test
    void shareAtLeastCountsCasesAtOrAboveTheThreshold() {
        Map<String, List<Bar>> bars = new HashMap<>();
        List<Stock> uni = new ArrayList<>();
        for (int k = 0; k < 30; k++) {
            uni.add(new Stock("S" + k, "x"));
            bars.put("S" + k, walk(k, 0.0002 * (k % 7 - 3), 1240));
        }
        ForwardReturnModel m = ForwardReturnModel.build(uni, bars, walk(999, 0.0005, 1240), 21);

        double any = m.shareAtLeast(3, null, 3, -10);      // every case is above -1000%
        double none = m.shareAtLeast(3, null, 3, 10);      // no case is above +1000%
        double half = m.shareAtLeast(3, null, 3, m.get(3, null, 3).median());
        assertEquals(1.0, any, 1e-12);
        assertEquals(0.0, none, 1e-12);
        assertTrue(half >= 0.45 && half <= 0.55, "around half at the median, was " + half);
        assertTrue(m.shareAtLeast(3, null, 3, 0.05) <= m.shareAtLeast(3, null, 3, 0.0));
    }

    static Consensus consensus(double target, int analysts) {
        return new Consensus(target, target * 1.2, target * 0.8, analysts, "buy", 2.0, 40.0, 30.0, 50.0, 60.0,
                0.2, 0.15, 0.1, 0.3);
    }

    @Test
    void analystTargetsCountOnlyWithEnoughAnalystsAndMeaningfulUpside() {
        Momentum mid = new Momentum(0, 0.5, 3, 200);
        Trend flat = new Trend(100, 95, 0.02, true, false, 120, 80, -0.1, 0, 0, 0, 0, 0.3);

        Verdict big = StockAnalysisService.verdict(mid, flat, true, List.of(), List.of(), false, consensus(130, 12), 100);
        Verdict small = StockAnalysisService.verdict(mid, flat, true, List.of(), List.of(), false, consensus(110, 12), 100);
        Verdict below = StockAnalysisService.verdict(mid, flat, true, List.of(), List.of(), false, consensus(90, 12), 100);
        Verdict thin = StockAnalysisService.verdict(mid, flat, true, List.of(), List.of(), false, consensus(150, 2), 100);
        Verdict missing = StockAnalysisService.verdict(mid, flat, true, List.of(), List.of(), false, null, 100);

        assertEquals(1, last(big).points());
        assertTrue(last(big).text().contains("+30.0%") && last(big).text().contains("12 analysts"), last(big).text());
        assertEquals(0, last(small).points());
        assertEquals(-1, last(below).points());
        assertEquals(0, last(thin).points());
        assertTrue(last(thin).text().contains("Too few analysts"));
        assertTrue(last(missing).text().contains("no consensus"));
    }

    static StockAnalysis.Reason last(Verdict v) {
        return v.reasons().get(v.reasons().size() - 1);
    }

    static Verdict pts(int p) {
        return new Verdict(p, "x", "x", List.of());
    }

    static StockAnalysis.Holding loss() {
        return StockAnalysisService.holding(new Position(26, 4230, null), 3292.5, pts(0));
    }

    static StockAnalysis.Holding profit() {
        return StockAnalysisService.holding(new Position(10, 100, null), 130, pts(0));
    }

    @Test
    void callForStocksYouDontOwn() {
        assertEquals("BUY", StockAnalysisService.call(pts(2), null, null, null).call());
        assertEquals("WAIT", StockAnalysisService.call(pts(1), null, null, null).call());
        assertEquals("WAIT", StockAnalysisService.call(pts(-1), null, null, null).call());
        assertEquals("AVOID", StockAnalysisService.call(pts(-2), null, null, null).call());
    }

    @Test
    void callAtALossSellsOnlyWhenSignalsAreAgainstAndRecoveryOddsArePoor() {
        StockAnalysis.Call sell = StockAnalysisService.call(pts(-3), loss(), 0.20, 0.30);
        assertEquals("SELL", sell.call());
        assertTrue(sell.headline().startsWith("30% of similar cases got back above your ₹4,230 within a year"), sell.headline());

        // Same weak signals, but most similar cases recovered: hold.
        StockAnalysis.Call goodOdds = StockAnalysisService.call(pts(-3), loss(), 0.35, 0.55);
        assertEquals("HOLD", goodOdds.call());
        assertTrue(goodOdds.headline().startsWith("55%"));

        // Poor odds but signals only mildly negative: hold, and say so.
        StockAnalysis.Call mild = StockAnalysisService.call(pts(-1), loss(), 0.10, 0.20);
        assertEquals("HOLD", mild.call());
        assertTrue(mild.headline().startsWith("Signals aren't strongly against it (-1 point), though only 20%"), mild.headline());

        // No 1-year data: the 6-month chance is used.
        StockAnalysis.Call sixOnly = StockAnalysisService.call(pts(-2), loss(), 0.25, null);
        assertEquals("SELL", sixOnly.call());
        assertTrue(sixOnly.headline().contains("within 6 months"));
    }

    @Test
    void callInProfitHoldsUnlessSignalsTurn() {
        assertEquals("HOLD", StockAnalysisService.call(pts(-1), profit(), null, null).call());
        StockAnalysis.Call sell = StockAnalysisService.call(pts(-2), profit(), null, null);
        assertEquals("SELL", sell.call());
        assertTrue(sell.headline().contains("booking the gain"));
    }

    @Test
    void parsesYahooQuoteSummary() throws Exception {
        String json = """
            {"quoteSummary":{"result":[{
              "financialData":{"targetMeanPrice":{"raw":4850.5,"fmt":"4,850.50"},"targetHighPrice":{"raw":6100},
                "targetLowPrice":{"raw":3500},"numberOfAnalystOpinions":{"raw":14},"recommendationKey":"buy",
                "recommendationMean":{"raw":1.9},"earningsGrowth":{"raw":0.32},"revenueGrowth":{"raw":0.25},
                "profitMargins":{"raw":0.08},"debtToEquity":{"raw":45.2}},
              "defaultKeyStatistics":{"forwardPE":{"raw":48.1},"trailingEps":{"raw":52.3},"forwardEps":{"raw":68.4}},
              "summaryDetail":{"trailingPE":{"raw":63.0}}}],"error":null}}
            """;

        Consensus c = com.swingpicker.data.YahooFundamentalsTestAccess.parse(json).get();

        assertEquals(4850.5, c.targetMean(), 1e-9);
        assertEquals(14, c.analysts().intValue());
        assertEquals("buy", c.rating());
        assertEquals(63.0, c.trailingPE(), 1e-9);
        assertEquals(48.1, c.forwardPE(), 1e-9);
        assertEquals(0.452, c.debtToEquity(), 1e-9);
        assertTrue(c.hasTarget());
    }
}
