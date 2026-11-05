package com.swingpicker.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Company name or symbol -> NSE symbols. Nifty 200 names (from NSE's own file) are matched locally first;
 * anything else goes to Yahoo's search, keeping only NSE-listed equities. If Yahoo is unreachable, local matches
 * still work and the typed text can still be used as a symbol.
 */
public class SymbolSearch {

    public record Match(String symbol, String name, boolean nifty200) {
    }

    private static final String URL = "https://query2.finance.yahoo.com/v1/finance/search?q=%s&quotesCount=10&newsCount=0";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, String> localNames;
    private final HttpClient http;

    public SymbolSearch(Map<String, String> localNames, HttpClient http) {
        this.localNames = Map.copyOf(localNames);
        this.http = http;
    }

    public List<Match> search(String query) {
        String q = query == null ? "" : query.trim();
        if (q.length() < 2) {
            return List.of();
        }
        Map<String, Match> out = new LinkedHashMap<>();
        localMatches(q).forEach(m -> out.putIfAbsent(m.symbol(), m));
        if (out.size() < 8 && http != null) {
            try {
                for (Match m : parseYahoo(fetch(q))) {
                    out.putIfAbsent(m.symbol(), new Match(m.symbol(), m.name(), localNames.containsKey(m.symbol())));
                }
            } catch (RuntimeException e) {
                // local results are still useful
            }
        }
        return out.values().stream().limit(10).toList();
    }

    List<Match> localMatches(String q) {
        String needle = q.toUpperCase(Locale.ROOT);
        List<Match> exact = new ArrayList<>();
        List<Match> starts = new ArrayList<>();
        List<Match> contains = new ArrayList<>();
        localNames.forEach((sym, name) -> {
            String n = name.toUpperCase(Locale.ROOT);
            Match m = new Match(sym, name, true);
            if (sym.equals(needle)) {
                exact.add(m);
            } else if (sym.startsWith(needle) || n.startsWith(needle)) {
                starts.add(m);
            } else if (n.contains(needle) || sym.contains(needle)) {
                contains.add(m);
            }
        });
        List<Match> all = new ArrayList<>(exact);
        all.addAll(starts);
        all.addAll(contains);
        return all;
    }

    private String fetch(String q) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(String.format(URL, URLEncoder.encode(q, StandardCharsets.UTF_8))))
                .timeout(Duration.ofSeconds(8))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .GET().build();
        try {
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                throw new MarketDataException("Yahoo search returned HTTP " + res.statusCode());
            }
            return res.body();
        } catch (java.io.IOException e) {
            throw new MarketDataException("Could not reach Yahoo search: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MarketDataException("Interrupted", e);
        }
    }

    /** Keeps NSE equities ("XYZ.NS"); BSE, indices, funds and other exchanges are dropped. */
    static List<Match> parseYahoo(String json) {
        List<Match> out = new ArrayList<>();
        JsonNode quotes;
        try {
            quotes = MAPPER.readTree(json).path("quotes");
        } catch (java.io.IOException e) {
            return out;
        }
        for (JsonNode q : quotes) {
            String sym = q.path("symbol").asText("");
            String type = q.path("quoteType").asText("");
            if (!sym.endsWith(".NS") || !"EQUITY".equalsIgnoreCase(type)) {
                continue;
            }
            String name = q.path("longname").asText(q.path("shortname").asText(sym));
            out.add(new Match(sym.substring(0, sym.length() - 3), name, false));
        }
        return out;
    }
}
