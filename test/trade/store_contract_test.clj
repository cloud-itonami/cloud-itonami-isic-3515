(ns trade.store-contract-test
  "The Store contract, run against BOTH backends. Proving MemStore and
  the Datomic-backed (langchain.db) store satisfy the same contract is
  what makes 'swap the SSoT for Datomic / kotoba-server' a configuration
  change, not a rewrite.

  For this actor the parity that matters most is the ORDER LOG: both
  backends must return the same events in the same interval-scoped
  order, because the book is re-derived from that order and a backend
  that reordered it would produce different fills from the same trades."
  (:require [clojure.test :refer [deftest is testing]]
            [trade.matching :as matching]
            [trade.store :as store]))

(defn- backends []
  [["MemStore" (store/seed-db)] ["DatomicStore" (store/datomic-seed-db)]])

(deftest read-parity
  (doseq [[label s] (backends)]
    (testing label
      (is (= "鈴木家 屋根置き太陽光+蓄電池" (:display-name (store/participant s "p-1"))))
      (is (= :prosumer (:role (store/participant s "p-1"))))
      (is (= "JPN" (:jurisdiction (store/participant s "p-1"))))
      (is (= 6000 (:capacity-w (store/participant s "p-1"))))
      (is (= 100 (:capacity-w (store/participant s "p-5"))))
      (is (false? (:market-abuse-flag-unresolved? (store/participant s "p-1"))))
      (is (true? (:market-abuse-flag-unresolved? (store/participant s "p-4"))))
      (is (= ["p-1" "p-2" "p-3" "p-4" "p-5"] (mapv :id (store/all-participants s))))

      (is (= 30 (:duration-minutes (store/interval s "iv-1"))))
      (is (= "JPN" (:jurisdiction (store/interval s "iv-1"))))
      (is (false? (:settled? (store/interval s "iv-1"))))
      (is (= ["iv-1" "iv-2"] (mapv :id (store/all-intervals s))))

      (is (= [] (store/order-log s "iv-1")))
      (testing "iv-2's gate closure is seeded as an EVENT, not a flag"
        (is (= [{:kind :close-gate :seq 1}] (store/order-log s "iv-2")))
        (is (true? (:gate-closed? (:book (matching/replay-book (store/order-log s "iv-2")))))))

      (is (nil? (store/licence-of s "p-1")))
      (is (nil? (store/conduct-screen-of s "p-1")))
      (is (nil? (store/meter-reading-of s "iv-1" "p-1")))
      (is (= [] (store/ledger s)))
      (is (= [] (store/order-history s)))
      (is (= [] (store/settlement-history s)))
      (is (zero? (store/next-order-sequence s "JPN")))
      (is (zero? (store/next-settlement-sequence s "JPN")))
      (is (false? (store/interval-already-settled? s "iv-1"))))))

