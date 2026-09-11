(ns trade.phase-test
  (:require [clojure.test :refer [deftest is testing]]
            [trade.phase :as phase]))

(def ^:private actuations #{:actuation/place-order :actuation/settle-interval})

(deftest actuations-are-never-auto-eligible-at-any-phase
  (testing "THE structural invariant of this actor -- an order book a model may fill on its own initiative is a machine for turning a hallucination into someone's electricity bill"
    (doseq [[p {:keys [auto]}] phase/phases
            op actuations]
      (is (not (contains? auto op))
          (str "phase " p " must not auto-commit " op)))))

(deftest phase-3-auto-set-has-exactly-one-member
  (is (= #{:participant/register} (get-in phase/phases [3 :auto]))))

(deftest screening-is-never-auto-eligible
  (doseq [[_ {:keys [auto]}] phase/phases]
    (is (not (contains? auto :conduct/screen)))))

(deftest phase-0-writes-nothing
  (is (= #{} (get-in phase/phases [0 :writes])))
  (is (= #{} (get-in phase/phases [0 :auto]))))

(deftest gate-holds-a-governor-hold-regardless-of-phase
  (doseq [p (keys phase/phases)
          op phase/write-ops]
    (is (= :hold (:disposition (phase/gate p {:op op} :hold)))
        "compliance always wins over the phase table")))

(deftest gate-holds-ops-not-yet-enabled-in-this-phase
  (let [{:keys [disposition reason]} (phase/gate 1 {:op :license/verify} :commit)]
    (is (= :hold disposition))
    (is (= :phase-disabled reason)))
  (let [{:keys [disposition reason]} (phase/gate 0 {:op :participant/register} :commit)]
    (is (= :hold disposition))
    (is (= :phase-disabled reason))))

(deftest gate-escalates-enabled-but-non-auto-ops-even-when-clean
  (doseq [op [:license/verify :conduct/screen :meter/submit]]
    (let [{:keys [disposition reason]} (phase/gate 2 {:op op} :commit)]
      (is (= :escalate disposition) (str op " must reach a human at phase 2"))
      (is (= :phase-approval reason))))
  (testing "and both actuations escalate even at phase 3, the most permissive phase"
    (doseq [op actuations]
      (let [{:keys [disposition reason]} (phase/gate 3 {:op op} :commit)]
        (is (= :escalate disposition))
        (is (= :phase-approval reason))))))

(deftest gate-lets-the-one-auto-op-through-at-phase-3
  (is (= :commit (:disposition (phase/gate 3 {:op :participant/register} :commit)))))

(deftest unknown-phase-falls-back-to-the-default-not-to-permissive
  (let [{:keys [disposition]} (phase/gate 99 {:op :actuation/place-order} :commit)]
    (is (= :escalate disposition) "an unknown phase behaves as phase 3, still never auto")))

(deftest verdict-mapping
  (is (= :hold (phase/verdict->disposition {:hard? true :escalate? true})))
  (is (= :escalate (phase/verdict->disposition {:hard? false :escalate? true})))
  (is (= :commit (phase/verdict->disposition {:hard? false :escalate? false}))))
