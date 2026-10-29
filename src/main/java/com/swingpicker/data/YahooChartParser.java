package com.swingpicker.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.swingpicker.domain.Bar;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * Turns Yahoo's v8 chart JSON (interval=1d) into clean daily bars, oldest first.
 *
 * Rules, each one a decision worth knowing:
 * 1. Dates are IST trading dates (Yahoo stamps each daily bar with the session's open as epoch seconds).
 * 2. Prices are fully adjusted: OHLC scaled by adjclose/close, so splits AND dividends don't show up as
 *    fake drops that would trigger stops in a backtest. Today's bar has factor ~1, so live prices stay real.
 * 3. A bar is dropped if any of O/H/L/C is missing or <= 0, if high < low, or if volume is 0 (holiday rows,
 *    suspended days).
 * 4. Duplicate dates keep the last row (Yahoo sometimes repeats the latest session).
 * 5. Today's bar is dropped until the session is final (after 16:00 IST); setups must only see completed days.
 */
public final class YahooChartParser {

    public static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final LocalTime SESSION_FINAL = LocalTime.of(16, 0);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private YahooChartParser() {
    }

    public static List<Bar> parse(String json, ZonedDateTime now) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (IOException e) {
            throw new MarketDataException("Yahoo returned something that isn't JSON", e);
        }

        JsonNode chart = root.path("chart");
        JsonNode error = chart.path("error");
        if (!error.isMissingNode() && !error.isNull()) {
            throw new MarketDataException("Yahoo error: " + error.path("description").asText(error.toString()));
        }
        JsonNode result = chart.path("result").path(0);
        if (result.isMissingNode()) {
            throw new MarketDataException("Yahoo returned no result");
        }

        JsonNode ts = result.path("timestamp");
        JsonNode quote = result.path("indicators").path("quote").path(0);
        JsonNode adj = result.path("indicators").path("adjclose").path(0).path("adjclose");

        LocalDate today = now.withZoneSameInstant(IST).toLocalDate();
        boolean todayIsFinal = !now.withZoneSameInstant(IST).toLocalTime().isBefore(SESSION_FINAL);

        TreeMap<LocalDate, Bar> byDate = new TreeMap<>();
        for (int i = 0; i < ts.size(); i++) {
            Double o = num(quote.path("open"), i);
            Double h = num(quote.path("high"), i);
            Double l = num(quote.path("low"), i);
            Double c = num(quote.path("close"), i);
            Double v = num(quote.path("volume"), i);
            if (o == null || h == null || l == null || c == null || v == null) {
                continue;
            }
            if (o <= 0 || h <= 0 || l <= 0 || c <= 0 || h < l || v <= 0) {
                continue;
            }

            LocalDate date = Instant.ofEpochSecond(ts.get(i).asLong()).atZone(IST).toLocalDate();
            if (date.isAfter(today) || (date.equals(today) && !todayIsFinal)) {
                continue;
            }

            Double adjClose = num(adj, i);
            double f = (adjClose != null && adjClose > 0) ? adjClose / c : 1.0;
            byDate.put(date, new Bar(date, o * f, h * f, l * f, c * f, v.longValue()));
        }
        return new ArrayList<>(byDate.values());
    }

    private static Double num(JsonNode array, int i) {
        JsonNode n = array.path(i);
        return (n.isMissingNode() || n.isNull() || !n.isNumber()) ? null : n.asDouble();
    }
}
