package com.swingpicker.store;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/** Used when no database is configured: works, but everything is lost on restart. Also used by tests. */
public class MemoryStore implements Store {

    private final Clock clock;
    private final AtomicLong ids = new AtomicLong();
    private final Map<Long, HoldingRow> holdings = new LinkedHashMap<>();
    private final Map<String, WatchRow> watch = new LinkedHashMap<>();
    private final Map<Long, CallRow> calls = new LinkedHashMap<>();

    public MemoryStore(Clock clock) {
        this.clock = clock;
    }

    @Override
    public boolean persistent() {
        return false;
    }

    @Override
    public synchronized List<HoldingRow> holdings() {
        return new ArrayList<>(holdings.values());
    }

    @Override
    public synchronized HoldingRow addHolding(String symbol, int quantity, double averagePrice, LocalDate buyDate) {
        HoldingRow h = new HoldingRow(ids.incrementAndGet(), symbol, quantity, averagePrice, buyDate, clock.instant());
        holdings.put(h.id(), h);
        return h;
    }

    @Override
    public synchronized Optional<HoldingRow> updateHolding(long id, int quantity, double averagePrice, LocalDate buyDate) {
        HoldingRow old = holdings.get(id);
        if (old == null) {
            return Optional.empty();
        }
        HoldingRow h = new HoldingRow(id, old.symbol(), quantity, averagePrice, buyDate, old.addedAt());
        holdings.put(id, h);
        return Optional.of(h);
    }

    @Override
    public synchronized boolean deleteHolding(long id) {
        return holdings.remove(id) != null;
    }

    @Override
    public synchronized List<WatchRow> watchlist() {
        return new ArrayList<>(watch.values());
    }

    @Override
    public synchronized void watch(String symbol) {
        watch.putIfAbsent(symbol, new WatchRow(symbol, clock.instant()));
    }

    @Override
    public synchronized boolean unwatch(String symbol) {
        return watch.remove(symbol) != null;
    }

    @Override
    public synchronized boolean logCall(CallRow c) {
        boolean exists = calls.values().stream().anyMatch(x -> x.kind().equals(c.kind()) && x.symbol().equals(c.symbol())
                && x.signalDate().equals(c.signalDate()));
        if (exists) {
            return false;
        }
        long id = ids.incrementAndGet();
        calls.put(id, new CallRow(id, clock.instant(), c.kind(), c.symbol(), c.setup(), c.signalDate(), c.refPrice(),
                c.stop(), c.target(), c.maxEntry(), c.holdDays(), c.recommended(), c.status(), c.entryDate(),
                c.entryPrice(), c.exitDate(), c.exitPrice(), c.returnPct(), c.rMultiple(), c.benchmark()));
        return true;
    }

    @Override
    public synchronized List<CallRow> calls() {
        List<CallRow> out = new ArrayList<>(calls.values());
        out.sort(Comparator.comparing(CallRow::signalDate).reversed().thenComparing(CallRow::symbol));
        return out;
    }

    @Override
    public synchronized void saveGrade(CallRow c) {
        if (calls.containsKey(c.id())) {
            calls.put(c.id(), c);
        }
    }
}
