# trades

Spring Boot REST application for entering option spread trades and integrating
with Tradier.

## Requirements

- Java 25 (LTS) or later
- Maven 3.6.3 or later
- MySQL or MariaDB running on localhost with a `trades` database

## Run

Set `TRADIER_ACCOUNT_ID` and `TRADIER_ACCESS_TOKEN` in the server environment
using credentials for a Tradier sandbox account. Never put credentials in source
control. Start the app:

```powershell
mvn spring-boot:run
```

The application listens on `http://127.0.0.1:8180` and serves the trade-entry
form at `/`. It is bound to localhost and sends orders only to
`https://sandbox.tradier.com`; there is no live-order endpoint or live URL
configuration.
The default datasource connects to `jdbc:mariadb://127.0.0.1:3306/trades` as
`root` with a blank password. The database must exist before startup; the app
creates its tables from `schema.sql`. This local XAMPP setup uses MariaDB, which
is MySQL-protocol compatible. A blank-password root account is for local
development only and should not be exposed to a network.

## Enter and submit a paper trade

The form and REST API support single-leg, vertical, iron condor, butterfly,
calendar, diagonal, double diagonal, and iron butterfly opening trades. The
form offers Bull Call Spread, Bear Call Spread, Bull Put Spread, and Bear Put
Spread; these are sent to the API as vertical structures. Credit-spread shorts
default OTM with the protective long farther OTM. Debit-spread longs default
ITM with the short leg OTM. Bull Put Spread is the initial selection. The form
applies standard structure rules: vertical legs share a type and
expiration; iron-condor shorts straddle the underlying and long wings sit
outside them; butterflies use a centered strike and equal-width wings; calendars
use the same strike across near-short and far-long expirations; diagonals use a
near short and farther-dated, different-strike long; double diagonals use a
near-term short strangle with farther-dated long strikes outside it; and an iron
butterfly uses a shared short center with long outer wings. Strike defaults are
based on the underlying quote and available option chain; strike choices are
restricted to preserve each spread's required strike ordering. The ATM strike is
marked in green with an `ATM` tag only while the dropdown list is open; the
selected strike displays only its price. A quantity multiplier (default 1) scales
all leg quantities automatically as you change it, preserving ratios such as the
butterfly's default 1:2:1; every leg quantity can still be overridden independently.
Expiration selectors are sized to keep the full date and DTE label clear of the
dropdown arrow. Iron-condor wings default to matching widths when
the matching strikes exist; initial iron-condor and iron-butterfly wings widen
beyond the default credit when needed to show defined risk. Editing one side
does not change the other. Changing the spread selection retains compatible
expiration choices. Then choose a limit debit, limit credit, or even order and
price.
The option table uses Call/Put and Buy/Sell buttons when those leg values are
editable, and plain labels when spread rules fix them. Expirations are selected
at the position level and shown as read-only values in the leg rows. Each leg
shows its estimated probability of expiring out of the money ("Prob OTM") after
quantity. Strike choices preserve
the spread's relative strike ordering while allowing in- or out-of-the-money
short legs; short legs default out of the money, and debit-spread long legs
default in the money when available.
The symbol field searches Tradier as you type, highlights the first match, and
uses it when you leave the field; the selected equity description appears
inside the symbol box. Focus starts on the symbol field.
The **Show underlying chart** button loads today's underlying price
bars (15-minute candles by default; a control switches between 1, 5, 15, 30 and
60 minutes) and an estimated volume-at-price profile on the right. The vertical price
range adds 10% of the session high-low span above and below the observed prices,
and hovering over the chart shows the corresponding time. The profile separates
up-candle volume in green from down-candle volume in red and leaves a small gap
between price rows. It assigns each bar's volume to its typical price
(high + low + close) / 3 because Tradier's 1-minute OHLCV data does not include
trade-by-trade volume at each price. Use **Refresh chart** to reload current data.
Available strike selections are loaded from Tradier's sandbox option chain for
the underlying, expiration, and call/put type selected in the form. Expiration
selections are loaded from the sandbox by symbol and display days to expiration
(DTE), including same-day expirations as 0 DTE; past expirations are excluded.
The underlying expiration value remains unchanged. Market-data lookups require
`TRADIER_ACCESS_TOKEN`; previewing and submitting orders also require
`TRADIER_ACCOUNT_ID`.
An iron butterfly uses one shared center strike for the short put and call, with
long put and call wings outside it. The center defaults to the strike nearest
the underlying's sandbox quote and is marked `ATM` in the dropdown. The sandbox
note sits beside the heading to keep the form compact.

