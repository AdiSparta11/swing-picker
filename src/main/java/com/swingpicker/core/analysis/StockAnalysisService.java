package com.swingpicker.core.analysis;

import com.swingpicker.core.EdgeEstimator;
import com.swingpicker.core.Indicators;
import com.swingpicker.core.LiveSession;
import com.swingpicker.core.MarketDataProvider;
import com.swingpicker.core.MarketRegime;
import com.swingpicker.core.RecommendationService.Stock;
import com.swingpicker.core.Setup;
import com.swingpicker.core.analysis.StockAnalysis.HorizonOutlook;
import com.swingpicker.core.analysis.StockAnalysis.Holding;
import com.swingpicker.core.analysis.StockAnalysis.Momentum;
import com.swingpicker.core.analysis.StockAnalysis.Reason;
import com.swingpicker.core.analysis.StockAnalysis.SetupSignal;
import com.swingpicker.core.analysis.StockAnalysis.Trend;
import com.swingpicker.core.analysis.StockAnalysis.Verdict;
import com.swingpicker.core.momentum.MomentumScore;
import com.swingpicker.domain.Bar;
import com.swingpicker.domain.SetupRecord;
import com.swingpicker.domain.TradePlan;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One stock, every signal the app has, one plain verdict with the reasons behind each point.
 *
 * Verdict points (the stock's buy price is deliberately NOT one of them: the market doesn't know what you paid):
 *   Momentum rank vs the Nifty 200 today: top 20% +2, next 20% +1, middle 0, next -1, bottom 20% -2.
 *   Own trend: above a rising-structure 200-day average (50 > 200) +1; below the 200-day -1.
 *   Market: Nifty not in an uptrend -1 (headwind for everything).
 *   Evidence: stocks in this quintile and market state beat all stocks over 6 months by more than 1 point +1,
 *             lagged by more than 1 point -1.
 *   Timing: a swing setup with a positive record in both periods fired today +1.
 */
public class StockAnalysisService {

    public record Config(int historyYears, int sampleStep, Duration modelTtl) {
    }

    /** Your position in the stock; buyDate may be null. */
    public record Position(int quantity, double averagePrice, LocalDate buyDate) {
    }

    private record Model(Instant at, ForwardReturnModel model) {
    }

    private final List<Stock> universe;
    private final Map<String, String> companyNames;
    private final MarketDataProvider data;
    private final MarketRegime regime;
    private final List<Setup> setups;
    private final EdgeEstimator edges;
    private final LiveSession session;
    private final Clock clock;
    private final Config config;
    private final FundamentalsProvider fundamentals;
    private Model model;

    public StockAnalysisService(List<Stock> universe, Map<String, String> companyNames, MarketDataProvider data,
                                MarketRegime regime, List<Setup> setups, EdgeEstimator edges, LiveSession session,
                                Clock clock, Config config) {
        this(universe, companyNames, data, regime, setups, edges, session, clock, config, FundamentalsProvider.none());
    }

    public StockAnalysisService(List<Stock> universe, Map<String, String> companyNames, MarketDataProvider data,
                                MarketRegime regime, List<Setup> setups, EdgeEstimator edges, LiveSession session,
                                Clock clock, Config config, FundamentalsProvider fundamentals) {
        this.fundamentals = fundamentals;
        this.universe = List.copyOf(universe);
        this.companyNames = Map.copyOf(companyNames);
        this.data = data;
        this.regime = regime;
        this.setups = List.copyOf(setups);
        this.edges = edges;
        this.session = session;
        this.clock = clock;
        this.config = config;
    }

