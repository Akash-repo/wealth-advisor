# Eval fixtures

`vanguard.txt`, `fidelity.txt`, `merrill.txt` are the source of truth for the standard 3-account
eval portfolio — plain text, not PDFs. A test-time helper (PDFBox, already a project dependency)
converts each into real PDF bytes before uploading through the real `/api/portfolio/upload`
endpoint, so the eval always exercises real PDF parsing rather than skipping it. See
`ARCHITECTURE.md`'s eval-design discussion for why the PDF is generated fresh per run instead of
committed as a binary.

## Holdings encoded

| Ticker | Vanguard | Fidelity | Merrill | Total shares |
|---|---|---|---|---|
| GOOGL | 50 @ $95 | 30 @ $110 | 20 @ $130 | 100 |
| JPM | 200 @ $140 | — | — | 200 |
| KO | 150 @ $60 | — | — | 150 |
| AAPL | — | 80 @ $160 | — | 80 |
| MSFT | — | 20 @ $300 | — | 20 |
| AMZN | — | — | 40 @ $140 | 40 |
| NVDA | — | — | 15 @ $100 | 15 |

## Ground truth this fixture is designed to encode

Verified live against real FMP prices — see `ARCHITECTURE.md`'s "Portfolio allocation" section for
the exact hand-checked numbers from the run that validated `getPortfolioAllocation`.

- **JPM is the single largest position** (~43% of total value at time of writing) — this is the
  structural fact the "concentration warning" eval case depends on.
- **Zero healthcare exposure** — no healthcare/pharma ticker anywhere in the fixture — this is the
  structural fact the "diversification opportunity" eval case (asking about JNJ) depends on.
- **Tech-sector concentration**: GOOGL + AAPL + MSFT + NVDA together are comparable in size to JPM
  alone — a secondary concentration fact, useful for the portfolio-risk-analysis case.
- **Also absent**: fixed income/bonds, international exposure — usable as additional "what am I
  missing" gap facts if a case needs more than one.

**Important for writing assertions**: dollar values and exact weight percentages are **not**
stable across eval runs — they depend on live, time-varying stock prices. Assertions should check
either (a) *structural/ordinal* facts that don't move with price (JPM is the largest position;
healthcare exposure is zero), or (b) *self-consistency* (recompute the expected value using
whatever live price the tool itself used, don't hardcode a historical dollar figure). Share counts
(100 GOOGL total, 200 JPM, etc.) **are** stable — those never depend on price.

## Tickers confirmed against FMP's free tier (`/stable/quote`)

Checked directly against the live API, not assumed — FMP's free tier has real, non-obvious gaps
(see `ARCHITECTURE.md` Gotchas):

| Ticker | Status |
|---|---|
| GOOGL, JPM, KO, AAPL, MSFT, AMZN, NVDA, JNJ | ✅ 200 — safe to use in fixtures/cases |
| GOOG (Class C — only GOOGL works), VTI, BND, FXAIX (ETFs/funds), MRK | ❌ 402 — avoid |

`JNJ` is the healthcare ticker used in the diversification-opportunity eval case specifically
because it's confirmed working — don't swap it for an unverified healthcare ticker without
re-checking against the free tier first.
