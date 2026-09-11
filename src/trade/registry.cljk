(ns trade.registry
  "Pure-function ORDER-PLACEMENT + INTERVAL-SETTLEMENT record
  construction, plus the physical-reality arithmetic the Market Conduct
  Governor recomputes independently.

  Like every sibling actor's registry, there is no single international
  check-digit standard for an order reference or a settlement reference
  -- every exchange/jurisdiction assigns its own format. This namespace
  does NOT invent one; it builds a jurisdiction-scoped sequence number
  and validates the record's required fields, the same honest, non-
  fabricating discipline `trade.facts` uses.

  This namespace is pure data + pure functions -- no I/O, no network
  call to any real exchange, clearing house, TSO/DSO or payment rail.
  It builds the RECORD an exchange operator would keep, not the act of
  placing the order or moving the money (that is `trade.operation`'s
  `:actuation/place-order`/`:actuation/settle-interval`, always human-
  gated -- see README `Actuation`).

  ── The two pieces of real arithmetic ──

  `deliverable-wh` and `imbalance-wh` are the only places in this actor
  where physics constrains the market, and both are deliberately pure
  so `trade.governor` can recompute them from ground-truth fields
  without trusting any proposal, verdict or stored opinion."
  (:require [kotoba.lang.text :as str]))

;; ----------------------------- units -----------------------------

(def ^:const micro-per-unit
  "Micro currency units per whole currency unit. Prices are integers in
  micro units per watt-hour so a fill's money leg stays exact integer
  multiplication -- see `trade.matching` ns docstring."
  1000000)

(def ^:const wh-per-kwh 1000)

(defn- ->int
  "Portable truncate-to-integer. `long` is JVM-only and `Math/round`
  differs across runtimes, so every integer coercion in this namespace
  goes through here -- this file is `.cljc` and must give the SAME
  answer on JVM and ClojureScript. Truncates toward zero."
  [x]
  #?(:clj  (long x)
     :cljs (if (neg? x) (js/Math.ceil x) (js/Math.floor x))))

(defn kwh-price->minor
  "Human price (whole currency units per kWh, e.g. 25.4 JPY/kWh) ->
  the integer µunit/Wh price the order book uses. Rounds to the nearest
  integer µunit/Wh; the residual is below one millionth of a currency
  unit per watt-hour and cannot accumulate, because every downstream
  computation uses the returned integer and never the float again."
  [per-kwh]
  (->int (+ 0.5 (/ (* (double per-kwh) micro-per-unit) wh-per-kwh))))

(defn minor->kwh-price
  "Inverse of `kwh-price->minor`, for display only. Never feed this back
  into the book."
  [minor-per-wh]
  (/ (double (* minor-per-wh wh-per-kwh)) micro-per-unit))

(defn micro->units
  "Micro currency units -> whole currency units, for display only."
  [micro]
  (/ (double micro) micro-per-unit))

;; ------------------------- physical reality -------------------------

(defn deliverable-wh
  "The MOST a participant with `capacity-w` nameplate generation
  capacity could physically deliver across an interval of
  `duration-minutes`, in watt-hours.

  `(quot (* capacity-w duration-minutes) 60)` -- integer, and truncating
  DOWNWARD is the safe direction: the cap is never rounded up in the
  seller's favour. A participant with no registered capacity (nil, or a
  pure consumer) can deliver nothing, which is the correct answer, not
  a missing-data error.

  This is an UPPER BOUND from nameplate capacity, not a forecast. A
  solar generator at night can deliver far less than this bound allows;
  the bound only refuses the physically impossible. Refusing to sell
  what you cannot possibly produce is the floor, not the ceiling, of
  market conduct."
  [capacity-w duration-minutes]
  (if (and (number? capacity-w) (number? duration-minutes)
           (pos? capacity-w) (pos? duration-minutes))
    (->int (/ (* (->int capacity-w) (->int duration-minutes)) 60))
    0))

(defn capacity-exceeded?
  "Would committing `proposed-wh` more watt-hours put this participant's
  total sold-plus-resting position for the interval above what it could
  physically deliver? Pure ground-truth check -- no proposal
  inspection, no stored verdict."
  [{:keys [capacity-w]} duration-minutes already-contracted-wh proposed-wh]
  (> (+ (or already-contracted-wh 0) (or proposed-wh 0))
     (deliverable-wh capacity-w duration-minutes)))

(defn imbalance-wh
  "Metered actual delivery MINUS contracted delivery, in watt-hours.

  Positive -> the participant delivered MORE than it sold (long).
  Negative -> it delivered LESS than it sold (short); this is the
              expensive direction in every real market, because the
              network operator had to make up the difference.
  Zero     -> balanced.

  Nil metered reading is NOT treated as zero imbalance -- it returns
  nil, and `trade.governor/unmetered-settlement-violations` refuses to
  settle an interval where any participant with fills is unmetered.
  Silently settling an unmetered position as balanced is precisely the
  bug that would let this actor pay out on power nobody measured."
  [metered-wh contracted-wh]
  (when (number? metered-wh)
    (- (->int metered-wh) (->int (or contracted-wh 0)))))

