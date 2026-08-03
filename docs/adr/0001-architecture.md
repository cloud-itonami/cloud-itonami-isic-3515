# ADR-0001 — Architecture of the ISIC 3515 electricity-trade actor

**Status:** accepted (2026-08-03)
**Superproject ADR:** `2608030700`

## Context

The `cloud-itonami` fleet already covered electricity *generation*
(`isic-3511` non-renewable, `isic-3512` community renewables) and the
*wires* (`isic-3510` transmission & distribution). An audit of the fleet
found no trade leg at all: nothing in the workspace modelled quantity,
price, counterparty, or an order.

`isic-3512` was the closest thing, and it is instructive that it was not
close. It has an `:actuation/finalize-settlement` operation, but its
store schema carries no watt-hours, no price and no counterparty — its
"settlement" is a compliance record with a reference number. The verb was
there; the market was not.

ISIC Rev.5 assigns a class to exactly this gap. Group 351 is
`3511` production non-renewable / `3512` production renewable / `3513`
transmission / `3514` distribution / **`3515` trade of electricity** /
`3516` storage, and 3515's own scope note names "sale of electricity to
the user", "electric power brokers or agents that arrange the sale of
electricity via power distribution systems operated by others", and
"operation of electricity and transmission capacity exchanges".

## Decision

Build the exchange as a standard fleet actor — **Electricity Trade
Advisor ⊣ Market Conduct Governor** on `langgraph-clj`, with the
`facts`/`registry`/`phase`/`governor`/`advisor`/`store`/`operation`
namespace shape every sibling uses — and add exactly what electricity
trading needs on top.

### D1. Use the real class number, 3515, not a role suffix

`cloud-itonami-isic-3512-p2ptrading` was considered and rejected. Role
suffixes (as in `cloud-itonami-isic-6611-cryptoexchange`) are for
satellites of a class that already fits. Here the class fits exactly,
and using it keeps the fleet's ISIC index meaningful.

**Noted, not fixed:** `cloud-itonami-isic-3510` claims "ISIC Rev.5 3510",
but 3510 is a Rev.4 code — in Rev.5 that repo's content (a distribution
utility) is `3514`. `isic-3512`'s README likewise mislabels its class as
"transmission and distribution" when Rev.5 3512 is production from
renewable sources, which is what the repo actually implements. Renaming
a live repo is a separate, riskier change with its own manifest and
redirect consequences; it is recorded here so a later session finds it
deliberately, not by surprise.

### D2. The order log is the only market state

No materialised book is persisted. The store holds the totally-ordered
order log per interval; the governor, the settlement computation and any
third party re-derive the book with `trade.matching/replay-book`.

This is what makes the governor's check 5 possible at all: it does not
hold an opinion about whether an order is admissible, it **re-runs the
deterministic engine over the public log**. Wash trading dies at the
engine (reject-taker self-trade prevention) and the governor merely
surfaces the engine's own `:reason`.

Gate closure obeys the same rule — a `:close-gate` event in the log, not
a mutable flag on the interval entity — so there is exactly one source of
truth for whether a market is open.

### D3. Derive the matching engine from `cryptoexchange.matching`; do not depend on it

`cloud-itonami-isic-6611-cryptoexchange` already has a correct
deterministic engine: price-time priority via explicit composite keys,
single-sequencer total order, maker price rule, reject-taker self-trade
prevention, third-party replay. That design was read and adopted rather
than reinvented.

It is **copied and credited** rather than depended on, for two reasons:

1. **Fleet convention.** Actors here do not share code with each other —
   they share flat wire shapes (see `isic-3512`'s store docstring on its
   no-shared-code linkage to `isic-3510`). Shared code lives in
   `kotoba-lang/*` libraries.
2. **Electricity is not a fungible asset.** A crypto fill moves a balance
   and is done. An electricity fill creates a **delivery obligation for a
   specific future half-hour**, reconciled later against a **meter
   reading**, and the difference is imbalance — which is where the real
   money and the real disputes live. `cryptoexchange.matching` has no
   interval, no gate closure and no deliver-later semantics, and bolting
   them on would distort an exchange actor that is correct as it stands.

If a `kotoba-lang/orderbook` library is ever extracted, both should move
onto it. Until then the duplication is deliberate and credited in the
namespace docstring.

### D4. Integer micro-units per watt-hour, so money never rounds

Quantities are watt-hours; prices are micro currency units per watt-hour.
A fill's money leg is `(* qty price)` — integer multiplication, no
division, no rounding rule to argue about in a dispute. Human-facing
conversions live in `trade.registry` and their output never re-enters the
book.