    public StockAnalysis analyze(String rawSymbol, Position position) {
        String symbol = normalise(rawSymbol);
        if (symbol.isEmpty()) {
            throw new IllegalArgumentException("Enter an NSE symbol, e.g. RELIANCE");
        }
        List<Bar> completed = data.dailyBars(symbol, config.historyYears());
        if (completed.size() < 60) {
            throw new IllegalArgumentException(symbol + " has too little price history on NSE to analyse ("
                    + completed.size() + " sessions)");
        }
        List<Bar> bars = LiveSession.extend(completed, session.live(symbol));
        int last = bars.size() - 1;
        Bar today = bars.get(last);
        double price = today.close();
        boolean live = session.isOpen() && today.date().equals(session.now().toLocalDate());

        Map<String, List<Bar>> universeBars = loadUniverse();
        Trend trend = trend(bars);
        Momentum momentum = momentum(symbol, bars, universeBars);
        Boolean up = regime.latest().map(Map.Entry::getValue).orElse(null);

        List<SetupSignal> fired = new ArrayList<>();
        for (Setup s : setups) {
            Optional<TradePlan> plan = s.detect(symbol, bars);
            if (plan.isPresent()) {
                SetupRecord rec = edges.record(s);
                fired.add(new SetupSignal(plan.get(), rec, rec.earlierExpectancyR() > 0 && rec.recentExpectancyR() > 0));
            }
        }

        ForwardReturnModel m = model(universeBars);
        List<HorizonOutlook> outlook = new ArrayList<>();
        if (momentum != null) {
            for (int h = 0; h < ForwardReturnModel.HORIZONS.length; h++) {
                ForwardReturnModel.Stats s = m.get(momentum.quintile(), up, h);
                ForwardReturnModel.Stats base = m.get(0, up, h);
                if (s.n() == 0) {
                    continue;
                }
                LocalDate around = today.date().plusDays(Math.round(ForwardReturnModel.HORIZONS[h] * 365.0 / 252));
                Double chance = position == null ? null
                        : m.shareAtLeast(momentum.quintile(), up, h, position.averagePrice() / price - 1);
                outlook.add(new HorizonOutlook(ForwardReturnModel.LABELS[h], ForwardReturnModel.HORIZONS[h], around,
                        price * (1 + s.median()), price * (1 + s.p25()), price * (1 + s.p75()), s.median(), s.p25(),
                        s.p75(), s.positiveShare(), s.n(), base.median(), m.usesRegime(momentum.quintile(), up, h),
                        chance));
            }
        }

        Consensus consensus = fundamentals.consensus(symbol).orElse(null);
        Verdict verdict = verdict(momentum, trend, up, outlook, fired, position != null, consensus, price);
        Holding holding = position == null ? null : holding(position, price, verdict);
        StockAnalysis.Call call = call(verdict, holding, chanceAt(outlook, 126), chanceAt(outlook, 252));
        String name = companyNames.getOrDefault(symbol, symbol);
        String sector = universe.stream().filter(x -> x.symbol().equals(symbol)).map(Stock::sector).findFirst().orElse("");
        boolean inUniverse = !sector.isEmpty();
        return new StockAnalysis(symbol, name, sector, inUniverse, today.date(), live, price, trend, momentum, up,
                fired, outlook, m.from(), m.to(), verdict, holding, consensus, call);
    }

    static String normalise(String raw) {
        String s = raw == null ? "" : raw.trim().toUpperCase();
        if (s.endsWith(".NS")) {
            s = s.substring(0, s.length() - 3);
        }
        return s;
    }

    // ---------- pieces ----------