(deftest write-and-ledger-parity
  (doseq [[label s] (backends)]
    (testing label
      (testing "partial upsert merges, preserving untouched fields"
        (store/commit-record! s {:effect :participant/upsert
                                 :value {:id "p-1" :display-name "鈴木家(改称)"}})
        (is (= "鈴木家(改称)" (:display-name (store/participant s "p-1"))))
        (is (= 6000 (:capacity-w (store/participant s "p-1"))) "unrelated field preserved"))

      (testing "licence / conduct-screen payloads commit and read back"
        (store/commit-record! s {:effect :licence/set :path ["p-1"]
                                 :payload {:jurisdiction "JPN" :checklist ["a" "b"]
                                           :permits #{:generate :sell}}})
        (is (= #{:generate :sell} (:permits (store/licence-of s "p-1"))))
        (store/commit-record! s {:effect :conduct-screen/set :path ["p-1"]
                                 :payload {:participant-id "p-1" :verdict :resolved}})
        (is (= :resolved (:verdict (store/conduct-screen-of s "p-1")))))

      (testing "meter readings are keyed by BOTH interval and participant"
        (store/commit-record! s {:effect :meter/set :path ["iv-1" "p-1"]
                                 :payload {:interval-id "iv-1" :participant-id "p-1"
                                           :metered-wh 1950}})
        (is (= 1950 (:metered-wh (store/meter-reading-of s "iv-1" "p-1"))))
        (is (nil? (store/meter-reading-of s "iv-1" "p-2")) "another participant is unaffected")
        (is (nil? (store/meter-reading-of s "iv-2" "p-1")) "another interval is unaffected"))

      (testing "placing an order appends to the log and advances the sequence"
        (store/commit-record! s {:effect :interval/place-order :path ["iv-1"]
                                 :value {:participant-id "p-1" :interval-id "iv-1"
                                         :order-id "o-1" :side :sell
                                         :qty-wh 2000 :price-minor 25000}})
        (let [log (store/order-log s "iv-1")]
          (is (= 1 (count log)))
          (is (= {:kind :order :seq 1 :id "o-1" :account "p-1" :side :sell
                  :price-minor 25000 :qty-minor 2000}
                 (first log))))
        (is (= "JPN-ORD-000000" (get (first (store/order-history s)) "record_id")))
        (is (= "order-placement-draft" (get (first (store/order-history s)) "kind")))
        (is (= 1 (store/next-order-sequence s "JPN"))))

      (testing "a second order gets the next interval-scoped :seq, and fills"
        (store/commit-record! s {:effect :interval/place-order :path ["iv-1"]
                                 :value {:participant-id "p-2" :interval-id "iv-1"
                                         :order-id "o-2" :side :buy
                                         :qty-wh 2000 :price-minor 26000}})
        (let [log (store/order-log s "iv-1")]
          (is (= [1 2] (mapv :seq log)) "log is returned in interval-scoped seq order")
          (let [{:keys [ok? fills]} (matching/replay-book log)]
            (is ok?)
            (is (= 1 (count fills)))
            (is (= 25000 (:price-minor (first fills))) "maker price")))
        (is (= "JPN-ORD-000001" (get (second (store/order-history s)) "record_id")))
        (is (= 2 (store/next-order-sequence s "JPN"))))

      (testing "settlement needs both meters, then freezes the interval"
        (store/commit-record! s {:effect :meter/set :path ["iv-1" "p-2"]
                                 :payload {:interval-id "iv-1" :participant-id "p-2"
                                           :metered-wh -2000}})
        (store/commit-record! s {:effect :interval/mark-settled :path ["iv-1"]})
        (let [rec (first (store/settlement-history s))
              pos (into {} (map (juxt #(get % "participant_id") identity)
                                (get rec "positions")))]
          (is (= "JPN-STL-000000" (get rec "record_id")))
          (is (= "interval-settlement-draft" (get rec "kind")))
          (is (= 2000 (get-in pos ["p-1" "contracted_wh"])))
          (is (= -50 (get-in pos ["p-1" "imbalance_wh"])))
          (is (= 50000000 (get-in pos ["p-1" "money_micro"])))
          (is (= -50000000 (get-in pos ["p-2" "money_micro"])))
          (is (zero? (+ (get-in pos ["p-1" "money_micro"])
                        (get-in pos ["p-2" "money_micro"])))
              "money conserves: what one participant is owed, the other owes"))
        (is (true? (store/interval-already-settled? s "iv-1")))
        (is (= "JPN-STL-000000" (:settlement-number (store/interval s "iv-1"))))
        (is (= 1 (store/next-settlement-sequence s "JPN"))))

      (testing "the ledger is append-only and ordered"
        (store/append-ledger! s {:t :committed :op :actuation/place-order :subject "p-1"})
        (store/append-ledger! s {:t :governor-hold :op :actuation/place-order :subject "p-3"})
        (is (= [:committed :governor-hold] (mapv :t (store/ledger s))))))))

(deftest order-log-is-isolated-per-interval
  (doseq [[label s] (backends)]
    (testing label
      (store/commit-record! s {:effect :interval/place-order :path ["iv-1"]
                               :value {:participant-id "p-1" :interval-id "iv-1"
                                       :order-id "a" :side :sell
                                       :qty-wh 100 :price-minor 25000}})
      (is (= 1 (count (store/order-log s "iv-1"))))
      (is (= 1 (count (store/order-log s "iv-2")))
          "iv-2 still holds only its seeded close-gate event")
      (is (= [] (store/order-log s "iv-nonexistent"))))))
