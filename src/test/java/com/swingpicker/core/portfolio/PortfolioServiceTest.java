package com.swingpicker.core.portfolio;

import com.swingpicker.core.BacktestEdgeEstimator;
import com.swingpicker.core.LiveSession;
import com.swingpicker.core.MarketDataProvider;
import com.swingpicker.core.MarketRegime;
import com.swingpicker.core.RecommendationService.Stock;
import com.swingpicker.core.TrendPullbackSetup;
import com.swingpicker.core.analysis.StockAnalysisService;
import com.swingpicker.data.MarketDataException;
import com.swingpicker.domain.Bar;
import com.swingpicker.store.MemoryStore;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortfolioServiceTest {

    static List<Bar> walk(long seed, double drift) {
        Random r = new Random(seed);
        List<Bar> b = new ArrayList<>();
        double c = 100 + r.nextInt(400);
        LocalDate d = LocalDate.of(2021, 11, 1);
        while (b.size() < 1240) {
            if (d.getDayOfWeek().getValue() < 6) {
                double o = c * (1 + r.nextGaussian() * 0.003);
                double nc = o * (1 + drift + r.nextGaussian() * 0.015);
                b.add(new Bar(d, o, Math.max(o, nc) * 1.004, Math.min(o, nc) * 0.996, nc, 100_000));
                c = nc;
            }
            d = d.plusDays(1);
        }
        return b;
    }

    @Test
    void holdingsAndWatchlistCarryCallsAndTotals() {
        Map<String, List<Bar>> bars = new HashMap<>();
        List<Stock> uni = new ArrayList<>();
        for (int k = 0; k < 30; k++) {
            uni.add(new Stock("S" + k, "x"));
            bars.put("S" + k, walk(k, -0.0005 + 0.00004 * k));
        }
        bars.put("WEAK", walk(77, -0.0015));
        bars.put(MarketRegime.NIFTY, walk(999, 0.0004));
        MarketDataProvider data = (s, y) -> Optional.ofNullable(bars.get(s))
                .orElseThrow(() -> new MarketDataException("Unknown NSE symbol: " + s));
        Clock clock = Clock.fixed(Instant.parse("2026-11-05T12:00:00Z"), ZoneOffset.UTC);
        MarketRegime regime = new MarketRegime(data, 5);
        StockAnalysisService analysis = new StockAnalysisService(uni, Map.of(), data, regime, List.of(new TrendPullbackSetup()),
                new BacktestEdgeEstimator(uni, data, 5, 20, Duration.ofHours(6), clock), LiveSession.closed(), clock,
                new StockAnalysisService.Config(5, 21, Duration.ofHours(6)));
        MemoryStore store = new MemoryStore(clock);
        PortfolioService p = new PortfolioService(store, analysis);

        double price = bars.get("WEAK").get(1239).close();
        PortfolioService.StockCard added = p.add("weak — Weak Ltd", 10, price * 1.3, null);
        assertEquals("WEAK", added.symbol());
        assertNotNull(added.call());
        assertTrue(added.pnl() < 0);
        p.add("S29", 5, bars.get("S29").get(1239).close() * 0.9, LocalDate.of(2026, 1, 2));
        assertThrows(IllegalArgumentException.class, () -> p.add("S1", 0, 10, null));
        assertThrows(MarketDataException.class, () -> p.add("NOPE", 1, 10, null));

        PortfolioService.Portfolio pf = p.portfolio();
        assertEquals(2, pf.items().size());
        assertEquals(pf.items().stream().mapToDouble(PortfolioService.StockCard::invested).sum(), pf.totals().invested(), 1e-6);
        assertEquals(pf.totals().value() - pf.totals().invested(), pf.totals().pnl(), 1e-6);
        assertEquals(2, pf.totals().hold() + pf.totals().sell());

        PortfolioService.StockCard upd = p.update(added.holdingId(), 20, price * 1.3, null);
        assertEquals(20, upd.quantity().intValue());
        assertTrue(p.remove(added.holdingId()));

        p.watch("S5.NS");
        PortfolioService.StockCard w = p.watchlist().items().get(0);
        assertEquals("S5", w.symbol());
        assertNull(w.holdingId());
        assertTrue(List.of("BUY", "WAIT", "AVOID").contains(w.call()));
        assertThrows(IllegalArgumentException.class, () -> p.watch("NOPE"));
        assertTrue(p.unwatch("s5"));
        assertThrows(IllegalArgumentException.class, () -> PortfolioService.symbolOf("  "));
        assertEquals("M&M", PortfolioService.symbolOf("m&m — Mahindra"));
    }
}