    static Trend trend(List<Bar> bars) {
        int i = bars.size() - 1;
        double price = bars.get(i).close();
        double sma50 = i >= 49 ? Indicators.sma(bars, i, 50) : Double.NaN;
        double sma200 = i >= 199 ? Indicators.sma(bars, i, 200) : Double.NaN;
        int from = Math.max(0, i - 251);
        double high = 0;
        double low = Double.MAX_VALUE;
        for (int k = from; k <= i; k++) {
            high = Math.max(high, bars.get(k).high());
            low = Math.min(low, bars.get(k).low());
        }
        double vol = 0;
        int n = Math.min(252, i);
        if (n > 1) {
            double sum = 0;
            double sq = 0;
            for (int k = i - n + 1; k <= i; k++) {
                double lr = Math.log(bars.get(k).close() / bars.get(k - 1).close());
                sum += lr;
                sq += lr * lr;
            }
            vol = Math.sqrt(Math.max(0, (sq - sum * sum / n) / (n - 1)) * 252);
        }
        return new Trend(sma50, sma200, Double.isNaN(sma200) ? Double.NaN : price / sma200 - 1,
                !Double.isNaN(sma200) && price > sma200,
                !Double.isNaN(sma200) && sma50 > sma200, high, low, price / high - 1,
                ret(bars, 21), ret(bars, 63), ret(bars, 126), ret(bars, 252), vol);
    }

    private static double ret(List<Bar> bars, int back) {
        int i = bars.size() - 1;
        return i - back >= 0 ? bars.get(i).close() / bars.get(i - back).close() - 1 : Double.NaN;
    }

    /** Rank today's score against every universe stock scored on the same latest date. */
    static Momentum momentum(String symbol, List<Bar> bars, Map<String, List<Bar>> universeBars) {
        Optional<MomentumScore> mine = MomentumScore.at(bars, bars.size() - 1);
        if (mine.isEmpty()) {
            return null;
        }
        LocalDate d = bars.get(bars.size() - 1).date();
        List<Double> others = new ArrayList<>();
        for (Map.Entry<String, List<Bar>> e : universeBars.entrySet()) {
            if (e.getKey().equals(symbol)) {
                continue;
            }
            List<Bar> b = e.getValue();
            int idx = b.size() - 1;
            // compare on the same day: if the universe stock lacks today's bar (e.g. live bar missing), use its latest
            if (b.get(idx).date().isAfter(d)) {
                continue;
            }
            MomentumScore.at(b, idx).ifPresent(sc -> others.add(sc.score()));
        }
        if (others.isEmpty()) {
            return new Momentum(mine.get().score(), 0.5, 3, 0);
        }
        long beaten = others.stream().filter(x -> x < mine.get().score()).count();
        double share = beaten / (double) others.size();
        return new Momentum(mine.get().score(), share, ForwardReturnModel.quintileOf(share), others.size() + 1);
    }

    static Verdict verdict(Momentum m, Trend t, Boolean up, List<HorizonOutlook> outlook, List<SetupSignal> fired,
                           boolean holding) {
        return verdict(m, t, up, outlook, fired, holding, null, Double.NaN);
    }