The compact order ticket validates leg counts, expirations, strike ordering,
actions, and quantities. Default leg quantities and iron-condor/iron-butterfly
wing widths can be adjusted independently after the defaults are populated.
After defining the legs, choose debit or credit. Position-level Tradier sandbox
quotes refresh automatically after expiration, strike, quantity, or price-type changes;
the expiration graph then uses the updated market-side limit. The UI displays
position bid, ask, mid, and bid/ask spread, along with each leg's bid, ask,
midpoint, open interest, Greeks, and the underlying's IV percentile. The IV
percentile is calculated from a 30-day, near-the-money implied-volatility
estimate, using the closest available expiration to 30 DTE and the nearest
strike; it compares the current estimate with daily samples from the preceding
52 weeks. Samples are stored in the local `trades` MySQL-compatible database in
the `ivDailySamples` table, accessed through the MyBatis mapper in the
`com.trades.persistence` package. Symbols are recorded in `ivTrackedSymbols`
and collected after market close. History begins with the first lookup,
so early percentiles are marked as partial and the column shows collection
progress until enough observations are available. The percentile column shows
the same underlying-level value for every leg. Yahoo Finance's option chain
does not provide the historical implied-volatility series needed for a genuine
IV rank, so the app derives this percentile from its own locally collected
Tradier samples instead of substituting a per-leg IV value. Per-leg IV values
remain available internally for model calculations. Tradier-provided Greeks
are displayed directly; when Tradier omits Greeks or IV, the app estimates
missing values from the option midpoint and underlying price with Black-Scholes
(zero rates and dividends). Insufficient quote data remains unavailable. The
per-leg table appears below the option selectors from startup and updates its
rows as spread legs are added; quote and Greek cells stay blank until data loads.
Long and short rows are shaded green and red. Option names use a readable
underlying, strike, option type, and expiration instead of an OCC symbol. The
table ends with a position-total Greek row once quotes are available. All
displayed Greeks are per-contract values; position totals apply the leg
quantity and long/short direction. The
position bid-to-ask range slider sets the limit in
one-cent increments directly below the editable limit-price field, with bid,
mid, and ask displayed as reference points. It stays visible and becomes
adjustable after quotes load; the range extends by 50% of the bid/ask
spread beyond both sides, while the bid, mid, and ask markers remain at their
actual positions.
Vertical selections choose their natural debit/credit
price type; the limit defaults to the bid for debit orders or the ask for credit
orders. The expiration graph follows the entered limit, shows the underlying
price along the bottom axis using clean $0.50 or whole-dollar tick intervals,
and uses one blue hover box for P/L. It shades
positive P/L green and negative P/L red, and overlays a Black-Scholes theoretical
P/L curve when each leg has an implied volatility. That estimate uses the
remaining time for each leg, zero interest and dividend rates, and current
option midpoint values as the position basis. Calendar, diagonal, and double
diagonal expiration P/L is shown at the short-leg expiration; farther-dated
legs are valued with Black-Scholes and their quoted implied volatility. The
graph waits for those quotes rather than drawing an intrinsic-only vertical.
The summary replaces redundant position Greek cards with estimated
probabilities of profit, maximum loss, and maximum gain at expiration. These
are labeled "Prob Profit", "Prob Max Loss", and "Prob Max Gain:" and are
shown in the order ticket and recalculate when the limit price changes. They
are lognormal estimates using average leg IV. They are unavailable for multi-expiration
positions or when IV cannot be obtained from Tradier or estimated from the
market midpoint. The probability of an unbounded maximum gain or maximum loss
is unavailable.
The estimates ignore skew, rates, fees, and early exercise. Sandbox market
data may be delayed, and quote requests require the same credentials.

Click **Preview order** to request a Tradier sandbox preview. Inspect the broker
response, then use **Confirm and submit paper order** and accept the confirmation
prompt to submit. Preview tickets expire after ten minutes and can only be
submitted once. No order is sent before this explicit confirmation. The REST
API provides the same quote and two-step order flow:

- `POST /api/trades/quote` with symbol, spread type, price type (`DEBIT`,
  `CREDIT`, or `EVEN`), and legs returns per-leg bid/ask quotes and available
  delta, gamma, theta, vega, rho, and mid-IV values, plus calculated position
  bid/ask and aggregate Greeks. Per-leg `greekSource` distinguishes `TRADIER`,
  `MODEL_ESTIMATE`, `PARTIAL`, and `UNAVAILABLE`. Position prices are per
  ratio-unit, independent of the number of matching units. No limit price is
  needed.
- `GET /api/trades/underlying-timesales?symbol=SPY` returns today's 1-minute
  OHLCV bars for the underlying; the UI uses them for the price chart and
  estimated volume profile.
- `POST /api/trades/preview` with a trade JSON body returns a preview ID and
  Tradier's sandbox preview.
- `POST /api/trades/submit` with `{"previewId":"…","environment":"SANDBOX","confirmed":true}`
  submits that exact, unexpired preview to the sandbox.

## Test

```powershell
mvn test
```

Live orders are intentionally unsupported. Sandbox paper orders can still be
rejected or filled using delayed market data; review every order and Tradier
response carefully. Do not expose this local app to an untrusted network.
