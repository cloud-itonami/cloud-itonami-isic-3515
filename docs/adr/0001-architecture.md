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
`trade.governor/high-stakes`. `test/trade/phase_test.cljk` asserts it
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

- **Ten national entries plus one bloc**, reaching 36 jurisdictions —
  not 194. Coverage is reported honestly by `trade.facts/coverage`, in
  terms of a data gap rather than a scope limit.
- **Exactly one exemption threshold is encoded** (ZAF's 100 MW), because
  the instrument that set it was identified by number and date. Every
  other entry admits its thresholds exist and were not read. An
  unverified threshold is a fabrication with a number on it.
- **USA is wholesale-only** and **CAN is federal-only**; both withhold
  `:sell`. A retail peer-to-peer sale in either has no spec-basis until
  a subdivision entry is seeded.
- **KOR rests on secondary sources** (practitioner guides, not the
  Electricity Business Act itself), which its `:verification-note` says.
  It is seeded anyway because the finding is conservative — it withholds
  a right rather than granting one — and because omitting Korea would
  misrepresent the world as uniformly liberalised.
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

## Amendment, 2026-08-03 — worldwide coverage (D9–D12)

The first iteration seeded four jurisdictions and, more importantly,
carried two assumptions that only a single-country catalog can hide.
Superproject ADR-2608030900 records the expansion; the decisions are
summarised here because they change this repository's own architecture.

### D9. Three resolution levels, walked in order

`resolve-basis` walks **subdivision (ISO 3166-2) → national (ISO 3166-1
alpha-3) → bloc**, and reports which level answered in `:resolved-at`.
Subdivision is not an edge case: electricity retail is a subnational
competence in the United States, Canada, Australia and India. The bloc
level lets one verified EU citation carry 26 member states that have no
national entry.

Fallback must never invent a right the fallback level lacks. A Vermont
participant resolves to the USA federal entry and still cannot make a
retail sale, because that entry does not carry `:sell`.

### D10. Named rights, and `:sell-wholesale` ≠ `:sell`

`:permits` gained `:sell-wholesale`. FERC's market-based rate authority
is real and worth recording, but it is authority over *wholesale* sales;
retail is a state competence nobody verified. Collapsing the two into a
single `:sell` would have made the United States' entry authorise
exactly the transaction it does not cover — the most consequential
fabrication available in the file.

Three entries now deliberately withhold `:sell` (USA federal, CAN
federal, KOR). Korea is the load-bearing one: the retail licence exists
in law and has never been granted to anyone but the incumbent. **A
catalog that could only describe liberalised markets would be assuming
its own conclusion**, so the model has to be able to say "not here".

### D11. Currency, because bare integers have no unit

A book is denominated in exactly one ISO 4217 currency, carried by the
delivery interval, and an order in another currency is HARD-held.

This was a real defect, not a missing feature. Prices are bare integers
— which is precisely what makes the money arithmetic exact (D4) and what
makes the unit un-inferable. A JPY ask and a EUR bid would have crossed
on their integers and produced a fill roughly two orders of magnitude
wrong, silently, with a correct-looking audit trail. `register-order`
now also refuses to build a record without the currency recorded
alongside the price: a bare number is unauditable in the dispute the
record exists for.

No FX, and none intended. Cross-currency trade is a different product
with different risk.

### D12. Cross-border matches must be declared, not inferred

If an order would match against a resting order held in a different
jurisdiction, the interval must carry an explicit `:cross-border-basis`.
Checked at placement rather than at fill, because by the time the engine
has produced a fill the obligation already exists.

Power crosses a border because an interconnector exists and capacity was
allocated, not because two people agreed a price. This actor cannot
verify any of that, so it refuses to *imply* it. The rule does not
forbid the trade — a declared basis permits it.

### What did not change

The engine, the log-as-only-market-state discipline, the integer money
arithmetic, the two permanently-non-auto actuations, and the
advisor-never-picks-a-price rule are all untouched. Going worldwide
added dimensions to the *facts* and two boundary checks to the
*governor*; it did not require relaxing a single invariant.

## Verification

`clojure -M:dev:test` — 73 tests, **568 assertions, 0 failures, 0
errors**. `clojure -M:lint` — **0 errors, 0 warnings**.
`clojure -M:dev:run` walks a complete trade (2000 Wh at the maker's
25 JPY/kWh = 50.00 JPY exactly, seller 50 Wh short at the meter) and then
every HARD hold.

`store_contract_test` runs the full contract against **both** MemStore
and the Datomic-backed store, including order-log ordering parity — a
backend that reordered the log would produce different fills from the
same trades, so that parity is load-bearing rather than cosmetic.