    static Verdict verdict(Momentum m, Trend t, Boolean up, List<HorizonOutlook> outlook, List<SetupSignal> fired,
                           boolean holding, Consensus consensus, double price) {
        List<Reason> reasons = new ArrayList<>();
        if (m == null) {
            reasons.add(new Reason(0, "Less than a year of prices, so it can't be ranked on momentum."));
        } else {
            int pts = 3 - m.quintile();   // 1 -> +2 ... 5 -> -2
            String where = switch (m.quintile()) {
                case 1 -> "in the top 20%";
                case 2 -> "in the top 40%";
                case 3 -> "in the middle";
                case 4 -> "in the bottom 40%";
                default -> "in the bottom 20%";
            };
            reasons.add(new Reason(pts, String.format("Momentum: stronger than %d%% of Nifty 200 stocks over 6-12 months (%s).",
                    Math.round(m.shareBeaten() * 100), where)));
        }
        if (Double.isNaN(t.sma200())) {
            reasons.add(new Reason(0, "Trend: under 200 sessions of history, so no long-term trend read."));
        } else if (t.aboveSma200() && t.sma50AboveSma200()) {
            reasons.add(new Reason(1, "Trend: above its 200-day average, with the 50-day above the 200-day (an uptrend)."));
        } else if (!t.aboveSma200()) {
            reasons.add(new Reason(-1, "Trend: " + pct(t.vsSma200()).replace("−", "") + " below its 200-day average (a downtrend)."));
        } else {
            reasons.add(new Reason(0, "Trend: above its 200-day average, but the 50-day hasn't crossed above it yet."));
        }
        if (up == null) {
            reasons.add(new Reason(0, "Market: Nifty data unavailable, so no market read."));
        } else if (up) {
            reasons.add(new Reason(0, "Market: Nifty 50 is in an uptrend (no headwind)."));
        } else {
            reasons.add(new Reason(-1, "Market: Nifty 50 is below its rising 200-day average, a headwind for most stocks."));
        }
        outlook.stream().filter(o -> o.sessions() == 126).findFirst().ifPresent(o -> {
            double edge = o.median() - o.baselineMedian();
            int pts = edge > 0.01 ? 1 : edge < -0.01 ? -1 : 0;
            reasons.add(new Reason(pts, String.format("History: stocks ranked like this %s went on to a median %s over "
                            + "6 months, versus %s for all stocks.",
                    o.sameMarketState() ? "in this kind of market" : "(all market conditions)", pct(o.median()),
                    pct(o.baselineMedian()))));
        });
        List<SetupSignal> good = fired.stream().filter(SetupSignal::hasEdge).toList();
        if (!good.isEmpty()) {
            reasons.add(new Reason(1, "Timing: the " + good.get(0).plan().setupName()
                    + " swing setup fired on the latest session, and that setup made money in both periods tested."));
        } else if (!fired.isEmpty()) {
            reasons.add(new Reason(0, "Timing: the " + fired.get(0).plan().setupName()
                    + " swing setup fired, but that setup hasn't made money in both periods, so it doesn't count."));
        }

        if (consensus != null && consensus.hasTarget() && price > 0) {
            double upside = consensus.targetMean() / price - 1;
            String base = String.format("Analysts: %d analyst%s' average 12-month target is %s (%s from here)%s.",
                    consensus.analysts(), consensus.analysts() == 1 ? "" : "s", rupees(consensus.targetMean()), pct(upside),
                    consensus.rating() == null ? "" : ", rating " + consensus.rating().replace('_', ' '));
            if (consensus.analysts() < 3) {
                reasons.add(new Reason(0, base + " Too few analysts to count."));
            } else if (upside > 0.15) {
                reasons.add(new Reason(1, base + " Targets run optimistic on average, so only an upside above 15% counts."));
            } else if (upside < 0) {
                reasons.add(new Reason(-1, base + " Even the analysts' average sees it lower."));
            } else {
                reasons.add(new Reason(0, base + " Under 15% upside is within analysts' usual optimism, so it doesn't count."));
            }
        } else if (consensus != null || !Double.isNaN(price)) {
            reasons.add(new Reason(0, "Analysts: no consensus price target found for this stock."));
        }

        int total = reasons.stream().mapToInt(Reason::points).sum();
        String label;
        String action;
        if (total >= 3) {
            label = "Strong";
            action = holding ? "Hold. The signals would buy this stock today." : "Buy candidate. The signals favour it.";
        } else if (total >= 1) {
            label = "Favourable";
            action = holding ? "Hold. More signals for it than against." : "Reasonable to consider; not a standout.";
        } else if (total == 0) {
            label = "Neutral";
            action = holding ? "No strong signal either way. Hold only if your own reason for owning it still stands."
                    : "No edge either way. Waiting costs nothing.";
        } else if (total >= -2) {
            label = "Weak";
            action = holding ? "Weak. The signals wouldn't buy it today; consider trimming or switching."
                    : "Avoid for now. More signals against than for.";
        } else {
            label = "Very weak";
            action = holding ? "Consider exiting. Almost every signal is against it." : "Avoid. Almost every signal is against it.";
        }
        return new Verdict(total, label, action, reasons);
    }

