package com.swingpicker.data;

import com.swingpicker.domain.Bar;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YahooChartParserTest {

    /** Epoch seconds of 09:15 IST on the given day, which is how Yahoo stamps daily NSE bars. */
    private static long open(String isoDate) {
        return LocalDate.parse(isoDate).atTime(9, 15).atZone(YahooChartParser.IST).toEpochSecond();
    }

    /** Rows: a bad high<low bar, a good bar with 0.5 adjustment, a holiday null row, a zero-volume row,
     *  a duplicated session, and today's bar. */
    private static String fixture() {
        return """
            {"chart":{"result":[{
              "timestamp":[%d,%d,%d,%d,%d,%d,%d],
              "indicators":{
                "quote":[{
                  "open":  [100, 100, null, 101, 102, 103, 106],
                  "high":  [ 98, 105, null, 103, 104, 108, 109],
                  "low":   [ 99,  99, null, 100, 101, 102, 105],
                  "close": [ 99, 104, null, 102, 103, 107, 108],
                  "volume":[500,1000, null,   0, 900,1200, 800]
                }],
                "adjclose":[{"adjclose":[99, 52, null, 102, 103, 107, 108]}]
              }
            }],"error":null}}
            """.formatted(
                open("2026-10-22"), open("2026-10-23"), open("2026-10-26"), open("2026-10-27"),
                open("2026-10-28"), open("2026-10-28"), open("2026-10-29"));
    }

    private static ZonedDateTime ist(int y, int m, int d, int hh, int mm) {
        return LocalDateTime.of(y, m, d, hh, mm).atZone(YahooChartParser.IST);
    }

    @Test
    void keepsOnlyValidCompletedSessionsDuringMarketHours() {
        List<Bar> bars = YahooChartParser.parse(fixture(), ist(2026, 10, 29, 11, 0));

        // 22nd (high<low), 26th (null), 27th (zero volume) dropped; 28th deduped; 29th still in progress.
        assertEquals(List.of(LocalDate.parse("2026-10-23"), LocalDate.parse("2026-10-28")),
                bars.stream().map(Bar::date).toList());
    }

    @Test
    void appliesAdjustmentFactorToAllPrices() {
        Bar adjusted = YahooChartParser.parse(fixture(), ist(2026, 10, 29, 11, 0)).get(0);

        // adjclose 52 / close 104 = 0.5 applied to O, H, L, C; volume untouched.
        assertEquals(50.0, adjusted.open(), 1e-9);
        assertEquals(52.5, adjusted.high(), 1e-9);
        assertEquals(49.5, adjusted.low(), 1e-9);
        assertEquals(52.0, adjusted.close(), 1e-9);
        assertEquals(1000L, adjusted.volume());
    }

    @Test
    void duplicateSessionKeepsTheLastRow() {
        Bar dup = YahooChartParser.parse(fixture(), ist(2026, 10, 29, 11, 0)).get(1);

        assertEquals(107.0, dup.close(), 1e-9);
    }

    @Test
    void includesTodayOnceTheSessionIsFinal() {
        List<Bar> bars = YahooChartParser.parse(fixture(), ist(2026, 10, 29, 16, 30));

        assertEquals(LocalDate.parse("2026-10-29"), bars.get(bars.size() - 1).date());
        assertEquals(3, bars.size());
    }

    @Test
    void yahooErrorBecomesMarketDataException() {
        String json = """
            {"chart":{"result":null,"error":{"code":"Not Found","description":"No data found, symbol may be delisted"}}}
            """;

        MarketDataException e = assertThrows(MarketDataException.class,
                () -> YahooChartParser.parse(json, ist(2026, 10, 29, 11, 0)));
        assertTrue(e.getMessage().contains("delisted"));
    }

    @Test
    void garbageBecomesMarketDataException() {
        assertThrows(MarketDataException.class,
                () -> YahooChartParser.parse("<html>blocked</html>", ist(2026, 10, 29, 11, 0)));
    }
}
