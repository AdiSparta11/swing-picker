package com.swingpicker.core.portfolio;

import com.swingpicker.core.analysis.StockAnalysis;
import com.swingpicker.core.analysis.StockAnalysis.HorizonOutlook;
import com.swingpicker.core.analysis.StockAnalysisService;
import com.swingpicker.core.analysis.StockAnalysisService.Position;
import com.swingpicker.store.Store;
import com.swingpicker.store.Store.HoldingRow;
import com.swingpicker.store.Store.WatchRow;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/** Your holdings and watchlist, each with the same call the Check-a-stock screen gives. */
public class PortfolioService {

    /** One row on the Portfolio or Watchlist screen. Holding fields are null on the watchlist. */
    public record StockCard(String symbol, String name, Double price, boolean livePrice, String call, String headline,
                            Integer points, String label, Long holdingId, Integer quantity, Double averagePrice,
                            LocalDate buyDate, Double invested, Double value, Double pnl, Double pnlPct,
                            Double chance6m, Double chance1y, Double typical1y, Double typical1yPct,
                            Double analystTarget, Integer analysts, String error) {
    }

    public record Totals(double invested, double value, double pnl, double pnlPct, int hold, int sell, int priced) {
    }

    public record Portfolio(boolean saved, List<StockCard> items, Totals totals) {
    }

    public record Watchlist(boolean saved, List<StockCard> items) {
    }

    private final Store store;
    private final StockAnalysisService analysis;

    public PortfolioService(Store store, StockAnalysisService analysis) {
        this.store = store;
        this.analysis = analysis;
    }

    public Portfolio portfolio() {
        List<HoldingRow> rows = store.holdings();
        List<StockCard> items = parallel(rows, h -> () -> card(h));
        double invested = 0;
        double value = 0;
        int hold = 0;
        int sell = 0;
        int priced = 0;
        for (StockCard c : items) {
            invested += c.invested();
            if (c.value() != null) {
                value += c.value();
                priced++;
            } else {
                value += c.invested();   // unpriced rows count at cost so totals stay meaningful
            }
            if ("HOLD".equals(c.call())) {
                hold++;
            } else if ("SELL".equals(c.call())) {
                sell++;
            }
        }
        double pnl = value - invested;
        return new Portfolio(store.persistent(), items,
                new Totals(invested, value, pnl, invested == 0 ? 0 : pnl / invested, hold, sell, priced));
    }

    public Watchlist watchlist() {
        List<WatchRow> rows = store.watchlist();
        return new Watchlist(store.persistent(), parallel(rows, w -> () -> card(w.symbol())));
    }

    /** Adds a holding after checking the symbol exists on NSE; returns its card. */
    public StockCard add(String rawSymbol, int quantity, double averagePrice, LocalDate buyDate) {
        validate(quantity, averagePrice, buyDate);
        String symbol = symbolOf(rawSymbol);
        analysis.analyze(symbol, null);   // throws a readable error for an unknown symbol
        return card(store.addHolding(symbol, quantity, averagePrice, buyDate));
    }

    public StockCard update(long id, int quantity, double averagePrice, LocalDate buyDate) {
        validate(quantity, averagePrice, buyDate);
        return card(store.updateHolding(id, quantity, averagePrice, buyDate)
                .orElseThrow(() -> new IllegalArgumentException("That holding no longer exists")));
    }

    public boolean remove(long id) {
        return store.deleteHolding(id);
    }

    public StockCard watch(String rawSymbol) {
        String symbol = symbolOf(rawSymbol);
        StockCard c = card(symbol);
        if (c.error() != null) {
            throw new IllegalArgumentException(c.error());
        }
        store.watch(symbol);
        return c;
    }

    public boolean unwatch(String rawSymbol) {
        return store.unwatch(symbolOf(rawSymbol));
    }

    /** "KAYNES — Kaynes Technology India Limited", "kaynes.ns", " Kaynes " all mean KAYNES. */
    public static String symbolOf(String raw) {
        String s = raw == null ? "" : raw.split(" — ")[0].trim().toUpperCase();
        if (s.endsWith(".NS")) {
            s = s.substring(0, s.length() - 3);
        }
        if (s.isEmpty() || !s.matches("[A-Z0-9&\\-]+")) {
            throw new IllegalArgumentException("Enter an NSE symbol, e.g. RELIANCE");
        }
        return s;
    }