The alternative (price per kWh, quantity in Wh) forces a division by 1000
on every fill and a rounding policy that would itself need governing.

### D5. Two actuations, permanently outside every `:auto` set

`:actuation/place-order` and `:actuation/settle-interval` never
auto-commit, at any phase, including phase 3. Enforced twice
independently: absent from every `:auto` set in `trade.phase`, and in
`trade.governor/high-stakes`. `test/trade/phase_test.clj` asserts it
across the whole phase table.

An order book a model may fill on its own initiative is a machine for
turning a hallucination into a delivery obligation and then into
someone's electricity bill.

### D6. The advisor never picks a price

Side, quantity and price come from the caller. The advisor normalizes
them and cites the participant's own facts; it does not invent a price,
choose what to trade, or select a counterparty. Price formation belongs
to the deterministic engine.

An advisor that chose prices would be a trading algorithm wearing a
compliance actor's clothes: the containment argument collapses when the
model's output *is* the market position, because the governor would be
reviewing the very judgement it exists to be independent of.

### D7. `:permits` as a set — the open-door / honest-gate split

The requirement "anyone can generate and trade" is implemented as a
deliberate asymmetry, because that asymmetry is what every seeded
jurisdiction actually legislates:

- **Registering, generating and buying are open.** Registration accepts
  any role and any self-declared capacity and is the single auto-eligible
  operation. Nothing gates generation. Buyers need no supply licence
  (asserted by `buying-does-not-require-the-sell-right`).
- **Selling is gated** — by the jurisdiction's own rule, read from a
  committed verification, never from the proposal.

`trade.facts` therefore records `:generate-basis` and `:sell-basis`
separately and yields `:permits` as a **set**, not a boolean. An unknown
jurisdiction yields `#{}` — deny by default.

The physical bound is the second gate: `trade.registry/deliverable-wh`
truncates downward (never rounded up in the seller's favour), and the
governor counts the participant's existing position so the bound cannot
be evaded by splitting orders.

### D8. Nothing settles against an unmeasured meter

Three independent layers refuse it: the governor holds HARD when any
participant with fills lacks a committed reading;
`trade.registry/register-settlement` throws rather than construct such a
record; and `trade.registry/imbalance-wh` returns `nil` for a missing
reading rather than the convenient `0` that would silently settle it as
balanced.

An interval with no fills settles to an empty position list — correctly,
since there is nothing to measure.

## Consequences

**Good.** The fleet's ISIC 351 coverage is complete. Any third party
holding the public order log re-derives every fill. The exchange cannot
produce an execution it cannot justify publicly. Small participants can
register and generate without a gatekeeper, and the one thing that is
gated is gated for a reason a regulator would recognise.

**Costs and limits, stated plainly.**

- **Four jurisdictions**, not 194. Coverage is reported honestly by
  `trade.facts/coverage`.
- **No exemption thresholds are encoded**, though every seeded
  jurisdiction has them. None were verified, and an unverified threshold
  is a fabrication with a number on it.
- **USA is wholesale-only.** FERC's market-based rate authority is what
  was verified; no state retail regime was. A retail peer-to-peer sale
  inside one US state has no spec-basis here.
- **Nameplate capacity is an upper bound, not a forecast.** A solar
  generator at night can deliver far less than the bound allows. The bound
  refuses only the physically impossible; it is the floor of market
  conduct, not the ceiling.
- **Self-declared capacity.** Nothing here verifies that a participant's
  registered capacity is real. That is a job for asset registration
  (Marktstammdatenregister, 発電事業届出) which this actor records as
  evidence but does not independently confirm.
- **No money movement, no grid coupling.** Settlement produces an
  unsigned draft record. Payment rails, imbalance pricing, and the
  TSO/DSO interface are out of scope for R0.
- **The matching engine is duplicated** with `isic-6611-cryptoexchange`.
  Accepted deliberately (D3); revisit if a shared library is extracted.

## Verification

`clojure -M:dev:test` — 57 tests, **368 assertions, 0 failures, 0
errors**. `clojure -M:lint` — **0 errors, 0 warnings**.
`clojure -M:dev:run` walks a complete trade (2000 Wh at the maker's
25 JPY/kWh = 50.00 JPY exactly, seller 50 Wh short at the meter) and then
every HARD hold.

`store_contract_test` runs the full contract against **both** MemStore
and the Datomic-backed store, including order-log ordering parity — a
backend that reordered the log would produce different fills from the
same trades, so that parity is load-bearing rather than cosmetic.
