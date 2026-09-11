(ns trade.governor-contract-test
  "The Market Conduct Governor's contract, exercised through the WHOLE
  actor graph (advisor -> governor -> phase gate -> commit/hold), not
  against the governor in isolation. What matters is not that
  `governor/check` returns a map, but that a bad proposal cannot reach
  the SSoT no matter who approves it."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [trade.governor :as governor]
            [trade.matching :as matching]
            [trade.operation :as op]
            [trade.registry :as registry]
            [trade.store :as store]))

(def operator {:actor-id "op-1" :actor-role :exchange-operator :phase 3})

(def ^:private ask (registry/kwh-price->minor 25))
(def ^:private bid (registry/kwh-price->minor 26))

(defn- exec! [actor tid request]
  (g/run* actor {:request request :context operator} {:thread-id tid}))

(defn- disp
  "The disposition a graph run settled on. `g/run*` returns
  {:status .. :state ..}; an escalated run comes back `:interrupted`
  with `:escalate` already in state, paused before `:request-approval`."
  [res]
  (get-in res [:state :disposition]))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn- run-approved! [actor tid request]
  (exec! actor tid request)
  (approve! actor tid))

(defn- holds [db]
  (filter #(= :hold (:disposition %)) (store/ledger db)))

(defn- last-hold-basis [db]
  (set (:basis (last (holds db)))))

(defn- fixture
  "A store + actor with p-1 (6000 W prosumer) and p-2 (consumer) both
  licensed and screened clean -- the state every trading test starts
  from."
  []
  (let [db (store/seed-db)
        actor (op/build db)]
    (doseq [pid ["p-1" "p-2"]]
      (run-approved! actor (str "lic-" pid) {:op :license/verify :subject pid})
      (run-approved! actor (str "scr-" pid) {:op :conduct/screen :subject pid}))
    [db actor]))

;; ---------------------------- happy path ----------------------------

(deftest a-complete-trade-settles
  (let [[db actor] (fixture)]
    (testing "the seller's order is admitted only after a human approves"
      (let [r (exec! actor "t1" {:op :actuation/place-order :subject "p-1"
                                 :interval-id "iv-1" :order-id "o-1" :side :sell
                                 :qty-wh 2000 :price-minor ask :currency "JPY"})]
        (is (= :escalate (disp r)) "actuation ALWAYS escalates")
        (is (empty? (store/order-log db "iv-1")) "nothing is written before approval"))
      (approve! actor "t1")
      (is (= 1 (count (store/order-log db "iv-1")))))

    (testing "the buyer crosses and the book fills at the MAKER's price"
      (run-approved! actor "t2" {:op :actuation/place-order :subject "p-2"
                                 :interval-id "iv-1" :order-id "o-2" :side :buy
                                 :qty-wh 2000 :price-minor bid :currency "JPY"})
      (let [{:keys [fills]} (matching/replay-book (store/order-log db "iv-1"))]
        (is (= 1 (count fills)))
        (is (= ask (:price-minor (first fills))) "buyer bid 26 JPY/kWh, pays 25")
        (is (= "p-2" (:buyer (first fills))))
        (is (= "p-1" (:seller (first fills))))))

    (testing "settlement reconciles contracted volume against METERED delivery"
      (run-approved! actor "m1" {:op :meter/submit :subject "iv-1"
                                 :participant-id "p-1" :metered-wh 1950})
      (run-approved! actor "m2" {:op :meter/submit :subject "iv-1"
                                 :participant-id "p-2" :metered-wh -2000})
      (run-approved! actor "s1" {:op :actuation/settle-interval :subject "iv-1"})
      (let [rec (first (store/settlement-history db))
            pos (into {} (map (juxt #(get % "participant_id") identity)
                              (get rec "positions")))]
        (is (= "JPN-STL-000000" (get rec "record_id")))
        (is (true? (:settled? (store/interval db "iv-1"))))
        (is (= 2000 (get-in pos ["p-1" "contracted_wh"])))
        (is (= 1950 (get-in pos ["p-1" "metered_wh"])))
        (is (= -50 (get-in pos ["p-1" "imbalance_wh"]))
            "the seller delivered 50 Wh less than it sold: short")
        (is (= 50000000 (get-in pos ["p-1" "money_micro"])) "2000 Wh * 25000 = 50 JPY")
        (is (= -2000 (get-in pos ["p-2" "contracted_wh"])) "the buyer's obligation is negative")
        (is (= -50000000 (get-in pos ["p-2" "money_micro"])))))))

;; ------------------------- HARD violations --------------------------

(deftest no-spec-basis-is-a-hard-hold
  (let [db (store/seed-db)
        actor (op/build db)
        r (exec! actor "t1" {:op :license/verify :subject "p-3" :no-spec? true})]
    (is (= :hold (disp r)))
    (is (contains? (last-hold-basis db) :no-spec-basis))
    (is (nil? (store/licence-of db "p-3")) "nothing was committed")))

(deftest selling-without-the-sell-right-is-a-hard-hold
  (testing "anyone may register and generate; SELLING is what the jurisdiction gates"
    (let [db (store/seed-db)
          actor (op/build db)
          r (exec! actor "t1" {:op :actuation/place-order :subject "p-3"
                               :interval-id "iv-1" :order-id "o-1" :side :sell
                               :qty-wh 1000 :price-minor ask :currency "JPY"})]
      (is (= :hold (disp r)))
      (is (contains? (last-hold-basis db) :unlicensed-sell))
      (is (empty? (store/order-log db "iv-1"))))))

(deftest buying-does-not-require-the-sell-right
  (testing "the licence gate is on selling only -- a buyer is not blocked by it"
    (let [db (store/seed-db)
          actor (op/build db)]
      (run-approved! actor "lic" {:op :license/verify :subject "p-2"})
      (let [r (exec! actor "t1" {:op :actuation/place-order :subject "p-2"
                                 :interval-id "iv-1" :order-id "o-1" :side :buy
                                 :qty-wh 1000 :price-minor bid :currency "JPY"})]
        (is (= :escalate (disp r)) "escalates for approval, not held")))))

(deftest offering-more-than-you-can-physically-generate-is-a-hard-hold
  (let [db (store/seed-db)
        actor (op/build db)]
    (run-approved! actor "lic" {:op :license/verify :subject "p-5"})
    (testing "100 W across a 30-minute interval is 50 Wh, not 2000"
      (is (= 50 (registry/deliverable-wh 100 30)))
      (let [r (exec! actor "t1" {:op :actuation/place-order :subject "p-5"
                                 :interval-id "iv-1" :order-id "o-1" :side :sell
                                 :qty-wh 2000 :price-minor ask :currency "JPY"})]
        (is (= :hold (disp r)))
        (is (contains? (last-hold-basis db) :capacity-exceeded))))
    (testing "but a quantity within the physical bound is allowed through to a human"
      (let [r (exec! actor "t2" {:op :actuation/place-order :subject "p-5"
                                 :interval-id "iv-1" :order-id "o-2" :side :sell
                                 :qty-wh 50 :price-minor ask :currency "JPY"})]
        (is (= :escalate (disp r)))))))

(deftest capacity-check-counts-the-existing-position
  (testing "a seller cannot evade the bound by splitting into several orders"
    (let [[db actor] (fixture)]
      (run-approved! actor "t1" {:op :actuation/place-order :subject "p-1"
                                 :interval-id "iv-1" :order-id "o-1" :side :sell
                                 :qty-wh 3000 :price-minor ask :currency "JPY"})
      (let [r (exec! actor "t2" {:op :actuation/place-order :subject "p-1"
                                 :interval-id "iv-1" :order-id "o-2" :side :sell
                                 :qty-wh 1 :price-minor ask :currency "JPY"})]
        (is (= :hold (disp r)) "6000 W * 30 min = 3000 Wh is already committed")
        (is (contains? (last-hold-basis db) :capacity-exceeded))))))

(deftest self-trading-is-refused-by-the-engine-and-surfaced-by-the-governor
  (let [[db actor] (fixture)]
    (run-approved! actor "t1" {:op :actuation/place-order :subject "p-1"
                               :interval-id "iv-1" :order-id "o-1" :side :sell
                               :qty-wh 1000 :price-minor ask :currency "JPY"})
    (let [r (exec! actor "t2" {:op :actuation/place-order :subject "p-1"
                               :interval-id "iv-1" :order-id "o-2" :side :buy
                               :qty-wh 1000 :price-minor bid :currency "JPY"})]
      (is (= :hold (disp r)))
      (is (contains? (last-hold-basis db) :engine-rejected)
          "wash trading dies at the engine, not at a policy the governor could be argued out of"))))

(deftest trading-through-a-closed-gate-is-a-hard-hold
  (let [[db actor] (fixture)]
    (is (true? (:gate-closed? (:book (matching/replay-book (store/order-log db "iv-2")))))
        "iv-2 is seeded closed, as an EVENT in its log")
    (let [r (exec! actor "t1" {:op :actuation/place-order :subject "p-1"
                               :interval-id "iv-2" :order-id "o-1" :side :sell
                               :qty-wh 100 :price-minor ask :currency "JPY"})]
      (is (= :hold (disp r)))
      (is (contains? (last-hold-basis db) :engine-rejected)))))

(deftest duplicate-order-ids-are-a-hard-hold
  (let [[db actor] (fixture)]
    (run-approved! actor "t1" {:op :actuation/place-order :subject "p-1"
                               :interval-id "iv-1" :order-id "o-1" :side :sell
                               :qty-wh 1000 :price-minor ask :currency "JPY"})
    (let [r (exec! actor "t2" {:op :actuation/place-order :subject "p-1"
                               :interval-id "iv-1" :order-id "o-1" :side :sell
                               :qty-wh 500 :price-minor ask :currency "JPY"})]
      (is (= :hold (disp r)))
      (is (contains? (last-hold-basis db) :engine-rejected)))))

(deftest unresolved-market-abuse-flag-is-a-hard-hold
  (testing "the screening op HARD-holds on its own finding"
    (let [db (store/seed-db)
          actor (op/build db)
          r (exec! actor "t1" {:op :conduct/screen :subject "p-4"})]
      (is (= :hold (disp r)))
      (is (contains? (last-hold-basis db) :market-abuse-flag-unresolved))
      (is (nil? (store/conduct-screen-of db "p-4")) "the finding is not committed as a verdict"))))

(deftest settling-over-an-unmetered-participant-is-a-hard-hold
  (let [[db actor] (fixture)]
    (run-approved! actor "t1" {:op :actuation/place-order :subject "p-1"
                               :interval-id "iv-1" :order-id "o-1" :side :sell
                               :qty-wh 2000 :price-minor ask :currency "JPY"})
    (run-approved! actor "t2" {:op :actuation/place-order :subject "p-2"
                               :interval-id "iv-1" :order-id "o-2" :side :buy
                               :qty-wh 2000 :price-minor bid :currency "JPY"})
    (testing "only ONE of the two counterparties submits a reading"
      (run-approved! actor "m1" {:op :meter/submit :subject "iv-1"
                                 :participant-id "p-1" :metered-wh 2000})
      (let [r (exec! actor "s1" {:op :actuation/settle-interval :subject "iv-1"})]
        (is (= :hold (disp r)))
        (is (contains? (last-hold-basis db) :unmetered-settlement))
        (is (empty? (store/settlement-history db)))
        (is (false? (:settled? (store/interval db "iv-1"))))))))

(deftest an-interval-with-no-fills-settles-to-nothing
  (let [[_ actor] (fixture)
        r (exec! actor "s1" {:op :actuation/settle-interval :subject "iv-1"})]
    (is (= :escalate (disp r))
        "no fills means no unmetered participants, so it reaches a human normally")))

(deftest double-settlement-is-a-hard-hold
  (let [[db actor] (fixture)]
    (run-approved! actor "s1" {:op :actuation/settle-interval :subject "iv-1"})
    (is (true? (:settled? (store/interval db "iv-1"))))
    (let [r (exec! actor "s2" {:op :actuation/settle-interval :subject "iv-1"})]
      (is (= :hold (disp r)))
      (is (contains? (last-hold-basis db) :already-settled)))))

;; ------------------ the approver cannot override HARD ------------------

(deftest a-human-cannot-approve-past-a-hard-violation
  (testing "HARD holds never reach the approval node at all -- there is nothing to approve"
    (let [db (store/seed-db)
          actor (op/build db)]
      (exec! actor "t1" {:op :actuation/place-order :subject "p-3"
                         :interval-id "iv-1" :order-id "o-1" :side :sell
                         :qty-wh 1000 :price-minor ask :currency "JPY"})
      (approve! actor "t1")
      (is (empty? (store/order-log db "iv-1")))
      (is (empty? (store/order-history db))))))

(deftest an-approver-rejection-holds-and-is-logged
  (let [[db actor] (fixture)]
    (exec! actor "t1" {:op :actuation/place-order :subject "p-1"
                       :interval-id "iv-1" :order-id "o-1" :side :sell
                       :qty-wh 1000 :price-minor ask :currency "JPY"})
    (g/run* actor {:approval {:status :rejected :by "op-1"}}
            {:thread-id "t1" :resume? true})
    (is (empty? (store/order-log db "iv-1")))
    (is (contains? (last-hold-basis db) :approver-rejected))))

;; ---------------------------- unit surface ----------------------------

(deftest high-stakes-is-exactly-the-two-actuations
  (is (= #{:actuation/place-order :actuation/settle-interval} governor/high-stakes)))

(deftest clean-low-confidence-proposals-escalate-rather-than-commit
  (let [db (store/seed-db)
        v (governor/check {:op :participant/register :subject "p-1"} {}
                          {:cites [:x] :confidence 0.1 :effect :participant/upsert}
                          db)]
    (is (false? (:ok? v)))
    (is (true? (:escalate? v)))
    (is (false? (:hard? v)))))

;; ─────────────── global model: currency, cross-border, rights ───────────────

(deftest an-order-in-the-wrong-currency-is-a-hard-hold
  (testing "without this rule a JPY ask and a EUR bid cross on their bare integers"
    (let [[db actor] (fixture)
          r (exec! actor "t1" {:op :actuation/place-order :subject "p-1"
                               :interval-id "iv-1" :order-id "o-1" :side :sell
                               :qty-wh 1000 :price-minor ask :currency "EUR"})]
      (is (= :hold (disp r)))
      (is (contains? (last-hold-basis db) :currency-mismatch))
      (is (empty? (store/order-log db "iv-1"))))))

(deftest each-book-is-single-currency
  (let [db (store/seed-db)]
    (is (= "JPY" (:currency (store/interval db "iv-1"))))
    (is (= "EUR" (:currency (store/interval db "iv-eu"))))
    (testing "the same integer price means a different amount of money in each"
      (is (not= (:currency (store/interval db "iv-1"))
                (:currency (store/interval db "iv-eu")))))))

(deftest a-cross-border-match-without-a-declared-basis-is-a-hard-hold
  (testing "power crosses a border because an interconnector exists, not because two people agreed a price"
    (let [db (store/seed-db)
          actor (op/build db)]
      ;; a Spanish seller rests an ask on the EUR book
      (run-approved! actor "lic-6" {:op :license/verify :subject "p-6"})
      (run-approved! actor "scr-6" {:op :conduct/screen :subject "p-6"})
      (run-approved! actor "t1" {:op :actuation/place-order :subject "p-6"
                                 :interval-id "iv-eu" :order-id "o-1" :side :sell
                                 :qty-wh 2000 :price-minor ask :currency "EUR"})
      (is (= 1 (count (store/order-log db "iv-eu"))))
      ;; a Japanese buyer tries to cross it -- different jurisdiction, no basis
      (run-approved! actor "lic-1" {:op :license/verify :subject "p-1"})
      (run-approved! actor "scr-1" {:op :conduct/screen :subject "p-1"})
      (let [r (exec! actor "t2" {:op :actuation/place-order :subject "p-1"
                                 :interval-id "iv-eu" :order-id "o-2" :side :buy
                                 :qty-wh 2000 :price-minor bid :currency "EUR"})]
        (is (= :hold (disp r)))
        (is (contains? (last-hold-basis db) :cross-border-without-basis))
        (is (= 1 (count (store/order-log db "iv-eu"))) "the cross never happened")))))

(deftest a-declared-cross-border-basis-permits-the-match
  (testing "the rule refuses to INFER a basis; it does not refuse the trade outright"
    (let [db (store/seed-db)
          actor (op/build db)]
      (doseq [pid ["p-6" "p-1"]]
        (run-approved! actor (str "lic-" pid) {:op :license/verify :subject pid})
        (run-approved! actor (str "scr-" pid) {:op :conduct/screen :subject pid}))
      (run-approved! actor "t1" {:op :actuation/place-order :subject "p-6"
                                 :interval-id "iv-xb" :order-id "o-1" :side :sell
                                 :qty-wh 2000 :price-minor ask :currency "EUR"})
      (let [r (exec! actor "t2" {:op :actuation/place-order :subject "p-1"
                                 :interval-id "iv-xb" :order-id "o-2" :side :buy
                                 :qty-wh 2000 :price-minor bid :currency "EUR"})]
        (is (= :escalate (disp r)) "reaches a human normally")
        (approve! actor "t2")
        (let [{:keys [fills]} (matching/replay-book (store/order-log db "iv-xb"))]
          (is (= 1 (count fills)))
          (is (= "p-6" (:seller (first fills))))
          (is (= "p-1" (:buyer (first fills)))))))))

(deftest same-jurisdiction-matches-need-no-cross-border-basis
  (let [[db actor] (fixture)]
    (run-approved! actor "t1" {:op :actuation/place-order :subject "p-1"
                               :interval-id "iv-1" :order-id "o-1" :side :sell
                               :qty-wh 1000 :price-minor ask :currency "JPY"})
    (let [r (exec! actor "t2" {:op :actuation/place-order :subject "p-2"
                               :interval-id "iv-1" :order-id "o-2" :side :buy
                               :qty-wh 1000 :price-minor bid :currency "JPY"})]
      (is (= :escalate (disp r)))
      (is (not (contains? (last-hold-basis db) :cross-border-without-basis))))))

(deftest a-wholesale-only-right-does-not-authorise-a-peer-to-peer-sale
  (testing "USA federal grants :sell-wholesale, and this exchange is not a wholesale market"
    (let [db (store/seed-db)
          actor (op/build db)]
      (run-approved! actor "lic" {:op :license/verify :subject "p-8"})
      (is (= #{:generate :sell-wholesale} (set (:permits (store/licence-of db "p-8"))))
          "the licence commits successfully -- the right is real, it is just the wrong right")
      (let [r (exec! actor "t1" {:op :actuation/place-order :subject "p-8"
                                 :interval-id "iv-1" :order-id "o-1" :side :sell
                                 :qty-wh 1000 :price-minor ask :currency "JPY"})]
        (is (= :hold (disp r)))
        (is (contains? (last-hold-basis db) :unlicensed-sell))))))

(deftest a-jurisdiction-that-grants-no-sell-right-holds
  (testing "KOR: the retail licence exists in law but only the incumbent holds one"
    (let [db (store/seed-db)
          actor (op/build db)]
      (run-approved! actor "lic" {:op :license/verify :subject "p-9"})
      (is (= #{:generate} (set (:permits (store/licence-of db "p-9")))))
      (let [r (exec! actor "t1" {:op :actuation/place-order :subject "p-9"
                                 :interval-id "iv-1" :order-id "o-1" :side :sell
                                 :qty-wh 1000 :price-minor ask :currency "JPY"})]
        (is (= :hold (disp r)))
        (is (contains? (last-hold-basis db) :unlicensed-sell))))))

(deftest a-bloc-resolved-participant-can-actually-trade
  (testing "one verified EU citation is enough for a Spanish co-op with no national entry"
    (let [db (store/seed-db)
          actor (op/build db)]
      (run-approved! actor "lic" {:op :license/verify :subject "p-6"})
      (let [lic (store/licence-of db "p-6")]
        (is (= :bloc (:resolved-at lic)))
        (is (= "EU" (:resolved-key lic)))
        (is (contains? (set (:permits lic)) :sell)))
      (run-approved! actor "scr" {:op :conduct/screen :subject "p-6"})
      (let [r (exec! actor "t1" {:op :actuation/place-order :subject "p-6"
                                 :interval-id "iv-eu" :order-id "o-1" :side :sell
                                 :qty-wh 2000 :price-minor ask :currency "EUR"})]
        (is (= :escalate (disp r)))))))