    private static void validate(int quantity, double averagePrice, LocalDate buyDate) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be at least 1");
        }
        if (!(averagePrice > 0)) {
            throw new IllegalArgumentException("Average price must be above 0");
        }
        if (buyDate != null && buyDate.isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("Buy date is in the future");
        }
    }

    // ---------- cards ----------

    StockCard card(HoldingRow h) {
        double invested = h.quantity() * h.averagePrice();
        try {
            StockAnalysis a = analysis.analyze(h.symbol(), new Position(h.quantity(), h.averagePrice(), h.buyDate()));
            StockAnalysis.Holding x = a.holding();
            return new StockCard(a.symbol(), a.name(), a.price(), a.livePrice(), a.call().call(), a.call().headline(),
                    a.verdict().points(), a.verdict().label(), h.id(), h.quantity(), h.averagePrice(), h.buyDate(),
                    invested, x.value(), x.pnl(), x.pnlPct(), chance(a, 126), chance(a, 252), typical(a), typicalPct(a),
                    target(a), analysts(a), null);
        } catch (RuntimeException e) {
            return new StockCard(h.symbol(), h.symbol(), null, false, null, null, null, null, h.id(), h.quantity(),
                    h.averagePrice(), h.buyDate(), invested, null, null, null, null, null, null, null, null, null,
                    message(e));
        }
    }

    StockCard card(String symbol) {
        try {
            StockAnalysis a = analysis.analyze(symbol, null);
            return new StockCard(a.symbol(), a.name(), a.price(), a.livePrice(), a.call().call(), a.call().headline(),
                    a.verdict().points(), a.verdict().label(), null, null, null, null, null, null, null, null, null,
                    null, typical(a), typicalPct(a), target(a), analysts(a), null);
        } catch (RuntimeException e) {
            return new StockCard(symbol, symbol, null, false, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, message(e));
        }
    }

    private static String message(RuntimeException e) {
        if (e instanceof UnsupportedOperationException) {
            throw e;
        }
        return e.getMessage() == null ? "Couldn't analyse this stock right now" : e.getMessage();
    }

    private static Double chance(StockAnalysis a, int sessions) {
        return a.outlook().stream().filter(o -> o.sessions() == sessions).map(HorizonOutlook::chanceAboveCost)
                .filter(c -> c != null && !c.isNaN()).findFirst().orElse(null);
    }

    private static HorizonOutlook year(StockAnalysis a) {
        return a.outlook().stream().filter(o -> o.sessions() == 252).findFirst().orElse(null);
    }

    private static Double typical(StockAnalysis a) {
        HorizonOutlook y = year(a);
        return y == null ? null : y.typicalPrice();
    }

    private static Double typicalPct(StockAnalysis a) {
        HorizonOutlook y = year(a);
        return y == null ? null : y.median();
    }

    private static Double target(StockAnalysis a) {
        return a.consensus() != null && a.consensus().hasTarget() ? a.consensus().targetMean() : null;
    }

    private static Integer analysts(StockAnalysis a) {
        return a.consensus() != null && a.consensus().hasTarget() ? a.consensus().analysts() : null;
    }

    private static <T> List<StockCard> parallel(List<T> rows, java.util.function.Function<T, Supplier<StockCard>> f) {
        if (rows.isEmpty()) {
            return List.of();
        }
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(4, rows.size()));
        try {
            Map<Integer, CompletableFuture<StockCard>> futures = new LinkedHashMap<>();
            for (int i = 0; i < rows.size(); i++) {
                futures.put(i, CompletableFuture.supplyAsync(f.apply(rows.get(i)), pool));
            }
            List<StockCard> out = new ArrayList<>();
            for (CompletableFuture<StockCard> c : futures.values()) {
                try {
                    out.add(c.join());
                } catch (RuntimeException e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    if (cause instanceof UnsupportedOperationException u) {
                        throw u;
                    }
                    throw e;
                }
            }
            return out;
        } finally {
            pool.shutdown();
        }
    }
}
