# swing-picker

Give it an amount ("I want to invest ₹10,000 today"), get back NSE swing-trade picks (2–3 week holds) with entry, stop, target and how many shares of each — or an explicit **"No trade today"** with the reason.

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

1. For each stock in `universe.csv`, run each `Setup` on daily bars; keep the ones that fired today.
2. Score each by **expected net R** (`Ranker`), using the setup's historical `Edge` and variable costs in R.
3. Drop scores below `minExpectedNetR`, sort best first, cap picks per sector.
4. `PositionSizer` turns them into whole-share quantities within the amount; fixed per-stock costs decide if a position is too small to be worth it.
5. Nothing survives → "no trade", with which stage eliminated everything.

## Build steps

| Step | Piece | Status |
|---|---|---|
| 1–2 | Setup rules + exit plan (entry/stop/target/max hold) | pending |
| 3 | NSE delivery `CostModel` | pending |
| — | `Ranker`: expected net R | pending |
| 4 | `PositionSizer` | pending |
| 5 | Backtest `EdgeEstimator` | pending |
| 6 | Context/event filters (Nifty trend, results dates) | pending |
| 7 | Monte Carlo replay | pending |
| 8 | Yahoo `.NS` `MarketDataProvider` (adjusted daily bars, 1h cache). Check: `GET /api/bars/RELIANCE` | done |
| 9 | Web page, Render deploy | pending |

## Deploy (Render, free)

Docker web service from this repo; Render sets `PORT`. Heap is capped at 300 MB in the Dockerfile.
