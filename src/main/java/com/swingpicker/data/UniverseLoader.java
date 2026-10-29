package com.swingpicker.data;

import com.swingpicker.core.RecommendationService.Stock;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The list of stocks to scan. Tries NSE's official index-constituent CSVs (current list, with industry),
 * and falls back to a bundled list if they can't be reached.
 */
public final class UniverseLoader {

    public record Universe(List<Stock> stocks, String source) {
    }

    public static final List<String> NIFTY_200_URLS = List.of(
            "https://nsearchives.nseindia.com/content/indices/ind_nifty200list.csv",
            "https://www.niftyindices.com/IndexConstituent/ind_nifty200list.csv");

    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36";

    private UniverseLoader() {
    }

    public static Universe load(HttpClient http, List<String> urls, String bundledCsv) {
        for (String url : urls) {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(10))
                        .header("User-Agent", USER_AGENT)
                        .header("Accept", "text/csv,*/*")
                        .GET().build();
                HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() == 200) {
                    List<Stock> stocks = parseNseConstituents(res.body());
                    if (stocks.size() >= 50) {
                        return new Universe(stocks, url);
                    }
                }
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                // try the next source
            }
        }
        return new Universe(parseBundled(bundledCsv), "bundled universe.csv (NSE list unreachable)");
    }

    /** NSE format: Company Name,Industry,Symbol,Series,ISIN Code. Only EQ-series rows are kept. */
    public static List<Stock> parseNseConstituents(String csv) {
        String[] lines = csv.replace("\r", "").split("\n");
        if (lines.length < 2) {
            return List.of();
        }
        List<String> header = splitCsvLine(lines[0]);
        int ind = indexOf(header, "Industry");
        int sym = indexOf(header, "Symbol");
        int ser = indexOf(header, "Series");
        if (ind < 0 || sym < 0) {
            return List.of();
        }
        List<Stock> out = new ArrayList<>();
        for (int k = 1; k < lines.length; k++) {
            if (lines[k].isBlank()) {
                continue;
            }
            List<String> cols = splitCsvLine(lines[k]);
            if (cols.size() <= Math.max(ind, sym)) {
                continue;
            }
            if (ser >= 0 && cols.size() > ser && !cols.get(ser).isBlank() && !cols.get(ser).equalsIgnoreCase("EQ")) {
                continue;
            }
            out.add(new Stock(cols.get(sym), cols.get(ind)));
        }
        return out;
    }

    /** Bundled format: symbol,sector with a header row. */
    public static List<Stock> parseBundled(String csv) {
        List<Stock> out = new ArrayList<>();
        String[] lines = csv.replace("\r", "").split("\n");
        for (int k = 1; k < lines.length; k++) {
            List<String> cols = splitCsvLine(lines[k]);
            if (cols.size() >= 2 && !cols.get(0).isBlank()) {
                out.add(new Stock(cols.get(0), cols.get(1)));
            }
        }
        return out;
    }

    private static int indexOf(List<String> header, String name) {
        for (int k = 0; k < header.size(); k++) {
            if (header.get(k).equalsIgnoreCase(name)) {
                return k;
            }
        }
        return -1;
    }

    /** Minimal CSV split that respects double quotes (company names like "Mahindra & Mahindra, Ltd."). */
    static List<String> splitCsvLine(String line) {
        List<String> cols = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int k = 0; k < line.length(); k++) {
            char ch = line.charAt(k);
            if (ch == '"') {
                if (quoted && k + 1 < line.length() && line.charAt(k + 1) == '"') {
                    cur.append('"');
                    k++;
                } else {
                    quoted = !quoted;
                }
            } else if (ch == ',' && !quoted) {
                cols.add(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        cols.add(cur.toString().trim());
        return cols;
    }
}
