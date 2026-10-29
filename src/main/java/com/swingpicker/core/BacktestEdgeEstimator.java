package com.swingpicker.core;

import com.swingpicker.core.RecommendationService.Stock;
import com.swingpicker.domain.Bar;
import com.swingpicker.domain.Edge;
import com.swingpicker.domain.TradePlan;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Replays a setup over every stock's history and measures what actually happened, in R (gross of costs).
 *
 * Simulation rules (chosen to be pessimistic rather than flattering):
 *  - Signal on day i uses only bars up to i. Entry is day i+1's open.
 *  - Skip if that open is above maxEntry (same rule as live) or at/below the stop.
 *  - Each later day: an open beyond the stop or target fills at the open (gaps hurt or help for real).
 *  - If a day's range touches both stop and target, assume the stop came first.
 *  - Still open after maxHoldDays: exit at that day's close.
 *  - Trades without enough future data are dropped. One trade at a time per stock.
 *
 * Per-stock results are short samples, so each stock's stats are blended toward the whole universe's
 * with a prior worth {@code priorTrades} trades: a stock with 5 trades is mostly "universe", one with 60 mostly itself.
 *
 * Known bias: the universe is today's index list, so stocks that crashed out of it aren't in the history.
 * Treat results as an upper bound.
 */
public class BacktestEdgeEstimator implements EdgeEstimator {

    /** One completed historical trade. */
    public record Trade(String symbol, int signalIndex, double r) {
    }

    private record Stats(int n, int wins, double sumWinR, double sumLossR) {
        static Stats of(List<Trade> trades) {
            int wins = 0;
            double sw = 0;
            double sl = 0;
            for (Trade t : trades) {
                if (t.r() > 0) {
                    wins++;
                    sw += t.r();
                } else {
                    sl += -t.r();
                }
            }
            return new Stats(trades.size(), wins, sw, sl);
        }
    }

    private record Memo(Instant at, Stats pool, Map<String, Stats> byStock) {
    }

    private final List<Stock> universe;
    private final MarketDataProvider data;
    private final int historyYears;
    private final double priorTrades;
    private final Duration ttl;
    private final Clock clock;
    private final Map<String, Memo> memo = new HashMap<>();

    public BacktestEdgeEstimator(List<Stock> universe, MarketDataProvider data, int historyYears,
                                 double priorTrades, Duration ttl, Clock clock) {
        this.universe = List.copyOf(universe);
        this.data = data;
        this.historyYears = historyYears;
        this.priorTrades = priorTrades;
        this.ttl = ttl;
        this.clock = clock;
    }

    @Override
    public synchronized Edge pool(Setup setup) {
        Stats pool = memoFor(setup).pool();
        if (pool.n() == 0) {
            return new Edge(0, 0, 0, 0, 0);
        }
        int losses = pool.n() - pool.wins();
        return new Edge((double) pool.wins() / pool.n(),
                pool.wins() == 0 ? 0 : pool.sumWinR() / pool.wins(),
                losses == 0 ? 0 : pool.sumLossR() / losses,
                pool.n(), pool.n());
    }

    private Memo memoFor(Setup setup) {
        Memo m = memo.get(setup.name());
        if (m == null || m.at().plus(ttl).isBefore(clock.instant())) {
            m = build(setup);
            memo.put(setup.name(), m);
        }
        return m;
    }

    @Override
    public synchronized Edge estimate(Setup setup, String symbol) {
        Memo m = memoFor(setup);
        Stats pool = m.pool();
        if (pool.n() == 0) {
            return new Edge(0, 0, 0, 0, 0);
        }
        Stats s = m.byStock().getOrDefault(symbol, new Stats(0, 0, 0, 0));

        double poolP = (double) pool.wins() / pool.n();
        double poolWin = pool.wins() == 0 ? 0 : pool.sumWinR() / pool.wins();
        int poolLosses = pool.n() - pool.wins();
        double poolLoss = poolLosses == 0 ? 0 : pool.sumLossR() / poolLosses;

        // Blend: the universe acts like `priorTrades` extra trades on this stock.
        double p = (s.wins() + priorTrades * poolP) / (s.n() + priorTrades);
        double winPrior = priorTrades * poolP;
        double lossPrior = priorTrades * (1 - poolP);
        double avgWin = (s.sumWinR() + winPrior * poolWin) / (s.wins() + winPrior);
        double avgLoss = (s.sumLossR() + lossPrior * poolLoss) / ((s.n() - s.wins()) + lossPrior);
        return new Edge(p, avgWin, avgLoss, s.n(), pool.n());
    }

    private Memo build(Setup setup) {
        List<Trade> all = new ArrayList<>();
        Map<String, Stats> byStock = new HashMap<>();
        for (Stock stock : universe) {
            List<Bar> bars;
            try {
                bars = data.dailyBars(stock.symbol(), historyYears);
            } catch (UnsupportedOperationException e) {
                throw e;
            } catch (RuntimeException e) {
                continue; // a stock we can't load just doesn't contribute history
            }
            List<Trade> trades = replay(setup, stock.symbol(), bars);
            all.addAll(trades);
            byStock.put(stock.symbol(), Stats.of(trades));
        }
        return new Memo(clock.instant(), Stats.of(all), byStock);
    }

    /** All completed, non-overlapping trades the setup would have taken on this stock. */
    public static List<Trade> replay(Setup setup, String symbol, List<Bar> bars) {
        List<Trade> trades = new ArrayList<>();
        int n = bars.size();
        int i = 0;
        while (i < n - 1) {
            Optional<TradePlan> signal = setup.detect(symbol, bars.subList(0, i + 1));
            if (signal.isEmpty()) {
                i++;
                continue;
            }
            TradePlan plan = signal.get();
            int entryDay = i + 1;
            double entry = bars.get(entryDay).open();
            if (entry > plan.maxEntry() || entry <= plan.stop()) {
                i++;
                continue;
            }
            double risk = entry - plan.stop();
            int lastDay = entryDay + plan.maxHoldDays() - 1;
            if (lastDay >= n) {
                break; // not enough future to know how it ended
            }

            double exit = Double.NaN;
            int exitDay = lastDay;
            for (int d = entryDay; d <= lastDay; d++) {
                Bar b = bars.get(d);
                if (d > entryDay && b.open() <= plan.stop()) {
                    exit = b.open();
                } else if (d > entryDay && b.open() >= plan.target()) {
                    exit = b.open();
                } else if (b.low() <= plan.stop()) {
                    exit = plan.stop();
                } else if (b.high() >= plan.target()) {
                    exit = plan.target();
                } else if (d == lastDay) {
                    exit = b.close();
                }
                if (!Double.isNaN(exit)) {
                    exitDay = d;
                    break;
                }
            }
            trades.add(new Trade(symbol, i, (exit - entry) / risk));
            i = exitDay + 1;
        }
        return trades;
    }
}
