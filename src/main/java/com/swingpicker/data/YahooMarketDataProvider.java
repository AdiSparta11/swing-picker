package com.swingpicker.data;

import com.swingpicker.core.MarketDataProvider;
import com.swingpicker.domain.Bar;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * Daily bars for NSE stocks from Yahoo's unofficial chart endpoint ("RELIANCE" -> "RELIANCE.NS").
 * Unofficial means it can change, lag or throttle; failures surface as MarketDataException, never as empty data.
 */
public class YahooMarketDataProvider implements MarketDataProvider {

    private static final String URL =
            "https://query2.finance.yahoo.com/v8/finance/chart/%s?range=%dy&interval=1d&events=div%%2Csplit&includeAdjustedClose=true";
    // Yahoo rejects some default client user agents.
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36";

    private final HttpClient http;
    private final Clock clock;

    public YahooMarketDataProvider(HttpClient http, Clock clock) {
        this.http = http;
        this.clock = clock;
    }

    @Override
    public List<Bar> dailyBars(String symbol, int years) {
        String yahooSymbol = URLEncoder.encode(symbol.trim().toUpperCase() + ".NS", StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(String.format(URL, yahooSymbol, years)))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new MarketDataException("Could not reach Yahoo for " + symbol + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MarketDataException("Interrupted while fetching " + symbol, e);
        }

        int status = response.statusCode();
        if (status == 429) {
            throw new MarketDataException("Yahoo is rate-limiting this server (HTTP 429) for " + symbol);
        }
        if (status == 404) {
            throw new MarketDataException("Unknown NSE symbol: " + symbol);
        }
        if (status != 200) {
            throw new MarketDataException("Yahoo returned HTTP " + status + " for " + symbol);
        }
        return YahooChartParser.parse(response.body(), ZonedDateTime.now(clock));
    }
}
