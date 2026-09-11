(ns trade.matching-test
  "The matching engine's own invariants, independent of the actor.

  These are the properties an outside auditor relies on: same log in,
  same fills out; a taker never pays worse than it asked; a participant
  cannot trade with itself; and a closed gate cannot be traded through."
  (:require [clojure.test :refer [deftest is testing]]
            [trade.matching :as m]))

(defn- order [seq id account side price qty]
  {:kind :order :seq seq :id id :account account :side side
   :price-minor price :qty-minor qty})

(deftest empty-book-is-open
  (is (false? (:gate-closed? m/empty-book)))
  (is (zero? (:last-seq m/empty-book)))
  (is (= 1 (m/next-seq []))))

(deftest resting-order-does-not-fill
  (let [{:keys [ok? book fills]} (m/submit m/empty-book (order 1 "o1" "a" :sell 25000 2000))]
    (is ok?)
    (is (empty? fills))
    (is (= 1 (count (:asks book))))
    (is (= 2000 (:qty-minor (first (:asks book)))))))

(deftest crossing-order-fills-at-maker-price
  (testing "the taker bid 26000 but pays the maker's 25000"
    (let [b1 (:book (m/submit m/empty-book (order 1 "o1" "seller" :sell 25000 2000)))
          {:keys [ok? book fills]} (m/submit b1 (order 2 "o2" "buyer" :buy 26000 2000))]
      (is ok?)
      (is (= 1 (count fills)))
      (let [f (first fills)]
        (is (= 25000 (:price-minor f)) "maker price rule")
        (is (= 2000 (:qty-minor f)))
        (is (= "buyer" (:buyer f)))
        (is (= "seller" (:seller f)))
        (is (= 50000000 (m/fill-money-micro f)) "2000 Wh * 25000 µ/Wh = 50 JPY exactly"))
      (is (empty? (:asks book)))
      (is (empty? (:bids book)) "fully filled taker rests nothing"))))

(deftest partial-fill-leaves-remainder-resting
  (let [b1 (:book (m/submit m/empty-book (order 1 "o1" "seller" :sell 25000 800)))
        {:keys [book fills]} (m/submit b1 (order 2 "o2" "buyer" :buy 26000 2000))]
    (is (= 800 (:qty-minor (first fills))))
    (is (= 1200 (:qty-minor (first (:bids book)))) "unfilled remainder rests as a bid")))

(deftest price-time-priority
  (testing "better price first, then earlier seq within a price"
    (let [log [(order 1 "cheap"  "s1" :sell 24000 1000)
               (order 2 "dear"   "s2" :sell 26000 1000)
               (order 3 "cheap2" "s3" :sell 24000 1000)]
          {:keys [book]} (m/replay-book log)]
      (is (= ["cheap" "cheap2" "dear"] (mapv :id (:asks book)))))))

(deftest self-trade-is-rejected-not-filled
  (testing "reject-taker: the whole incoming order dies before any fill"
    (let [b1 (:book (m/submit m/empty-book (order 1 "o1" "same" :sell 25000 1000)))
          r (m/submit b1 (order 2 "o2" "same" :buy 26000 1000))]
      (is (false? (:ok? r)))
      (is (= :self-trade-prevented (:reason r)))
      (is (= b1 (:book r)) "book is untouched on rejection"))))

(deftest out-of-order-and-duplicate-are-rejected
  (let [b1 (:book (m/submit m/empty-book (order 1 "o1" "a" :sell 25000 1000)))]
    (is (= :out-of-order (:reason (m/submit b1 (order 5 "o9" "b" :buy 26000 100)))))
    (is (= :duplicate-id (:reason (m/submit b1 (order 2 "o1" "b" :sell 25000 100)))))))

(deftest malformed-orders-are-rejected
  (doseq [bad [(order 1 "o" "a" :sell 0 1000)
               (order 1 "o" "a" :sell 25000 0)
               (order 1 "o" "a" :sell 25000 -5)
               (assoc (order 1 "o" "a" :sell 25000 1000) :side :sideways)]]
    (is (= :malformed (:reason (m/submit m/empty-book bad))) (pr-str bad))))

(deftest gate-closure-blocks-orders-but-not-cancels
  (let [b1 (:book (m/submit m/empty-book (order 1 "o1" "a" :sell 25000 1000)))
        b2 (:book (m/close-gate b1 {:kind :close-gate :seq 2}))]
    (is (true? (:gate-closed? b2)))
    (is (= :gate-closed (:reason (m/submit b2 (order 3 "o2" "b" :buy 26000 100)))))
    (testing "withdrawing a position you can no longer deliver stays allowed"
      (let [r (m/cancel-order b2 {:kind :cancel :seq 3 :id "o1" :account "a"})]
        (is (:ok? r))
        (is (empty? (:asks (:book r))))))
    (testing "re-closing is rejected rather than silently accepted"
      (is (= :already-closed (:reason (m/close-gate b2 {:kind :close-gate :seq 3})))))))

(deftest only-the-owner-may-cancel
  (let [b1 (:book (m/submit m/empty-book (order 1 "o1" "a" :sell 25000 1000)))]
    (is (= :not-owner (:reason (m/cancel-order b1 {:kind :cancel :seq 2 :id "o1" :account "b"}))))
    (is (= :unknown-order (:reason (m/cancel-order b1 {:kind :cancel :seq 2 :id "nope" :account "a"}))))))

(deftest replay-is-deterministic-and-reproduces-fills
  (testing "the third-party recomputation path: same log in, same book and fills out"
    (let [log [(order 1 "o1" "s" :sell 25000 2000)
               (order 2 "o2" "b" :buy 26000 1200)
               (order 3 "o3" "c" :buy 25000 500)]
          a (m/replay-book log)
          b (m/replay-book log)]
      (is (:ok? a))
      (is (= a b) "replay is a pure fold -- byte-identical on re-run")
      (is (= 2 (count (:fills a))))
      (is (= 300 (:qty-minor (first (:asks (:book a))))) "2000 - 1200 - 500"))))

(deftest replay-stops-at-the-first-invalid-event
  (let [log [(order 1 "o1" "s" :sell 25000 1000)
             (order 7 "o2" "b" :buy 26000 1000)]
        r (m/replay-book log)]
    (is (false? (:ok? r)))
    (is (= :out-of-order (:reason r)))
    (is (= 7 (:at r)))))

(deftest position-views
  (let [log [(order 1 "o1" "s" :sell 25000 2000)
             (order 2 "o2" "b" :buy 26000 1200)]
        re (m/replay-book log)]
    (is (= 2000 (m/contracted-sell-wh re "s")) "1200 filled + 800 still resting")
    (is (= 1200 (m/contracted-buy-wh re "b")))
    (is (= 0 (m/contracted-buy-wh re "s")))
    (is (= ["b" "s"] (m/accounts-with-fills re)))
    (is (= [] (m/accounts-with-fills (m/replay-book []))))))

(deftest next-seq-tracks-the-log
  (let [log [(order 1 "o1" "s" :sell 25000 1000)
             (order 2 "o2" "b" :buy 26000 1000)]]
    (is (= 3 (m/next-seq log)))
    (is (= 1 (m/next-seq [])))))
