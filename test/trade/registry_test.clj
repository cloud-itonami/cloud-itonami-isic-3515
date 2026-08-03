(ns trade.registry-test
  (:require [clojure.test :refer [deftest is testing]]
            [trade.registry :as r]))

(deftest price-conversion-round-trips
  (testing "25 JPY/kWh is 25000 µJPY/Wh"
    (is (= 25000 (r/kwh-price->minor 25)))
    (is (= 25.0 (r/minor->kwh-price 25000))))
  (testing "sub-yen prices survive"
    (is (= 25400 (r/kwh-price->minor 25.4)))
    (is (= 25.4 (r/minor->kwh-price 25400))))
  (testing "money leg is exact integer micro-units"
    (is (= 50000000 (* 2000 (r/kwh-price->minor 25))))
    (is (= 50.0 (r/micro->units 50000000)))))

(deftest deliverable-wh-is-a-truncating-physical-bound
  (testing "6000 W across 30 minutes is 3000 Wh"
    (is (= 3000 (r/deliverable-wh 6000 30))))
  (testing "100 W across 30 minutes is 50 Wh"
    (is (= 50 (r/deliverable-wh 100 30))))
  (testing "truncates DOWNWARD -- never rounded up in the seller's favour"
    (is (= 8 (r/deliverable-wh 100 5)) "100*5/60 = 8.33 -> 8"))
  (testing "no capacity means nothing deliverable, not an error"
    (is (= 0 (r/deliverable-wh 0 30)))
    (is (= 0 (r/deliverable-wh nil 30)))
    (is (= 0 (r/deliverable-wh 6000 nil)))))

(deftest capacity-exceeded-accounts-for-existing-position
  (let [p {:capacity-w 6000}]                       ; 3000 Wh across 30 min
    (is (false? (r/capacity-exceeded? p 30 0 3000)) "exactly at the bound is allowed")
    (is (true?  (r/capacity-exceeded? p 30 0 3001)))
    (is (false? (r/capacity-exceeded? p 30 2000 1000)))
    (is (true?  (r/capacity-exceeded? p 30 2000 1001)) "already-contracted volume counts")
    (is (true?  (r/capacity-exceeded? {:capacity-w 100} 30 0 2000)))))

(deftest imbalance-is-metered-minus-contracted
  (is (= 0 (r/imbalance-wh 2000 2000)))
  (is (= -50 (r/imbalance-wh 1950 2000)) "delivered less than sold: short")
  (is (= 100 (r/imbalance-wh 2100 2000)) "delivered more than sold: long")
  (testing "a missing reading is nil, NEVER a convenient zero"
    (is (nil? (r/imbalance-wh nil 2000)))))

(deftest order-record-validates-its-inputs
  (let [ok (r/register-order "p-1" "iv-1" "JPN" :sell 2000 25000 0)]
    (is (= "JPN-ORD-000000" (get ok "order_number")))
    (is (= "order-placement-draft" (get-in ok ["record" "kind"])))
    (is (= "sell" (get-in ok ["record" "side"])))
    (is (= 2000 (get-in ok ["record" "qty_wh"])))
    (is (true? (get-in ok ["record" "immutable"])))
    (testing "certificates are always unsigned drafts"
      (is (nil? (get-in ok ["certificate" "proof"])))
      (is (false? (get-in ok ["certificate" "issued_by_registry"])))
      (is (= "draft-unsigned" (get-in ok ["certificate" "status"])))))
  (testing "sequence advances the reference"
    (is (= "JPN-ORD-000007" (get (r/register-order "p-1" "iv-1" "JPN" :buy 10 1 7) "order_number"))))
  (doseq [[label args] {"blank participant" ["" "iv-1" "JPN" :sell 1 1 0]
                        "blank interval"    ["p-1" "" "JPN" :sell 1 1 0]
                        "blank jurisdiction" ["p-1" "iv-1" "" :sell 1 1 0]
                        "bad side"          ["p-1" "iv-1" "JPN" :sideways 1 1 0]
                        "zero qty"          ["p-1" "iv-1" "JPN" :sell 0 1 0]
                        "negative price"    ["p-1" "iv-1" "JPN" :sell 1 -1 0]
                        "negative sequence" ["p-1" "iv-1" "JPN" :sell 1 1 -1]}]
    (testing label
      (is (thrown? clojure.lang.ExceptionInfo (apply r/register-order args))))))

(deftest settlement-record-refuses-unmetered-positions
  (let [good [{:participant-id "p-1" :contracted-wh 2000 :metered-wh 1950
               :imbalance-wh -50 :money-micro 50000000}
              {:participant-id "p-2" :contracted-wh -2000 :metered-wh -2000
               :imbalance-wh 0 :money-micro -50000000}]
        ok (r/register-settlement "iv-1" "JPN" good 0)]
    (is (= "JPN-STL-000000" (get ok "settlement_number")))
    (is (= 2 (count (get-in ok ["record" "positions"]))))
    (is (= -50 (get-in ok ["record" "positions" 0 "imbalance_wh"]))))
  (testing "a nil metered reading throws rather than settling as balanced"
    (is (thrown? clojure.lang.ExceptionInfo
                 (r/register-settlement "iv-1" "JPN"
                                        [{:participant-id "p-1" :contracted-wh 2000
                                          :metered-wh nil :imbalance-wh nil :money-micro 0}]
                                        0))))
  (testing "an interval with no fills settles to an empty position list"
    (is (= [] (get-in (r/register-settlement "iv-9" "JPN" [] 0) ["record" "positions"])))))

(deftest append-collects-records
  (let [a (r/register-order "p-1" "iv-1" "JPN" :sell 1 1 0)
        b (r/register-order "p-1" "iv-1" "JPN" :sell 1 1 1)]
    (is (= 2 (count (-> [] (r/append a) (r/append b)))))))
