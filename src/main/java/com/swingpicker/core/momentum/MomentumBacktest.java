package com.swingpicker.core.momentum;

import com.swingpicker.core.MarketRegime;
import com.swingpicker.core.RecommendationService.Stock;
import com.swingpicker.domain.Bar;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Month-by-month replay of the momentum portfolio, next to two benchmarks on the same dates.
 *
 * Rules (identical to the live picks):
 *  - Rebalance every {@code holdDays} sessions on the Nifty calendar. Rank with data up to the rebalance day,
 *    buy at the next session's open, equal weight.
 *  - Keep a holding while it still ranks in the top {@code positions x buffer}; fill the rest from the top,
 *    at most {@code maxPerSector} per sector.
 *  - With the regime filter on: when the Nifty isn't in an uptrend on the rebalance day, hold cash for that period.
 *  - Charges: buy-side fraction on every new position, sell-side fraction (incl. the per-stock DP charge spread
 *    over the position size) on every exit.
 *
 * Benchmarks: "all stocks equal-weight" buys every stock with data, same dates, no charges (same survivorship
 * bias as the strategy, so it's the fair comparison); "Nifty 50" is the index itself (price only, no dividends).
 */
public final class MomentumBacktest {

    public record Params(int positions, int holdDays, double buffer, int maxPerSector, boolean regimeFilter,
                         double buyCost, double sellCost) {
    }

    /**
     * One holding period. Returns are fractions (0.03 = +3%), strategy is after charges.
     * {@code holdingReturns} are the individual stocks' returns over the period, before charges:
     * the raw material for "what does a momentum pick typically do in the month after it's picked".
     */
    public record Period(LocalDate entryDate, LocalDate exitDate, double strategy, double equalWeight,
                         double nifty, boolean inCash, int holdings, int newPositions, List<Double> holdingReturns) {
    }

    private MomentumBacktest() {
    }

    public static List<Period> run(List<Stock> universe, Map<String, List<Bar>> bars, List<Bar> nifty, Params p) {
        List<Period> out = new ArrayList<>();
        if (nifty.size() < MomentumScore.LOOKBACK + p.holdDays() + 2) {
            return out;
        }
        Map<String, Map<LocalDate, Integer>> index = new HashMap<>();
        for (Stock s : universe) {
            List<Bar> b = bars.get(s.symbol());
            if (b == null) {
                continue;
            }
            Map<LocalDate, Integer> m = new HashMap<>();
            for (int k = 0; k < b.size(); k++) {
                m.put(b.get(k).date(), k);
            }
            index.put(s.symbol(), m);
        }
        Map<LocalDate, Boolean> regime = MarketRegime.compute(nifty);

        Set<String> held = new LinkedHashSet<>();
        for (int t = MomentumScore.LOOKBACK; t + 1 + p.holdDays() < nifty.size(); t += p.holdDays()) {
            LocalDate signal = nifty.get(t).date();
            LocalDate entry = nifty.get(t + 1).date();
            LocalDate exit = nifty.get(t + 1 + p.holdDays()).date();

            // Every stock that can be scored today and bought tomorrow, with its return over the period.
            List<Ranked> ranked = new ArrayList<>();
            double ewSum = 0;
            int ewCount = 0;
            Map<String, Double> periodReturn = new HashMap<>();
            for (Stock s : universe) {
                Map<LocalDate, Integer> m = index.get(s.symbol());
                if (m == null || !m.containsKey(entry)) {
                    continue;
                }
                List<Bar> b = bars.get(s.symbol());
                Optional<Double> r = periodReturn(b, m.get(entry), exit);
                if (r.isEmpty()) {
                    continue;
                }
                periodReturn.put(s.symbol(), r.get());
                ewSum += r.get();
                ewCount++;
                Integer si = m.get(signal);
                if (si != null) {
                    MomentumScore.at(b, si).ifPresent(sc -> ranked.add(new Ranked(s, sc.score())));
                }
            }
            ranked.sort(Comparator.comparingDouble(Ranked::score).reversed());

            boolean cash = p.regimeFilter() && !regime.getOrDefault(signal, false);
            Set<String> target = cash ? Set.of() : select(ranked.stream().map(Ranked::stock).toList(), held, p);

            int entries = 0;
            int exits = 0;
            for (String sym : target) {
                if (!held.contains(sym)) {
                    entries++;
                }
            }
            for (String sym : held) {
                if (!target.contains(sym)) {
                    exits++;
                }
            }
            double gross = target.isEmpty() ? 0
                    : target.stream().mapToDouble(sym -> periodReturn.getOrDefault(sym, 0.0)).average().orElse(0);
            int slots = Math.max(1, Math.max(target.size(), held.size()));
            double charges = (entries * p.buyCost() + exits * p.sellCost()) / slots;

            double niftyReturn = nifty.get(t + 1 + p.holdDays()).open() / nifty.get(t + 1).open() - 1;
            List<Double> each = target.stream().map(sym -> periodReturn.getOrDefault(sym, 0.0)).toList();
            out.add(new Period(entry, exit, gross - charges, ewCount == 0 ? 0 : ewSum / ewCount, niftyReturn,
                    cash, target.size(), entries, each));
            held = new LinkedHashSet<>(target);
        }
        return out;
    }

    private record Ranked(Stock stock, double score) {
    }

    /** Keep holdings still inside the buffer, then fill from the top, respecting the sector cap. */
    static Set<String> select(List<Stock> rankedBestFirst, Set<String> held, Params p) {
        int keepWithin = (int) Math.round(p.positions() * p.buffer());
        Set<String> target = new LinkedHashSet<>();
        Map<String, Integer> perSector = new HashMap<>();
        for (int k = 0; k < rankedBestFirst.size() && k < keepWithin; k++) {
            Stock s = rankedBestFirst.get(k);
            if (held.contains(s.symbol()) && target.size() < p.positions()
                    && perSector.getOrDefault(s.sector(), 0) < p.maxPerSector()) {
                target.add(s.symbol());
                perSector.merge(s.sector(), 1, Integer::sum);
            }
        }
        for (Stock s : rankedBestFirst) {
            if (target.size() >= p.positions()) {
                break;
            }
            if (target.contains(s.symbol()) || perSector.getOrDefault(s.sector(), 0) >= p.maxPerSector()) {
                continue;
            }
            target.add(s.symbol());
            perSector.merge(s.sector(), 1, Integer::sum);
        }
        return target;
    }

    /** Open on the entry bar to the open on the exit date (or the last close before it if the stock has no bar then). */
    private static Optional<Double> periodReturn(List<Bar> b, int entryIdx, LocalDate exit) {
        double buy = b.get(entryIdx).open();
        for (int k = entryIdx + 1; k < b.size(); k++) {
            LocalDate d = b.get(k).date();
            if (d.equals(exit)) {
                return Optional.of(b.get(k).open() / buy - 1);
            }
            if (d.isAfter(exit)) {
                return Optional.of(b.get(k - 1).close() / buy - 1);
            }
        }
        LocalDate last = b.get(b.size() - 1).date();
        return last.isBefore(exit) ? Optional.empty() : Optional.of(b.get(b.size() - 1).close() / buy - 1);
    }

    /** Summary of a series of period returns. */
    public record Performance(int periods, double totalReturn, double annualReturn, double maxDrawdown,
                              double winningShare) {

        public static Performance of(List<Double> returns, int holdDays) {
            if (returns.isEmpty()) {
                return new Performance(0, 0, 0, 0, 0);
            }
            double equity = 1;
            double peak = 1;
            double maxDd = 0;
            int wins = 0;
            for (double r : returns) {
                equity *= 1 + r;
                peak = Math.max(peak, equity);
                maxDd = Math.max(maxDd, 1 - equity / peak);
                if (r > 0) {
                    wins++;
                }
            }
            double years = returns.size() * holdDays / 252.0;
            double annual = Math.pow(equity, 1 / years) - 1;
            return new Performance(returns.size(), equity - 1, annual, maxDd, (double) wins / returns.size());
        }
    }
}
