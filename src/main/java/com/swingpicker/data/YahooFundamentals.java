package com.swingpicker.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.swingpicker.core.analysis.Consensus;
import com.swingpicker.core.analysis.FundamentalsProvider;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Analyst consensus from Yahoo's quoteSummary endpoint. Yahoo requires a session cookie plus a "crumb" token:
 * visit fc.yahoo.com for the cookie, ask /v1/test/getcrumb for the token, then pass it with every request.
 * Results are cached for 12 hours, and a failure is remembered for 30 minutes so a blocked server doesn't slow
 * every check. Any failure just means "no consensus available".
 */
public class YahooFundamentals implements FundamentalsProvider {

    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36";
    private static final String SUMMARY = "https://query2.finance.yahoo.com/v10/finance/quoteSummary/%s?modules=financialData,defaultKeyStatistics,summaryDetail&crumb=%s";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private record Cached(Optional<Consensus> value, Instant at) {
    }

    private final HttpClient http;
    private final Clock clock;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private volatile String crumb;
    private volatile Instant failedAt;

    public YahooFundamentals(Clock clock) {
        this.clock = clock;
        CookieManager cookies = new CookieManager();
        cookies.setCookiePolicy(CookiePolicy.ACCEPT_ALL);
        this.http = HttpClient.newBuilder()
                .cookieHandler(cookies)
                .connectTimeout(Duration.ofSeconds(8))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public Optional<Consensus> consensus(String symbol) {
        String key = symbol.trim().toUpperCase();
        Instant now = clock.instant();
        Cached hit = cache.get(key);
        if (hit != null && hit.at().plus(Duration.ofHours(12)).isAfter(now)) {
            return hit.value();
        }
        if (failedAt != null && failedAt.plus(Duration.ofMinutes(30)).isAfter(now)) {
            return Optional.empty();
        }
        try {
            Optional<Consensus> c = fetch(key, false);
            cache.put(key, new Cached(c, now));
            failedAt = null;
            return c;
        } catch (IOException | RuntimeException e) {
            failedAt = now;
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    private Optional<Consensus> fetch(String symbol, boolean retried) throws IOException, InterruptedException {
        if (crumb == null) {
            crumb = newCrumb();
        }
        String ySymbol = URLEncoder.encode(symbol + ".NS", StandardCharsets.UTF_8);
        HttpResponse<String> res = http.send(get(String.format(SUMMARY, ySymbol, URLEncoder.encode(crumb, StandardCharsets.UTF_8))),
                HttpResponse.BodyHandlers.ofString());
        if ((res.statusCode() == 401 || res.statusCode() == 403) && !retried) {
            crumb = null;   // expired session: one fresh handshake
            return fetch(symbol, true);
        }
        if (res.statusCode() == 404) {
            return Optional.empty();
        }
        if (res.statusCode() != 200) {
            throw new IOException("quoteSummary HTTP " + res.statusCode());
        }
        return parse(res.body());
    }

    private String newCrumb() throws IOException, InterruptedException {
        http.send(get("https://fc.yahoo.com"), HttpResponse.BodyHandlers.discarding());   // sets the session cookie (status can be 404)
        HttpResponse<String> res = http.send(get("https://query2.finance.yahoo.com/v1/test/getcrumb"),
                HttpResponse.BodyHandlers.ofString());
        String c = res.body() == null ? "" : res.body().trim();
        if (res.statusCode() != 200 || c.isEmpty() || c.startsWith("{") || c.startsWith("<")) {
            throw new IOException("No crumb (HTTP " + res.statusCode() + ")");
        }
        return c;
    }

    private static HttpRequest get(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(8))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json,text/plain,*/*")
                .GET().build();
    }

    static Optional<Consensus> parse(String json) throws IOException {
        JsonNode r = MAPPER.readTree(json).path("quoteSummary").path("result").path(0);
        if (r.isMissingNode() || r.isNull()) {
            return Optional.empty();
        }
        JsonNode fd = r.path("financialData");
        JsonNode ks = r.path("defaultKeyStatistics");
        JsonNode sd = r.path("summaryDetail");
        Double count = raw(fd, "numberOfAnalystOpinions");
        String rating = fd.path("recommendationKey").asText(null);
        if (rating != null && (rating.isBlank() || rating.equalsIgnoreCase("none"))) {
            rating = null;
        }
        Double de = raw(fd, "debtToEquity");
        Consensus c = new Consensus(raw(fd, "targetMeanPrice"), raw(fd, "targetHighPrice"), raw(fd, "targetLowPrice"),
                count == null ? null : count.intValue(), rating, raw(fd, "recommendationMean"),
                firstNonNull(raw(sd, "trailingPE"), raw(ks, "trailingPE")), firstNonNull(raw(ks, "forwardPE"), raw(sd, "forwardPE")),
                raw(ks, "trailingEps"), raw(ks, "forwardEps"), raw(fd, "earningsGrowth"), raw(fd, "revenueGrowth"),
                raw(fd, "profitMargins"), de == null ? null : de / 100.0);   // Yahoo reports debt/equity as a percentage
        return Optional.of(c);
    }

    private static Double raw(JsonNode parent, String field) {
        JsonNode n = parent.path(field);
        if (n.isNumber()) {
            return n.asDouble();
        }
        JsonNode raw = n.path("raw");
        return raw.isNumber() ? raw.asDouble() : null;
    }

    private static Double firstNonNull(Double a, Double b) {
        return a != null ? a : b;
    }
}
