(ns trade.facts-test
  (:require [clojure.test :refer [deftest is testing]]
            [trade.facts :as facts]))

(deftest seeded-jurisdictions-carry-a-real-spec-basis
  (doseq [iso3 (keys facts/catalog)]
    (testing iso3
      (let [sb (facts/spec-basis iso3)]
        (is (seq (:owner-authority sb)))
        (is (seq (:legal-basis sb)))
        (is (seq (:generate-basis sb)) "the GENERATE side must be stated separately")
        (is (seq (:sell-basis sb)) "the SELL side must be stated separately")
        (is (seq (:provenance sb)))
        (is (seq (:required-evidence sb)))
        (is (set? (:permits sb)))))))

(deftest unknown-jurisdiction-permits-nothing
  (testing "the default is deny, never a permissive fallback"
    (is (nil? (facts/spec-basis "ATL")))
    (is (= #{} (facts/permits "ATL")))
    (is (false? (facts/permits? "ATL" :sell)))
    (is (false? (facts/permits? "ATL" :generate)))
    (is (false? (facts/permits? nil :sell)))))

(deftest permits-distinguishes-generating-from-selling
  (is (true? (facts/permits? "JPN" :generate)))
  (is (true? (facts/permits? "JPN" :sell)))
  (is (false? (facts/permits? "JPN" :operate-a-nuclear-reactor))
      "an unlisted right is never granted by accident"))

(deftest evidence-checklist-must-be-fully-satisfied
  (let [full (facts/evidence-checklist "JPN")]
    (is (= 4 (count full)))
    (is (true? (facts/required-evidence-satisfied? "JPN" full)))
    (is (false? (facts/required-evidence-satisfied? "JPN" (butlast full)))
        "a partial checklist never satisfies")
    (is (false? (facts/required-evidence-satisfied? "JPN" [])))
    (testing "an unknown jurisdiction is never satisfied, whatever is submitted"
      (is (nil? (facts/required-evidence-satisfied? "ATL" ["anything"]))))))

(deftest coverage-is-reported-honestly
  (let [c (facts/coverage ["JPN" "GBR" "ATL" "ZZZ"])]
    (is (= 4 (:requested c)))
    (is (= 2 (:covered c)))
    (is (= ["GBR" "JPN"] (:covered-jurisdictions c)))
    (is (= ["ATL" "ZZZ"] (:missing-jurisdictions c))
        "missing jurisdictions are named, never silently dropped"))
  (testing "the default report covers exactly what is seeded"
    (is (= (count facts/catalog) (:covered (facts/coverage))))
    (is (= [] (:missing-jurisdictions (facts/coverage))))))

(deftest scope-limits-are-recorded-not-papered-over
  (testing "USA's entry states that FERC reaches wholesale, not retail"
    (is (seq (:jurisdiction-note (facts/spec-basis "USA")))))
  (testing "every entry declares that exemption thresholds are unverified"
    (doseq [iso3 (keys facts/catalog)]
      (is (seq (:exemption-note (facts/spec-basis iso3))) iso3))))
