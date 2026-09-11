(ns trade.matching
  "Deterministic matching engine for ONE electricity DELIVERY INTERVAL:
  a pure fold of a totally-ordered order log. Same log in, same book and
  same fills out -- on any runtime, for any third party. An auditor
  holding the public order log re-derives every fill without asking the
  exchange anything.

  ── Provenance: derived from `cryptoexchange.matching` ──

  The price-time priority discipline, the single-sequencer total order,
  the maker price rule, reject-taker self-trade prevention, the explicit
  composite priority keys and the `replay-*` third-party recomputation
  path are all taken from `cloud-itonami-isic-6611-cryptoexchange`'s
  `cryptoexchange.matching` (ADR-2607141200, INV-4/INV-11). That engine
  was read and its design adopted deliberately rather than reinvented.

  It is COPIED-AND-ADAPTED rather than depended on, for two reasons
  recorded in this repo's ADR-0001:

    1. Fleet convention. Actors in this fleet do not share code with
       each other -- they share flat WIRE SHAPES (see `cloud-itonami-
       isic-3512`'s `energy.store` ns docstring on its no-shared-code
       linkage to `cloud-itonami-isic-3510`). Shared code lives in
       `kotoba-lang/*` libraries, not in a sibling actor.

    2. Electricity is not a fungible asset. A crypto fill moves a
       balance and is over. An electricity fill creates a DELIVERY
       OBLIGATION for a specific future half-hour, which is later
       reconciled against a METER READING -- and the difference is
       imbalance, which is where the real money and the real disputes
       are. `cryptoexchange.matching` has no interval, no gate closure
       and no deliver-later semantics, and adding them to it would
       distort an exchange actor that is correct as it stands.

  If a `kotoba-lang/orderbook` library is ever extracted, BOTH should
  move onto it; until then this duplication is deliberate and credited.

  ── Units: exact integers, no rounding branch ──

  `:qty-minor`   watt-hours (Wh), integer.
  `:price-minor` MICRO currency units per watt-hour (e.g. µJPY/Wh),
                 integer.

  A fill's money leg is `(* qty-minor price-minor)` in micro currency
  units -- pure integer arithmetic, no division and therefore no
  rounding rule to litigate, exactly as in the engine this derives
  from. Worked example: 25 JPY/kWh is 25 JPY per 1000 Wh = 25000
  µJPY/Wh; 1500 Wh at that price is 1500 * 25000 = 37,500,000 µJPY =
  37.5 JPY, exactly. `trade.registry` has the human-facing conversions.

  ── Interval scoping ──

  One book per delivery interval. Orders in different intervals NEVER
  match each other: power delivered at 09:00 does not satisfy an
  obligation for 09:30. The `:seq` sequencer is therefore scoped to the
  interval's own book, not global.

  ── Gate closure ──

  Every physical power market stops accepting orders some time before
  delivery, because the network operator needs a final position to
  balance against. `submit` rejects any order once the book is closed.
  Closure is one-way: `close-gate` cannot be undone by this namespace.

  Events (plain data, the public order log for one interval):
    {:kind :order  :seq n :id oid :account a :side :buy|:sell
     :price-minor p :qty-minor q}
    {:kind :cancel :seq n :id oid :account a}
    {:kind :close-gate :seq n}

  `submit`/`cancel-order`/`close-gate`/`replay-book` are the only
  constructors; there is no mutation API.")

(def empty-book
  "A fresh open book for one delivery interval."
  {:last-seq 0 :bids [] :asks [] :gate-closed? false})

;; --------------------------- priority keys ---------------------------

(defn- priority-key
  "Composite [price-rank seq]: lower sorts first on both sides. Explicit
  keys mean determinism never depends on a sort implementation's
  stability."
  [side order]
  [(if (= side :buy)
     (- (:price-minor order))
     (:price-minor order))
   (:seq order)])

(defn- insert-resting
  [orders side order]
  (vec (sort-by #(priority-key side %) (conj orders order))))

;; ----------------------------- crossing ------------------------------

(defn- crosses?
  [side price-minor resting]
  (if (= side :buy)
    (<= (:price-minor resting) price-minor)
    (<= price-minor (:price-minor resting))))

(defn- crossing-region
  "All resting opposite orders the incoming order could reach, in
  priority order."
  [book side price-minor]
  (let [opposite (if (= side :buy) (:asks book) (:bids book))]
    (vec (take-while #(crosses? side price-minor %) opposite))))

;; ---------------------------- validation -----------------------------

(defn- amount? [x] (and (integer? x) (pos? x)))

(defn- find-order [book oid]
  (some #(when (= (:id %) oid) %) (concat (:bids book) (:asks book))))

(defn- validate-order
  [book {:keys [kind seq id account side price-minor qty-minor]}]
  (cond
    (not= kind :order) :unknown-kind
    (:gate-closed? book) :gate-closed
    (not= seq (inc (:last-seq book))) :out-of-order
    (not (and id account
              (or (= side :buy) (= side :sell))
              (amount? price-minor)
              (amount? qty-minor))) :malformed
    (find-order book id) :duplicate-id
    (some #(= (:account %) account)
          (crossing-region book side price-minor)) :self-trade-prevented
    :else nil))

;; ------------------------------ matching -----------------------------

(defn- match-loop
  "Walk the crossing region in priority order, filling at MAKER prices.
  A taker never gets a worse price than it asked; makers get exactly
  what they quoted -- no engine discretion, no selectively-granted
  improvement."
  [region taker-order]
  (loop [region region
         remaining (:qty-minor taker-order)
         fills []
         consumed 0]
    (if (or (= remaining 0) (empty? region))
      {:fills fills :consumed consumed :reduced nil :remaining remaining}
      (let [maker (first region)
            fill-qty (min remaining (:qty-minor maker))
            fill {:maker-id (:id maker)
                  :taker-id (:id taker-order)
                  :price-minor (:price-minor maker)
                  :qty-minor fill-qty
                  :buyer (if (= (:side taker-order) :buy)
                           (:account taker-order)
                           (:account maker))
                  :seller (if (= (:side taker-order) :buy)
                            (:account maker)
                            (:account taker-order))
                  :taker-seq (:seq taker-order)}
            maker-left (- (:qty-minor maker) fill-qty)]
        (if (= 0 maker-left)
          (recur (rest region) (- remaining fill-qty)
                 (conj fills fill) (inc consumed))
          {:fills (conj fills fill)
           :consumed consumed
           :reduced (assoc maker :qty-minor maker-left)
           :remaining 0})))))

(defn submit
  "Apply one `:order` event. {:ok? true :book b' :fills [...]} or
  {:ok? false :reason kw :book book} (book unchanged on failure)."
  [book {:keys [seq side price-minor] :as order}]
  (if-let [reason (validate-order book order)]
    {:ok? false :reason reason :book book}
    (let [region (crossing-region book side price-minor)
          {:keys [fills consumed reduced remaining]} (match-loop region order)
          opposite-key (if (= side :buy) :asks :bids)
          own-key (if (= side :buy) :bids :asks)
          opposite' (vec (concat (if reduced [reduced] [])
                                 (drop (+ consumed (if reduced 1 0))
                                       (get book opposite-key))))
          book' (cond-> (assoc book
                               :last-seq seq
                               opposite-key opposite')
                  (pos? remaining)
                  (update own-key insert-resting side
                          (assoc order :qty-minor remaining)))]
      {:ok? true :book book' :fills fills})))

(defn cancel-order
  "Apply one `:cancel` event. Only the owner may cancel. Cancelling is
  still allowed after gate closure -- withdrawing a position you can no
  longer deliver is the safe direction."
  [book {:keys [kind seq id account]}]
  (cond
    (not= kind :cancel) {:ok? false :reason :unknown-kind :book book}
    (not= seq (inc (:last-seq book))) {:ok? false :reason :out-of-order :book book}
    :else
    (let [order (find-order book id)]
      (cond
        (nil? order) {:ok? false :reason :unknown-order :book book}
        (not= (:account order) account) {:ok? false :reason :not-owner :book book}
        :else
        (let [strip (fn [orders] (vec (remove #(= (:id %) id) orders)))]
          {:ok? true
           :book (-> book
                     (assoc :last-seq seq)
                     (update :bids strip)
                     (update :asks strip))})))))

(defn close-gate
  "Apply one `:close-gate` event. One-way: this namespace has no reopen
  path. Re-closing an already-closed book is rejected rather than
  silently accepted, so the log stays meaningful."
  [book {:keys [kind seq]}]
  (cond
    (not= kind :close-gate) {:ok? false :reason :unknown-kind :book book}
    (:gate-closed? book) {:ok? false :reason :already-closed :book book}
    (not= seq (inc (:last-seq book))) {:ok? false :reason :out-of-order :book book}
    :else {:ok? true :book (assoc book :last-seq seq :gate-closed? true)}))

;; ------------------------------ replay --------------------------------

(defn replay-book
  "Rebuild the book + full fill history for ONE interval from its raw
  event seq, re-validating every event -- the third-party
  recomputation path. Stops at the first invalid event:
  {:ok? false :reason kw :at seq-no ...}.

  `trade.governor` calls this to independently re-derive the book
  before judging a proposed order, so the governor never has to trust
  the advisor's or the store's view of the market."
  [events]
  (reduce (fn [{:keys [book fills]} {:keys [kind] :as event}]
            (let [r (case kind
                      :order (submit book event)
                      :cancel (cancel-order book event)
                      :close-gate (close-gate book event)
                      {:ok? false :reason :unknown-kind :book book})]
              (if (:ok? r)
                {:ok? true :book (:book r)
                 :fills (into fills (:fills r []))}
                (reduced {:ok? false :reason (:reason r)
                          :at (:seq event) :book book :fills fills}))))
          {:ok? true :book empty-book :fills []}
          events))

;; --------------------------- position views ---------------------------

(defn next-seq
  "The `:seq` the next event for this log must carry."
  [events]
  (inc (:last-seq (:book (replay-book events)) 0)))

(defn contracted-sell-wh
  "Total watt-hours `account` has already SOLD in this interval's fills,
  plus everything it still has resting on the ask side. This is the
  quantity a seller is on the hook to actually deliver if nothing else
  changes -- the input to the governor's capacity check."
  [{:keys [book fills]} account]
  (+ (reduce + 0 (map :qty-minor (filter #(= (:seller %) account) fills)))
     (reduce + 0 (map :qty-minor (filter #(= (:account %) account) (:asks book))))))

(defn contracted-buy-wh
  "Total watt-hours `account` has BOUGHT in this interval's fills."
  [{:keys [fills]} account]
  (reduce + 0 (map :qty-minor (filter #(= (:buyer %) account) fills))))

(defn accounts-with-fills
  "Every account that ended up on either side of a fill in this
  interval, sorted. These are exactly the participants a settlement has
  to have a meter reading for."
  [{:keys [fills]}]
  (vec (sort (into #{} (mapcat (juxt :buyer :seller)) fills))))

(defn fill-money-micro
  "One fill's money leg in MICRO currency units: `(* qty price)`. Exact
  integer, no rounding."
  [{:keys [qty-minor price-minor]}]
  (* qty-minor price-minor))