    static Double chanceAt(List<HorizonOutlook> outlook, int sessions) {
        return outlook.stream().filter(o -> o.sessions() == sessions).map(HorizonOutlook::chanceAboveCost)
                .filter(c -> c != null && !c.isNaN()).findFirst().orElse(null);
    }

    static final double RECOVERY_BAR = 0.40;
    static final int AGAINST = -2;
    static final int FOR = 2;

    /**
     * The bold one-word call.
     *   Not owned:  BUY at +2 points or more, AVOID at -2 or less, otherwise WAIT.
     *   Owned at a loss: SELL only if the signals are clearly against it (-2 or less) AND fewer than 40% of
     *                    similar cases got back above your price within a year (6 months if 1 year is unavailable);
     *                    otherwise HOLD.
     *   Owned in profit: SELL if the signals are clearly against it (-2 or less), otherwise HOLD.
     */
    static StockAnalysis.Call call(Verdict v, Holding h, Double chance6m, Double chance1y) {
        int pts = v.points();
        String p = (pts > 0 ? "+" : "") + pts + " point" + (Math.abs(pts) == 1 ? "" : "s");
        if (h == null) {
            String rule = "BUY at +2 points or more, AVOID at -2 or less, otherwise WAIT.";
            if (pts >= FOR) {
                return new StockAnalysis.Call("BUY", "Signals favour buying (" + p + ").", rule);
            }
            if (pts <= AGAINST) {
                return new StockAnalysis.Call("AVOID", "Signals are against it (" + p + ").", rule);
            }
            return new StockAnalysis.Call("WAIT", "No clear edge (" + p + "). Waiting costs nothing.", rule);
        }
        if (h.pnl() < 0) {
            String rule = "At a loss: SELL only if the signals are clearly against it (-2 points or less) AND fewer than "
                    + Math.round(RECOVERY_BAR * 100) + "% of similar cases got back above your price within a year. Otherwise HOLD.";
            Double c = chance1y != null ? chance1y : chance6m;
            String horizon = chance1y != null ? "a year" : "6 months";
            String odds = c == null ? "No history to estimate the chance of getting back to your price."
                    : Math.round(c * 100) + "% of similar cases got back above your " + rupees(h.averagePrice()) + " within " + horizon + ".";
            boolean poorOdds = c == null || c < RECOVERY_BAR;
            if (pts <= AGAINST && poorOdds) {
                return new StockAnalysis.Call("SELL", odds + " And the signals are against it (" + p + ").", rule);
            }
            if (!poorOdds) {
                return new StockAnalysis.Call("HOLD", odds, rule);
            }
            return new StockAnalysis.Call("HOLD", "Signals aren't strongly against it (" + p + "), though only " + odds, rule);
        }
        String rule = "In profit: SELL if the signals are clearly against it (-2 points or less), otherwise HOLD.";
        if (pts <= AGAINST) {
            return new StockAnalysis.Call("SELL", "In profit, but the signals have turned against it (" + p
                    + "). Consider booking the gain; see the tax note.", rule);
        }
        return new StockAnalysis.Call("HOLD", "In profit and the signals aren't against it (" + p + ").", rule);
    }

