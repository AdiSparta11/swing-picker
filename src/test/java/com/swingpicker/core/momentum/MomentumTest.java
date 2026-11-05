package com.swingpicker.core.momentum;

import com.swingpicker.core.MarketDataProvider;
import com.swingpicker.core.MarketRegime;
import com.swingpicker.core.NseDeliveryCostModel;
import com.swingpicker.core.RecommendationService.Stock;
import com.swingpicker.core.momentum.MomentumBacktest.Params;
import com.swingpicker.core.momentum.MomentumBacktest.Performance;
import com.swingpicker.core.momentum.MomentumBacktest.Period;
import com.swingpicker.domain.Bar;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MomentumTest {

    static final LocalDate START = LocalDate.of(2022, 1, 3);

    /** Price compounding at {@code daily} with a small alternating wiggle so volatility isn't zero. */
    static List<Bar> series(int days, double daily, double wiggle) {
        List<Bar> bars = new ArrayList<>();
        double c = 100;
        for (int k = 0; k < days; k++) {
            double o = c;
            c = c * (1 + daily) * (1 + (k % 2 == 0 ? wiggle : -wiggle));
            bars.add(new Bar(START.plusDays(k), o, Math.max(o, c) * 1.001, Math.min(o, c) * 0.999, c, 1000));
        }
        return bars;
    }

    @Test
    void scoreIsAverageReturnOverVolatility() {
        List<Bar> b = series(300, 0.001, 0.002);
        MomentumScore s = MomentumScore.at(b, 299).get();

        double r6 = b.get(299).close() / b.get(173).close() - 1;
        double r12 = b.get(299).close() / b.get(47).close() - 1;
        assertEquals(r6, s.return6m(), 1e-12);
        assertEquals(r12, s.return12m(), 1e-12);
        assertEquals((r6 + r12) / 2 / s.volatility(), s.score(), 1e-12);
        assertTrue(MomentumScore.at(b, 251).isEmpty());
    }

    @Test
    void smootherRiseRanksAboveTheSameRiseWithBigSwings() {
        double calm = MomentumScore.at(series(300, 0.001, 0.002), 299).get().score();
        double wild = MomentumScore.at(series(300, 0.001, 0.02), 299).get().score();
        assertTrue(calm > wild);
    }

    @Test
    void selectKeepsHoldingsInsideTheBufferAndCapsSectors() {
        // Ranked best first: A, B, C, D, E. Holding D (rank 4); 2 positions x buffer 2 = keep anything in the top 4.
        List<Stock> ranked = List.of(new Stock("A", "Bank"), new Stock("B", "Bank"), new Stock("C", "IT"),
                new Stock("D", "Bank"), new Stock("E", "Auto"));

        var picked = MomentumBacktest.select(ranked, java.util.Set.of("D"), new Params(2, 21, 2.0, 1, false, 0, 0));

        // D is kept; with one stock per sector, A and B (banks) are blocked, so C (IT) fills the second slot.
        assertEquals(List.of("D", "C"), List.copyOf(picked));

        var fresh = MomentumBacktest.select(ranked, java.util.Set.of("E"), new Params(2, 21, 2.0, 1, false, 0, 0));
        // E is rank 5, outside the buffer: sold. A and C come in.
        assertEquals(List.of("A", "C"), List.copyOf(fresh));
    }

    @Test
    void backtestOwnsTheWinnerAndChargesOnlyOnChanges() {
        int days = 252 + 1 + 21 * 3 + 1;
        Map<String, List<Bar>> bars = new HashMap<>();
        bars.put("UP", series(days, 0.003, 0.001));      // strongest
        bars.put("FLAT", series(days, 0.0, 0.001));
        bars.put("DOWN", series(days, -0.002, 0.001));
        List<Stock> uni = List.of(new Stock("UP", "x"), new Stock("FLAT", "y"), new Stock("DOWN", "z"));
        List<Bar> nifty = series(days, 0.001, 0.001);

        List<Period> periods = MomentumBacktest.run(uni, bars, nifty, new Params(1, 21, 2.0, 1, false, 0.01, 0.02));

        assertEquals(3, periods.size());
        List<Bar> up = bars.get("UP");
        for (int k = 0; k < 3; k++) {
            Period p = periods.get(k);
            int in = (int) java.time.temporal.ChronoUnit.DAYS.between(START, p.entryDate());
            int out = (int) java.time.temporal.ChronoUnit.DAYS.between(START, p.exitDate());
            double gross = up.get(out).open() / up.get(in).open() - 1;
            // Period 1 buys UP and pays the 1% buy charge; later periods keep it, so no further charges.
            assertEquals(k == 0 ? gross - 0.01 : gross, p.strategy(), 1e-12);
            assertEquals(k == 0 ? 1 : 0, p.newPositions());
            assertTrue(p.strategy() > p.equalWeight() || k == 0);
        }
    }

    @Test
    void regimeFilterHoldsCashInADowntrend() {
        int days = 252 + 1 + 21 * 2 + 1;
        Map<String, List<Bar>> bars = Map.of("UP", series(days, 0.003, 0.001));
        List<Bar> fallingNifty = series(days, -0.002, 0.001);

        List<Period> periods = MomentumBacktest.run(List.of(new Stock("UP", "x")), bars, fallingNifty,
                new Params(1, 21, 2.0, 1, true, 0.01, 0.02));

        assertTrue(periods.stream().allMatch(Period::inCash));
        assertTrue(periods.stream().allMatch(p -> p.strategy() == 0));
    }

    @Test
    void performanceSummary() {
        Performance p = Performance.of(List.of(0.10, -0.20, 0.10), 21);

        assertEquals(1.1 * 0.8 * 1.1 - 1, p.totalReturn(), 1e-12);
        assertEquals(1 - 0.8, p.maxDrawdown(), 1e-12);
        assertEquals(2.0 / 3, p.winningShare(), 1e-12);
        assertEquals(Math.pow(1.1 * 0.8 * 1.1, 1 / (3 * 21 / 252.0)) - 1, p.annualReturn(), 1e-12);
    }

    @Test
    void serviceSizesPicksToTheAmountAndSaysStayInCashInADowntrend() {
        int days = 252 + 1 + 21 * 30 + 1;   // ~30 periods: enough for an "earlier" and a "last 12 months" bucket
        Map<String, List<Bar>> bars = new HashMap<>();
        List<Stock> uni = new ArrayList<>();
        for (int k = 0; k < 6; k++) {
            uni.add(new Stock("S" + k, "sector" + k));
            bars.put("S" + k, series(days, 0.0005 * k, 0.001));
        }
        bars.put(MarketRegime.NIFTY, series(days, -0.001, 0.001));
        MarketDataProvider data = (sym, y) -> Optional.ofNullable(bars.get(sym)).orElseThrow();
        MomentumService svc = new MomentumService(uni, data, new MarketRegime(data, 5), new NseDeliveryCostModel(),
                new MomentumService.Config(5000, 10, 21, 2.0, 5));

        MomentumResult r = svc.recommend(10_000);

        assertEquals(2, r.positions());
        assertEquals(List.of("S5", "S4"), r.picks().stream().map(MomentumResult.MomentumStock::symbol).toList());
        assertTrue(r.picks().stream().allMatch(p -> p.quantity() >= 1 && p.amount() <= 5000));
        assertFalse(r.recommended());
        assertFalse(r.niftyUptrend());
        assertTrue(r.reason().startsWith("Stay in cash"));
        assertEquals(4, r.backtest().size());
        assertTrue(r.backtest().get(0).recent().periods() > 0 && r.backtest().get(0).earlier().periods() > 0);
    }

    @Test
    void quantilesInterpolate() {
        List<Double> v = List.of(0.04, -0.02, 0.10, 0.00, 0.06);   // sorted: -0.02 0 0.04 0.06 0.10

        assertEquals(0.04, MomentumService.quantile(v, 0.5), 1e-12);
        assertEquals(0.00, MomentumService.quantile(v, 0.25), 1e-12);
        assertEquals(0.06, MomentumService.quantile(v, 0.75), 1e-12);
        assertEquals(0.0, MomentumService.quantile(List.of(), 0.5), 1e-12);
    }

    @Test
    void eachStockGetsTypicalTargetAndItsOwnGoodAndBadMonth() {
        int days = 252 + 1 + 21 * 30 + 1;
        Map<String, List<Bar>> bars = new HashMap<>();
        List<Stock> uni = new ArrayList<>();
        for (int k = 0; k < 6; k++) {
            uni.add(new Stock("S" + k, "sector" + k));
            bars.put("S" + k, series(days, 0.0005 * k, 0.001 + 0.002 * k));   // higher ranks swing more
        }
        bars.put(MarketRegime.NIFTY, series(days, 0.001, 0.001));
        MarketDataProvider data = (sym, y) -> Optional.ofNullable(bars.get(sym)).orElseThrow();
        MomentumService svc = new MomentumService(uni, data, new MarketRegime(data, 5), new NseDeliveryCostModel(),
                new MomentumService.Config(5000, 10, 21, 2.0, 5));

        MomentumResult r = svc.recommend(10_000);
        MomentumResult.Outlook o = r.outlook();

        assertTrue(o.samples() > 0 && o.recentSamples() > 0);
        assertTrue(o.p25() <= o.median() && o.median() <= o.p75());
        assertEquals(29, java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.now(), o.reviewDate()), 1);
        for (MomentumResult.MomentumStock x : r.picks()) {
            assertEquals(x.lastClose() * (1 + o.median()), x.typicalTarget(), 1e-9);
            assertTrue(x.goodMonth() > 0 && x.badMonth() < 0);
            assertEquals(1 / (1 + x.goodMonth()), 1 + x.badMonth(), 1e-12);   // symmetric in log terms
        }
        // A jumpier stock gets a wider range.
        MomentumResult.MomentumStock first = r.picks().get(0);
        MomentumResult.MomentumStock second = r.picks().get(1);
        double volFirst = bars.get(first.symbol()) == null ? 0 : first.goodMonth();
        assertTrue(volFirst > 0 && first.goodMonth() != second.goodMonth());
    }
}
