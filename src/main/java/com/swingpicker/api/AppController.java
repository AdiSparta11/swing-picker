package com.swingpicker.api;

import com.swingpicker.auth.Auth;
import com.swingpicker.core.LiveSession;
import com.swingpicker.core.MarketDataProvider;
import com.swingpicker.core.MarketRegime;
import com.swingpicker.core.RecommendationService;
import com.swingpicker.core.momentum.MomentumResult;
import com.swingpicker.core.momentum.MomentumService;
import com.swingpicker.core.portfolio.PortfolioService;
import com.swingpicker.core.portfolio.PortfolioService.Portfolio;
import com.swingpicker.core.portfolio.PortfolioService.StockCard;
import com.swingpicker.core.portfolio.PortfolioService.Watchlist;
import com.swingpicker.core.track.TrackRecordService;
import com.swingpicker.domain.Bar;
import com.swingpicker.domain.Recommendation;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/** Dashboard, portfolio, watchlist, track record, and the daily job. */
@RestController
@RequestMapping("/api")
public class AppController {

    /** Amounts used when the app runs on its own (dashboard, daily job); your own amount is used on Find trades. */
    static final double DASHBOARD_SWING_AMOUNT = 100_000;
    static final double DASHBOARD_MOMENTUM_AMOUNT = 50_000;

    public record HoldingRequest(String symbol, Integer quantity, Double averagePrice, String buyDate) {
    }

    public record WatchRequest(String symbol) {
    }

    public record Market(Double niftyClose, Double niftyChange, Boolean uptrend, LocalDate asOf, boolean live,
                         String checkedAt) {
    }

    public record Dashboard(Market market, MomentumResult momentum, Recommendation swing, Portfolio portfolio,
                            List<StockCard> watchlist, List<TrackRecordService.Summary> track, boolean saved,
                            Instant computedAt) {
    }

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE d MMM, h:mm a");

    private final RecommendationService swing;
    private final MomentumService momentum;
    private final PortfolioService portfolio;
    private final TrackRecordService track;
    private final MarketDataProvider data;
    private final MarketRegime regime;
    private final LiveSession session;
    private final Auth auth;
    private Dashboard cached;

    public AppController(RecommendationService swing, MomentumService momentum, PortfolioService portfolio,
                         TrackRecordService track, MarketDataProvider data, MarketRegime regime, LiveSession session,
                         Auth auth) {
        this.swing = swing;
        this.momentum = momentum;
        this.portfolio = portfolio;
        this.track = track;
        this.data = data;
        this.regime = regime;
        this.session = session;
        this.auth = auth;
    }

    // ---------- dashboard ----------

    @GetMapping("/dashboard")
    public synchronized Dashboard dashboard() {
        if (cached != null && cached.computedAt().plusSeconds(300).isAfter(Instant.now())) {
            return cached;
        }
        Recommendation s = swing.recommend(DASHBOARD_SWING_AMOUNT);
        MomentumResult m = momentum.recommend(DASHBOARD_MOMENTUM_AMOUNT);
        track.logSwing(s);
        track.logMomentum(m, 21);
        Portfolio p = portfolio.portfolio();
        List<StockCard> w = portfolio.watchlist().items();
        TrackRecordService.Report t = track.report();
        cached = new Dashboard(market(), m, s, p, w, t.summaries(), t.saved(), Instant.now());
        return cached;
    }

    private Market market() {
        List<Bar> nifty;
        try {
            nifty = LiveSession.extend(data.dailyBars(MarketRegime.NIFTY, 5), session.live(MarketRegime.NIFTY));
        } catch (RuntimeException e) {
            nifty = List.of();
        }
        Boolean up = regime.latest().map(Map.Entry::getValue).orElse(null);
        String at = session.now().format(WHEN) + " IST";
        if (nifty.size() < 2) {
            return new Market(null, null, up, null, session.isOpen(), at);
        }
        Bar last = nifty.get(nifty.size() - 1);
        Bar prev = nifty.get(nifty.size() - 2);
        boolean live = session.isOpen() && last.date().equals(session.now().toLocalDate());
        return new Market(last.close(), last.close() / prev.close() - 1, up, last.date(), live, at);
    }

    private synchronized void invalidate() {
        cached = null;
    }

    // ---------- portfolio ----------

    @GetMapping("/portfolio")
    public Portfolio portfolio() {
        return portfolio.portfolio();
    }

    @PostMapping("/portfolio")
    public StockCard addHolding(@RequestBody HoldingRequest r) {
        StockCard c = portfolio.add(r.symbol(), require(r.quantity(), "quantity"), require(r.averagePrice(), "average price"),
                date(r.buyDate()));
        invalidate();
        return c;
    }

    @PutMapping("/portfolio/{id}")
    public StockCard updateHolding(@PathVariable long id, @RequestBody HoldingRequest r) {
        StockCard c = portfolio.update(id, require(r.quantity(), "quantity"), require(r.averagePrice(), "average price"),
                date(r.buyDate()));
        invalidate();
        return c;
    }

    @DeleteMapping("/portfolio/{id}")
    public Map<String, Object> deleteHolding(@PathVariable long id) {
        boolean ok = portfolio.remove(id);
        invalidate();
        return Map.of("deleted", ok);
    }

    // ---------- watchlist ----------

    @GetMapping("/watchlist")
    public Watchlist watchlist() {
        return portfolio.watchlist();
    }

    @PostMapping("/watchlist")
    public StockCard watch(@RequestBody WatchRequest r) {
        StockCard c = portfolio.watch(r.symbol());
        invalidate();
        return c;
    }

    @DeleteMapping("/watchlist/{symbol}")
    public Map<String, Object> unwatch(@PathVariable String symbol) {
        boolean ok = portfolio.unwatch(symbol);
        invalidate();
        return Map.of("deleted", ok);
    }

    // ---------- track record ----------

    @GetMapping("/track")
    public TrackRecordService.Report trackRecord() {
        return track.report();
    }

    /**
     * Run by cron-job.org after the close on weekdays (with header X-Job-Token) so picks get logged and graded
     * even on days you don't open the app.
     */
    @PostMapping("/jobs/daily")
    public Map<String, Object> daily(@RequestHeader(value = "X-Job-Token", required = false) String token,
                                     HttpServletResponse w) {
        if (!auth.jobsEnabled() || !auth.checkJobToken(token)) {
            w.setStatus(403);
            return Map.of("error", "Missing or wrong job token");
        }
        Recommendation s = swing.recommend(DASHBOARD_SWING_AMOUNT);
        MomentumResult m = momentum.recommend(DASHBOARD_MOMENTUM_AMOUNT);
        int swingLogged = track.logSwing(s);
        int momentumLogged = track.logMomentum(m, 21);
        int graded = track.gradeOpen();
        invalidate();
        return Map.of("swingLogged", swingLogged, "momentumLogged", momentumLogged, "graded", graded,
                "marketOpen", session.isOpen());
    }

    // ---------- helpers ----------

    private static <T> T require(T v, String name) {
        if (v == null) {
            throw new IllegalArgumentException("Missing " + name);
        }
        return v;
    }

    private static LocalDate date(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(s.trim());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Buy date should look like 2025-03-10");
        }
    }
}
