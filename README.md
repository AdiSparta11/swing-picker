# swing-picker

Give it an amount ("I want to invest ₹10,000 today") and get two answers for NSE stocks, each with an honest track record:

- **Momentum portfolio** (hold ~1 month): the top-ranked Nifty 200 stocks by risk-adjusted 6/12-month return, sized to the amount, or "stay in cash" when the Nifty is in a downtrend.
- **Swing trades** (hold up to 3 weeks): entry, stop, target and quantity, or an explicit **"No trade today"** with the reason.

Not financial advice. It's software that measures how simple rules played out historically. Paper trade before using real money.

## Run

```
mvn spring-boot:run
curl -X POST localhost:8080/api/recommend -H 'Content-Type: application/json' -d '{"amount": 10000}'
```

Unbuilt steps answer `501 {"pending": "Step N: ..."}`.

## Design

```
api/        Spring REST controller            (edge)
config/     wiring: the only Spring <-> core seam
core/       RecommendationService + interfaces (plain Java, no Spring)
domain/     records: Bar, TradePlan, Edge, Candidate, Pick, Recommendation
```

`RecommendationService.recommend(amount)`:

1. For each stock in the Nifty 200 (`nifty200.csv`, NSE's file), run each `Setup` on daily bars; keep the ones that fired today.
2. Score each by **expected net R** (`Ranker`), using the setup's historical `Edge` and variable costs in R.
3. Drop scores below `minExpectedNetR`, sort best first, cap picks per sector.
4. `PositionSizer` turns them into whole-share quantities within the amount; fixed per-stock costs decide if a position is too small to be worth it.
5. Nothing survives → "no trade", with which stage eliminated everything.

## What it does today

| Piece | Choice |
|---|---|
| Universe | Nifty 200 from NSE's constituent file, bundled as `src/main/resources/nifty200.csv` (NSE blocks cloud servers). Replace it after each rebalance (end of March and September). Check: `GET /api/universe` |
| Data | Yahoo `.NS` daily bars (Nifty 50 as `^NSEI`), 5 years, split/dividend adjusted, completed sessions only. Check: `GET /api/bars/RELIANCE` |
| Setups | **Trend pullback:** uptrend (close > SMA200, SMA50 > SMA200 and rising), dip to SMA20 in last 5 sessions, turn = close above SMA20 and yesterday's high. **55-day breakout:** close > SMA200, close above the prior 55-day high on ≥1.5× 20-day volume, close in top 25% of the day's range, not more than 3 ATR above SMA20 |
| Market filter | "Nifty uptrend" = Nifty 50 above its 200-day average and that average higher than 20 sessions earlier. Swing setups run plain and filtered; momentum holds cash when it's off |
| Momentum | Score = (6-month + 12-month return) / 2 / annualised volatility. Monthly (21-session) rebalance, equal weight, keep holdings ranked within 2x the number held, ~30% sector cap. N stocks = amount / ₹5,000, max 10. Backtested at that N after charges, against all stocks equal-weight and the Nifty 50 |
| Out-of-sample check | Every strategy is reported for earlier years and the last 12 months separately. Swing setups must be positive in both; momentum must beat equal-weight in both |
| Transparency | Every run returns all stocks that fired with the reason each was or wasn't picked, plus each setup's 3-year track record |
| Plan | Stop just under the dip (min 1 ATR), target 2R, don't buy above entry + 0.25R, exit after 15 sessions |
| Swing backtest | Replays the rule over 5 years: next-open entries, gap fills at the open, stop assumed first if both touched. Per-stock stats blended toward the universe (prior of 20 trades) |
| Costs | Zerodha delivery schedule: STT 0.1% each side, stamp 0.015% buy, NSE 0.00307%, SEBI, GST, DP ₹15.34 per stock sold |
| Ranking | Expected net R = p·avgWin − (1−p)·avgLoss − costs; must exceed +0.10R; max 2 per industry |
| Sizing | 2% of the amount at risk per stock, max 35% of the amount per stock, whole shares, sized at the max-entry price |

Every number lives in `config/WiringConfig.java`.

Known limits: survivorship bias (today's index list), Yahoo is unofficial, no event filter yet (results dates).

## Deploy (Render, free)

Docker web service from this repo; Render sets `PORT`. Heap is capped at 300 MB in the Dockerfile.
