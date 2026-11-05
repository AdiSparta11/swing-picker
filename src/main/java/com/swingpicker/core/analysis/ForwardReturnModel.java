package com.swingpicker.core.analysis;

import com.swingpicker.core.MarketRegime;
import com.swingpicker.core.RecommendationService.Stock;
import com.swingpicker.core.momentum.MomentumScore;
import com.swingpicker.domain.Bar;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * "What did stocks in the same state go on to do?", measured, not predicted.
 *
 * Every {@code step} sessions over the history, all universe stocks are ranked by momentum score and split into
 * quintiles (1 = top 20%). For each stock we record its return over the next 3, 6, 9 and 12 months (63/126/189/252
 * sessions, close to close, before charges). Results are grouped by quintile and by whether the Nifty was in an
 * uptrend on that day, plus an "all stocks" baseline (quintile 0) so a bucket can be compared with doing nothing special.
 *
 * Bias: the universe is today's index list, so all numbers lean optimistic, baseline included.
 */
public final class ForwardReturnModel {

    public static final int[] HORIZONS = {63, 126, 189, 252};
    public static final String[] LABELS = {"3 months", "6 months", "9 months", "1 year"};
    static final int MIN_BUCKET = 40;

    /** Distribution of forward returns (fractions). */
    public record Stats(int n, double median, double p25, double p75, double positiveShare) {
        static final Stats EMPTY = new Stats(0, 0, 0, 0, 0);
    }

    private record Key(int quintile, Boolean regimeUp) {
    }

    private final Map<Key, Stats[]> table;
    private final Map<Key, double[][]> sorted;
    private final LocalDate from;
    private final LocalDate to;

    private ForwardReturnModel(Map<Key, Stats[]> table, Map<Key, double[][]> sorted, LocalDate from, LocalDate to) {
        this.table = table;
        this.sorted = sorted;
        this.from = from;
        this.to = to;
    }

    /**
     * Share of cases (same bucket and fallback as {@link #get}) whose forward return was at least {@code threshold}.
     * For "chance it gets back above my buy price", the threshold is the rise needed to break even.
     */
    public double shareAtLeast(int quintile, Boolean regimeUp, int horizon, double threshold) {
        Key k = usesRegime(quintile, regimeUp, horizon) ? new Key(quintile, regimeUp) : new Key(quintile, null);
        double[][] all = sorted.get(k);
        if (all == null || all[horizon].length == 0) {
            return Double.NaN;
        }
        double[] v = all[horizon];
        int lo = 0;
        int hi = v.length;   // first index with v[i] >= threshold
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (v[mid] < threshold) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return (v.length - lo) / (double) v.length;
    }

    public LocalDate from() {
        return from;
    }

    public LocalDate to() {
        return to;
    }

    /**
     * Stats for a quintile (0 = all stocks) at a horizon index, in a given market state.
     * Falls back to both market states pooled when the regime-specific bucket is too thin or the regime is unknown.
     */
    public Stats get(int quintile, Boolean regimeUp, int horizon) {
        if (regimeUp != null) {
            Stats[] s = table.get(new Key(quintile, regimeUp));
            if (s != null && s[horizon].n() >= MIN_BUCKET) {
                return s[horizon];
            }
        }
        Stats[] pooled = table.get(new Key(quintile, null));
        return pooled == null ? Stats.EMPTY : pooled[horizon];
    }

    /** True when the regime-specific bucket was used (enough samples), false when it fell back to pooled. */
    public boolean usesRegime(int quintile, Boolean regimeUp, int horizon) {
        if (regimeUp == null) {
            return false;
        }
        Stats[] s = table.get(new Key(quintile, regimeUp));
        return s != null && s[horizon].n() >= MIN_BUCKET;
    }

    /** 1 = top 20% ... 5 = bottom 20%, from the share of stocks this one beats (0..1). */
    public static int quintileOf(double shareBeaten) {
        int q = 5 - (int) Math.floor(Math.min(0.999999, Math.max(0, shareBeaten)) * 5);
        return Math.max(1, Math.min(5, q));
    }

    public static ForwardReturnModel build(List<Stock> universe, Map<String, List<Bar>> bars, List<Bar> nifty, int step) {
        Map<Key, List<List<Double>>> samples = new HashMap<>();
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
        LocalDate first = null;
        LocalDate last = null;

        for (int t = MomentumScore.LOOKBACK; t < nifty.size(); t += step) {
            LocalDate d = nifty.get(t).date();
            record Scored(List<Bar> bars, int at, double score) {
            }
            List<Scored> scored = new ArrayList<>();
            for (Stock s : universe) {
                Integer si = index.getOrDefault(s.symbol(), Map.of()).get(d);
                if (si == null) {
                    continue;
                }
                List<Bar> b = bars.get(s.symbol());
                MomentumScore.at(b, si).ifPresent(sc -> scored.add(new Scored(b, si, sc.score())));
            }
            if (scored.size() < 10) {
                continue;
            }
            scored.sort(Comparator.comparingDouble(Scored::score).reversed());
            Boolean up = regime.get(d);
            boolean any = false;
            for (int pos = 0; pos < scored.size(); pos++) {
                Scored x = scored.get(pos);
                double shareBeaten = (scored.size() - 1 - pos) / (double) (scored.size() - 1);
                int q = quintileOf(shareBeaten);
                double base = x.bars().get(x.at()).close();
                for (int h = 0; h < HORIZONS.length; h++) {
                    int end = x.at() + HORIZONS[h];
                    if (end >= x.bars().size()) {
                        continue;
                    }
                    double r = x.bars().get(end).close() / base - 1;
                    add(samples, new Key(q, null), h, r);
                    add(samples, new Key(0, null), h, r);
                    if (up != null) {
                        add(samples, new Key(q, up), h, r);
                        add(samples, new Key(0, up), h, r);
                    }
                    any = true;
                }
            }
            if (any) {
                first = first == null ? d : first;
                last = d;
            }
        }

        Map<Key, Stats[]> table = new HashMap<>();
        Map<Key, double[][]> sorted = new HashMap<>();
        samples.forEach((k, lists) -> {
            Stats[] s = new Stats[HORIZONS.length];
            double[][] arr = new double[HORIZONS.length][];
            for (int h = 0; h < HORIZONS.length; h++) {
                s[h] = stats(lists.get(h));
                arr[h] = lists.get(h).stream().mapToDouble(Double::doubleValue).sorted().toArray();
            }
            table.put(k, s);
            sorted.put(k, arr);
        });
        return new ForwardReturnModel(table, sorted, first, last);
    }

    private static void add(Map<Key, List<List<Double>>> samples, Key k, int h, double r) {
        samples.computeIfAbsent(k, x -> {
            List<List<Double>> l = new ArrayList<>();
            for (int i = 0; i < HORIZONS.length; i++) {
                l.add(new ArrayList<>());
            }
            return l;
        }).get(h).add(r);
    }

    static Stats stats(List<Double> values) {
        if (values.isEmpty()) {
            return Stats.EMPTY;
        }
        List<Double> v = new ArrayList<>(values);
        v.sort(null);
        long up = v.stream().filter(r -> r > 0).count();
        return new Stats(v.size(), q(v, 0.5), q(v, 0.25), q(v, 0.75), up / (double) v.size());
    }

    private static double q(List<Double> sorted, double p) {
        double pos = p * (sorted.size() - 1);
        int lo = (int) Math.floor(pos);
        int hi = (int) Math.ceil(pos);
        return sorted.get(lo) + (sorted.get(hi) - sorted.get(lo)) * (pos - lo);
    }
}
