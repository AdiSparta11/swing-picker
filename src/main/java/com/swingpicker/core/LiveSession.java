package com.swingpicker.core;

import com.swingpicker.domain.Bar;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Knows whether the NSE cash session is open (Mon-Fri, 09:15-15:30 IST) and, while it is, adds today's
 * still-forming bar to a stock's history so the app can answer "what qualifies right now".
 * Exchange holidays aren't listed: on a holiday Yahoo has no bar for today, so nothing is added.
 * Backtests never go through here; they only ever see completed sessions.
 */
public class LiveSession {

    public static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final LocalTime OPEN = LocalTime.of(9, 15);
    static final LocalTime CLOSE = LocalTime.of(15, 30);

    private final MarketDataProvider data;
    private final Clock clock;

    public LiveSession(MarketDataProvider data, Clock clock) {
        this.data = data;
        this.clock = clock;
    }

    /** A session that is never open: everything runs on completed bars only. */
    public static LiveSession closed() {
        return new LiveSession(null, null);
    }

    public ZonedDateTime now() {
        return clock == null ? ZonedDateTime.now(IST) : ZonedDateTime.now(clock).withZoneSameInstant(IST);
    }

    public boolean isOpen() {
        if (data == null) {
            return false;
        }
        ZonedDateTime t = now();
        DayOfWeek day = t.getDayOfWeek();
        LocalTime time = t.toLocalTime();
        return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY && !time.isBefore(OPEN) && time.isBefore(CLOSE);
    }

    /** Today's forming bar, only while the session is open; a failed fetch just means "no live bar". */
    public Optional<Bar> live(String symbol) {
        if (!isOpen()) {
            return Optional.empty();
        }
        try {
            return data.liveBar(symbol).filter(b -> b.date().equals(now().toLocalDate()));
        } catch (RuntimeException e) {
            if (e instanceof UnsupportedOperationException) {
                throw e;
            }
            return Optional.empty();
        }
    }

    /** Completed bars plus today's live bar, if there is one and it's newer than the last completed bar. */
    public static List<Bar> extend(List<Bar> completed, Optional<Bar> live) {
        if (live.isEmpty() || (!completed.isEmpty()
                && !live.get().date().isAfter(completed.get(completed.size() - 1).date()))) {
            return completed;
        }
        List<Bar> out = new ArrayList<>(completed.size() + 1);
        out.addAll(completed);
        out.add(live.get());
        return out;
    }
}