;; ----------------------------- records -----------------------------

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED -- signature is the
  operator's own act, not this actor's. See README `Actuation`."
  [kind subject record-id]
  {"@context" ["https://www.w3.org/ns/credentials/v2"]
   "type" ["VerifiableCredential" kind]
   "credentialSubject" {"id" subject "record" record-id}
   "proof" nil
   "issued_by_registry" false
   "status" "draft-unsigned"})

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

(defn register-order
  "Validate + construct the ORDER-PLACEMENT registration DRAFT -- the
  exchange operator's own act of admitting a real, financially binding
  buy/sell order into a real delivery interval's book. Pure function --
  does not touch any real exchange or payment rail; it builds the
  RECORD an operator would keep.

  `trade.governor` independently replays the interval's public order
  log, re-derives the book, re-checks that the matching engine accepts
  this order, and re-checks the seller's physical capacity, before this
  is ever allowed to commit."
  [participant-id interval-id jurisdiction currency side qty-wh price-minor sequence]
  (when-not (and participant-id (not= participant-id ""))
    (throw (ex-info "order: participant_id required" {})))
  (when-not (and interval-id (not= interval-id ""))
    (throw (ex-info "order: interval_id required" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "order: jurisdiction required" {})))
  ;; A price is a bare integer; without the currency recorded ALONGSIDE
  ;; it the record is unreadable six months later and unauditable in a
  ;; dispute. Refuse to build one rather than emit an ambiguous number.
  (when-not (and (string? currency) (re-matches #"[A-Z]{3}" currency))
    (throw (ex-info "order: currency must be an ISO 4217 alpha-3 code"
                    {:currency currency})))
  (when-not (contains? #{:buy :sell} side)
    (throw (ex-info "order: side must be :buy or :sell" {:side side})))
  (when-not (and (integer? qty-wh) (pos? qty-wh))
    (throw (ex-info "order: qty_wh must be a positive integer" {:qty qty-wh})))
  (when-not (and (integer? price-minor) (pos? price-minor))
    (throw (ex-info "order: price_minor must be a positive integer" {:price price-minor})))
  (when (< sequence 0)
    (throw (ex-info "order: sequence must be >= 0" {})))
  (let [order-number (str (str/upper jurisdiction) "-ORD-" (zero-pad sequence 6))
        record {"record_id" order-number
                "kind" "order-placement-draft"
                "participant_id" participant-id
                "interval_id" interval-id
                "jurisdiction" jurisdiction
                "currency" currency
                "side" (name side)
                "qty_wh" qty-wh
                "price_micro_per_wh" price-minor
                "immutable" true}]
    {"record" record "order_number" order-number
     "certificate" (unsigned-certificate "ElectricityOrder" order-number order-number)}))

(defn register-settlement
  "Validate + construct the INTERVAL-SETTLEMENT registration DRAFT --
  the exchange operator's own act of clearing a delivery interval:
  freezing the fills, reconciling each participant's contracted volume
  against its METERED actual delivery, and stating the resulting
  imbalance. Pure function -- does not touch any real payment rail; it
  builds the RECORD an operator would keep.

  `positions` is a vector of
  {:participant-id .. :contracted-wh n :metered-wh n :imbalance-wh n
   :money-micro n}, already computed by the caller from the replayed
  order log and the committed meter readings. This function does not
  recompute them -- it validates that every position actually carries a
  metered figure, because a settlement over an unmetered position is
  the one thing this record must never be able to represent."
  [interval-id jurisdiction positions sequence]
  (when-not (and interval-id (not= interval-id ""))
    (throw (ex-info "settlement: interval_id required" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "settlement: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "settlement: sequence must be >= 0" {})))
  (when (some #(nil? (:metered-wh %)) positions)
    (throw (ex-info "settlement: every position must carry a metered reading"
                    {:unmetered (mapv :participant-id
                                      (filter #(nil? (:metered-wh %)) positions))})))
  (let [settlement-number (str (str/upper jurisdiction) "-STL-" (zero-pad sequence 6))
        record {"record_id" settlement-number
                "kind" "interval-settlement-draft"
                "interval_id" interval-id
                "jurisdiction" jurisdiction
                "positions" (mapv (fn [p]
                                    {"participant_id" (:participant-id p)
                                     "contracted_wh" (:contracted-wh p)
                                     "metered_wh" (:metered-wh p)
                                     "imbalance_wh" (:imbalance-wh p)
                                     "money_micro" (:money-micro p)})
                                  positions)
                "immutable" true}]
    {"record" record "settlement_number" settlement-number
     "certificate" (unsigned-certificate "IntervalSettlement" settlement-number settlement-number)}))

(defn append [history result]
  (conj (vec history) (get result "record")))
