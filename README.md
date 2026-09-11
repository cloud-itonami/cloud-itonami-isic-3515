# cloud-itonami-isic-3515

Open Business Blueprint for **ISIC Rev.5 3515: Trade of electricity** --
a peer-to-peer electricity exchange that **anyone can join as a
generator, a consumer, or both**.

ISIC Rev.5 class 3515 covers "sale of electricity to the user,
activities of electric power brokers or agents that arrange the sale of
electricity via power distribution systems operated by others, and
operation of electricity and transmission capacity exchanges for
electric power". This repository publishes that last item -- the
exchange itself -- as an OSS business that any qualified operator can
fork, deploy, run, improve and sell, so a neighbourhood, a cooperative,
a school district or a municipality can let its members trade their own
power with each other instead of renting a closed marketplace.

Built on this workspace's
[`langgraph-clj`](https://github.com/kotoba-lang/langgraph) StateGraph
runtime (portable `.cljc`, supervised superstep loop, interrupts,
Datomic/in-mem checkpoints) -- the same actor pattern as every prior
actor in this fleet. Here it is **Electricity Trade Advisor ⊣ Market
Conduct Governor**.

**Maturity: `:implemented`.** 73 tests / 568 assertions pass; `clj-kondo`
reports 0 errors and 0 warnings; `clojure -M:dev:run` walks a complete
trade end to end and then exercises five distinct regulatory shapes on
four continents. See **Maturity, honestly** below for what
`:implemented` does and does not claim.

## Where this sits in the value chain

This fleet already covers the rest of ISIC group 351. This repository is
the missing **trade** leg:

| repo | ISIC Rev.5 | what it does |
|---|---|---|
| `cloud-itonami-isic-3511` | 3511 production, non-renewable | SMR generation-operator compliance records |
| `cloud-itonami-isic-3512` | 3512 production, renewable | community solar/storage site operations |
| `cloud-itonami-isic-3510` | (Rev.4 numbering) | the wires: transmission & distribution utility |
| **this repo** | **3515 trade of electricity** | **the exchange: who may sell, to whom, at what price, and who owes whom afterwards** |

Generation and wires answer *can this power exist and can it move*. This
actor answers a different question: *may this person sell it, did the
sale clear fairly, and did the power actually show up*.

## What "anyone can generate and trade" actually means here

That phrase is doing real work, and it is worth being precise about
which parts are open and which are gated, because a design that pretended
electricity retail is unregulated would be useless to an operator and
dishonest to a member.

**Open to anyone, no gate:**

- **Registering.** `:participant/register` accepts any person or
  organisation in any role -- `:generator`, `:consumer`, `:prosumer` --
  with any self-declared capacity. It is the one operation that may
  auto-commit when clean. There is no membership committee.
- **Generating.** Nothing in this actor gates the act of producing
  electricity. Every jurisdiction seeded in `trade.facts` treats
  generation as a low-barrier act -- Japan's 発電事業 is a *notification*
  (電気事業法 27条の27①), not a licence -- and below a threshold it is
  usually exempt entirely.
- **Buying.** A buyer needs no supply licence. The licence gate is on
  selling, and the tests assert that a buyer is not caught by it
  (`buying-does-not-require-the-sell-right`).

**Gated, deliberately:**

- **Selling.** Every jurisdiction seeded here draws the same line:
  generating is a notification, *selling electricity to someone else* is
  a licence, an authorisation or a registration the regulator can refuse
  and revoke. `trade.facts` records both sides separately
  (`:generate-basis` / `:sell-basis`) and expresses the resulting rights
  as a set (`:permits #{:generate :sell}`). The governor refuses a SELL
  order from a participant whose *committed* licence basis lacks
  `:sell`. An unknown jurisdiction permits **nothing** -- the default is
  deny, never a permissive fallback.
- **Selling what you cannot make.** A 100 W balcony panel may register
  freely, but it may not sell 2000 Wh into a 30-minute interval, because
  100 W × 30 min = 50 Wh. The governor recomputes that bound from the
  participant's own registered capacity and the interval's own duration.
- **Trading with yourself.** Refused by the matching engine
  (reject-taker self-trade prevention), not by a policy.

So: open door, honest gate. The actor does not pretend supply licensing
away, and it does not use licensing as an excuse to keep small
participants out of the parts that are genuinely open to them.

## What this actor does and does not do

**Does:** admit participants; verify their jurisdiction's market-
participation basis against an official, fetched-and-read source; screen
for market abuse; run a deterministic, replayable order book per delivery
interval; record metered delivery; and reconcile contracted volume
against metered delivery into a settlement record with per-participant
imbalance.

**Does not:** move money, dispatch anything, talk to a TSO/DSO or a
SCADA system, sign anything, or decide what to trade. Every certificate
it produces is an **unsigned draft**; signature is the operator's own
act. Price formation is the deterministic engine's job, from price-time
priority -- never the model's (see below).

## Actuation

Two operations touch the real world, and **neither ever auto-commits at
any phase**:

- `:actuation/place-order` -- admits a financially binding order into a
  real delivery interval, creating a delivery obligation and a payment.
- `:actuation/settle-interval` -- clears an interval and states who owes
  whom.

Both are permanently absent from every phase's `:auto` set in
`trade.phase`, *including* phase 3, and `trade.governor`'s high-stakes
gate escalates them independently. Two layers agree, and neither is
sufficient alone. `test/trade/phase_test.cljk` asserts this across the
whole phase table rather than trusting the prose.

The reason is specific to this domain: **an order book that a language
model may fill on its own initiative is a machine for turning a
hallucination into a delivery obligation and then into someone's
electricity bill.** One human decision, one order.

## Why the advisor never picks a price

`trade.tradeadvisor` normalizes side, quantity and price supplied by the
caller, cites the participant's own licence and capacity facts, and
lowers its confidence when those facts do not support the order. It does
not invent a price, choose what to trade, or select a counterparty --
the book does that, deterministically.

An advisor that chose prices would be a trading algorithm wearing a
compliance actor's clothes, and the containment argument would be void:
you cannot meaningfully govern a model whose output *is* the market
position. Keeping price formation in the engine is what makes the
governor's independent replay a real check rather than a rubber stamp.

## The order log is the market

This actor persists **no materialised order book**. It persists the
totally-ordered order log per interval, and every reader -- the
governor, the settlement computation, an outside auditor -- re-derives
the book with `trade.matching/replay-book`. Same log in, same fills out,
on any runtime.

That is the wash-trading and front-running countermeasure: the exchange
cannot produce an execution it cannot re-derive in public. It also means
there is no cached state an operator could edit to produce a fill the
public log does not imply. Gate closure follows the same rule -- it is a
`:close-gate` **event** in the log, not a mutable flag.

`trade.matching`'s price-time discipline, maker price rule, reject-taker
self-trade prevention and replay path are **derived from
`cloud-itonami-isic-6611-cryptoexchange`'s `cryptoexchange.matching`**
(ADR-2607141200, INV-4/INV-11), copied-and-credited rather than depended
on. See that namespace's docstring and `docs/adr/0001-architecture.md`
for why, and what would change if a shared `kotoba-lang/orderbook`
library were ever extracted.

## Units

Quantities are **watt-hours (Wh)**, integer. Prices are **micro currency
units per watt-hour** (µJPY/Wh), integer. A fill's money leg is
`(* qty price)` -- pure integer multiplication, no division, therefore no
rounding rule to litigate.

25 JPY/kWh is 25000 µJPY/Wh; 2000 Wh at that price is exactly
50,000,000 µJPY = 50 JPY. `trade.registry` has the human-facing
conversions; nothing downstream ever touches a float again.

## The Market Conduct Governor

Nine HARD checks, none of which a human approver can override:

1. **Spec-basis** -- an official, cited source, or nothing.
2. **Evidence incomplete** -- the jurisdiction's full checklist, actually satisfied.
3. **Unlicensed sell** -- the `:sell` right, read from the *committed* verification. An advisor cannot license itself, and `:sell-wholesale` does not satisfy it.
3b. **Currency mismatch** -- the order's currency must equal its book's.
3c. **Cross-border** -- a match across jurisdictions needs a declared basis.
4. **Capacity exceeded** -- recomputed from nameplate capacity, interval duration, and the participant's existing position. Splitting into several orders does not evade it.
5. **Engine rejection** -- the governor **replays the public order log** and asks the matching engine whether it would accept the order. Catches gate closure, sequence breaks, duplicate ids, malformed orders, and self-trading -- as the engine's verdict, not as a policy. *A rule can be argued with; a replay cannot.*
6. **Market-abuse flag unresolved** -- from this proposal or already on file. One flagged participant blocks the whole interval's settlement rather than being quietly settled around.
7. **Unmetered settlement** -- every participant with fills must have a committed meter reading. Settling money against unmeasured delivery is refused structurally: the governor holds, *and* `trade.registry/register-settlement` throws rather than build such a record. Three layers refuse it.

Plus double-settlement prevention, off a dedicated `:settled?` boolean --
never a `:status` value (the discipline informed by
`cloud-itonami-isic-6492`'s status-lifecycle bug, ADR-2607071320).

## Worldwide by construction

Three resolution levels describe any jurisdiction on Earth, and
`trade.facts/resolve-basis` walks them in order, reporting which one
answered:

- **`:subdivision`** — ISO 3166-2 (`USA-CA`, `CAN-ON`, `AUS-WA`). Not an
  edge case: electricity retail is a subnational competence in the
  United States, Canada, Australia and India.
- **`:national`** — ISO 3166-1 alpha-3.
- **`:bloc`** — a supranational instrument binding its members. One
  verified EU citation carries 26 member states that have no national
  entry of their own.

Falling back never invents a right the fallback level lacks: a Vermont
participant resolves to the USA federal entry and still cannot make a
retail sale, because that entry does not carry `:sell`.

Two further things a global exchange needs, which a single-country one
can get away with omitting:

- **Currency.** Every book is denominated in exactly one ISO 4217
  currency, carried by the delivery interval. Prices are bare integers —
  which is what makes the arithmetic exact and what makes the unit
  un-inferable — so an order whose currency differs from its book's is
  refused. Without that rule a JPY ask and a EUR bid cross on their
  integers and produce a fill two orders of magnitude wrong, silently.
  There is no FX here and no intention to add one.
- **Cross-border.** If an order would match against a resting order held
  in a different jurisdiction, the interval must carry an explicit
  `:cross-border-basis`. Power crosses a border because an interconnector
  exists and capacity was allocated, not because two people agreed a
  price. This actor cannot verify that, so it refuses to imply it.

## Jurisdiction coverage (honest)

| | generating | selling | authority |
|---|---|---|---|
| | region | generating | selling | grants `:sell`? |
|---|---|---|---|---|
| **EU** *(bloc, 27 states)* | Europe | Dir. 2018/2001 Art. 21(1) self-consumer right | **Art. 21(2)(a): sell excess production "including through … peer-to-peer trading arrangements"** | ✅ |
| **JPN** | Asia | 発電事業: 届出 (電事法 27条の27①) | 小売電気事業: 登録 (2条の2) | ✅ |
| **IND** | Asia | **de-licensed outright** (Electricity Act 2003 s.7) | trading licence s.12 / s.52 | ✅ |
| **KOR** | Asia | open to IPPs, trade via KPX | licence exists — **never granted except to KEPCO** | ❌ |
| **GBR** | Europe | s.6(1)(a) generation licence | s.6(1)(d) supply licence | ✅ |
| **DEU** | Europe | not gated by § 5 | EnWG § 5 Anzeige to BNetzA | ✅ |
| **USA** | Americas | no federal generation licence as such | FERC market-based rate authority — **wholesale only** | ❌ |
| **BRA** | Americas | Lei 14.300/2022 micro/minigeração | comercialização via ACL/ACR, CCEE | ✅ |
| **CAN** | Americas | provincial | **federal CER has no retail authority; provincial unverified** | ❌ |
| **ZAF** | Africa | **exempt to 100 MW**, registration with NERSA | trading licence under the ERA | ✅ |
| **AUS** | Oceania | AEMO registration | NERL s.88 retailer authorisation **or** exemption | ✅ |

Ten national entries plus the EU bloc — every continent, and 36
jurisdictions reachable in total. Every `:provenance` URL was fetched
and read.

**Three entries deliberately withhold `:sell`, and that is the catalog
working.** A design that could only describe liberalised markets would
be assuming its own conclusion. Korea's retail licence exists in law and
has never been granted to anyone but the incumbent; the USA federal
entry reaches wholesale, not retail; Canada's federal regulator has no
retail authority at all. In each case this actor HARD-holds a
peer-to-peer sale, and says which right *was* on file rather than just
refusing.

**One numeric threshold is encoded in the entire catalog** — South
Africa's 100 MW — and only because the instrument that set it was
identified by number and date (GN 737, Gazette 44989, 12 Aug 2021).
Every other entry has an `:exemption-note` admitting its thresholds
exist and were not read. A threshold you have not read is a fabrication
with a number on it, which is worse than an admitted gap.

Extending coverage is additive: add one map, cite a source you actually
read. The **model** excludes no jurisdiction on Earth — what is
incomplete is the data, and `trade.facts/coverage` says so in those
terms.

## Maturity, honestly

`blueprint.edn` says `:implemented`, which in this fleet means the actor
is real and exercised — not that it is in production. Concretely:

**Real and verified:** the full actor graph; the deterministic,
replayable engine; both store backends passing one contract; the
governor's nine HARD checks; 73 tests / 568 assertions; a demo that
trades, settles, and exercises every failure mode offline.

**Not built:** no money movement (settlement produces an *unsigned draft
record*); no TSO/DSO or SCADA coupling; no real LLM has driven it (the
mock advisor is deterministic by design, and `llm-advisor` exists but is
untested against a live model); no deployment, no UI, no authentication;
capacity is self-declared and unverified against any asset registry.

So: a correct, auditable core with the regulatory and market-conduct
reasoning done, and every external integration still to do. It is ready
to be reasoned about and extended; it is not ready to take anyone's
money.

## Run it

```bash
clojure -M:dev:test         # 73 tests, 568 assertions
clojure -M:lint             # clj-kondo, errors fail CI
clojure -M:dev:run          # a complete trade, then every HARD hold
clojure -M:dev:render-html  # regenerate docs/samples/operator-console.html
```

The demo registers a household prosumer and a nursery school, licenses
and screens both, sells 2000 Wh at 25 JPY/kWh, matches the school's
26 JPY/kWh bid **at the maker's 25**, takes both meter readings (the
seller comes up 50 Wh short), and settles the interval. Then it shows
seven HARD holds that never reach a human at all.

Then it goes worldwide with the same actor and no new code: a Spanish
co-op with no national entry trades on a EUR book under the EU bloc
citation; a Vermont seller is held because the federal right is
`:sell-wholesale`, not `:sell`; a Korean co-op is held because its
jurisdiction grants neither; an Indian collective is licensed under a
regime where generation is de-licensed outright; a JPY book refuses a
EUR order; and a Japan–Spain match is held for want of a declared
cross-border basis. It finishes by replaying the public order log to
re-derive the same book and the same 50.00 JPY fill.

`docs/samples/operator-console.html` is the same actor's output rendered
as an operator console. It is **generated at build time by running the
actor** (`trade.render-html`), not hand-written: every id, price, fill,
money leg, permit set and hold reason on the page was read back out of a
real `trade.operation` run over a freshly seeded store, and the action
gate is derived from `trade.phase`/`trade.governor` rather than described
in prose. It is deterministic — no timestamps, no randomness, no
floating-point formatting — so two consecutive runs are byte-identical
and a regeneration shows up as a real diff or not at all. Alongside the
domestic lifecycle it drives the seeded `iv-xb` interval, which `sim`
never touches, through a complete **cross-border** trade, so the page
shows the cross-border rule permitting a match where the basis is
declared next to it refusing one where it is not.

## Architecture

`docs/adr/0001-architecture.md`. Superproject ADRs: `2608030700`
(the actor), `2608030900` (worldwide coverage).

## Licence

AGPL-3.0-or-later.