    static Holding holding(Position p, double price, Verdict v) {
        double invested = p.quantity() * p.averagePrice();
        double value = p.quantity() * price;
        double pnl = value - invested;
        double pnlPct = invested == 0 ? 0 : pnl / invested;
        double breakEven = price >= p.averagePrice() ? 0 : p.averagePrice() / price - 1;
        List<String> notes = new ArrayList<>();
        Long days = null;
        LocalDate ltFrom = null;
        Boolean lt = null;
        if (p.buyDate() != null) {
            days = ChronoUnit.DAYS.between(p.buyDate(), LocalDate.now());
            ltFrom = p.buyDate().plusYears(1).plusDays(1);
            lt = !LocalDate.now().isBefore(ltFrom);
        }

        notes.add("Your buy price doesn't change this stock's prospects. The real question is: with "
                + rupees(value) + " in cash today, would you buy this stock? The verdict above answers that.");
        if (pnl < 0) {
            notes.add(String.format("To get back to your average price it needs to rise %s from here. A %s fall needs a "
                    + "bigger rise to recover, so money held in a weak stock has to work harder than in a strong one.",
                    pct(breakEven), pct(pnlPct)));
            if (lt == null) {
                notes.add("Tax: selling at a loss books a capital loss you can set off against gains and carry forward "
                        + "8 years. Add your buy date to see whether it's short- or long-term.");
            } else if (!lt) {
                notes.add("Tax: selling now books a short-term capital loss of about " + rupees(-pnl)
                        + ". It can be set off against both short- and long-term capital gains, and carried forward 8 years.");
            } else {
                notes.add("Tax: selling now books a long-term capital loss of about " + rupees(-pnl)
                        + ". It can only be set off against long-term gains; carry forward up to 8 years.");
            }
        } else if (pnl > 0) {
            if (lt == null) {
                notes.add("Tax: gains are taxed at 20% if held up to 12 months, 12.5% above ₹1.25 lakh a year after that. "
                        + "Add your buy date to see which applies.");
            } else if (!lt) {
                long left = ChronoUnit.DAYS.between(LocalDate.now(), ltFrom);
                notes.add("Tax: this gain is short-term (20%). It turns long-term on " + ltFrom + " (in " + left
                        + " days), after which it's 12.5% on gains above ₹1.25 lakh a year."
                        + (v.points() >= 0 && left <= 60 ? " With signals not against it and the date this close, waiting may save tax." : ""));
            } else {
                notes.add("Tax: this gain is long-term: 12.5% on total long-term gains above ₹1.25 lakh in the financial year.");
            }
        }
        notes.add("Not tax or investment advice; check the tax figures with your CA.");
        return new Holding(p.quantity(), p.averagePrice(), invested, value, pnl, pnlPct, p.buyDate(), days, ltFrom, lt,
                breakEven, notes);
    }

    // ---------- data ----------

    private Map<String, List<Bar>> loadUniverse() {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            Map<String, CompletableFuture<List<Bar>>> futures = new LinkedHashMap<>();
            for (Stock s : universe) {
                futures.put(s.symbol(), CompletableFuture.supplyAsync(() -> data.dailyBars(s.symbol(), config.historyYears()), pool));
            }
            Map<String, List<Bar>> out = new LinkedHashMap<>();
            for (Map.Entry<String, CompletableFuture<List<Bar>>> f : futures.entrySet()) {
                try {
                    List<Bar> b = f.getValue().join();
                    if (!b.isEmpty()) {
                        out.put(f.getKey(), b);
                    }
                } catch (RuntimeException e) {
                    Throwable c = e.getCause() != null ? e.getCause() : e;
                    if (c instanceof UnsupportedOperationException u) {
                        throw u;
                    }
                }
            }
            return out;
        } finally {
            pool.shutdown();
        }
    }

    private synchronized ForwardReturnModel model(Map<String, List<Bar>> universeBars) {
        if (model == null || model.at().plus(config.modelTtl()).isBefore(clock.instant())) {
            List<Bar> nifty;
            try {
                nifty = data.dailyBars(MarketRegime.NIFTY, config.historyYears());
            } catch (RuntimeException e) {
                if (e instanceof UnsupportedOperationException) {
                    throw e;
                }
                nifty = List.of();
            }
            model = new Model(clock.instant(), ForwardReturnModel.build(universe, universeBars, nifty, config.sampleStep()));
        }
        return model.model();
    }

    static String pct(double v) {
        if (Double.isNaN(v)) {
            return "n/a";
        }
        return (v >= 0 ? "+" : "−") + String.format("%.1f%%", Math.abs(v * 100));
    }

    static String rupees(double v) {
        return com.swingpicker.core.Money.rupees(v);
    }
}
