(ns trade.governor
  "Market Conduct Governor -- the independent compliance layer that earns
  the Electricity Trade Advisor the right to commit.

  The LLM has no notion of which jurisdiction's market-participation
  regime is official, no licence of its own to sell electricity to
  anyone, no way to know whether a seller can physically produce what
  it just offered, and no way to know when a draft stops being a draft
  and becomes a binding delivery obligation with somebody's money on
  the other side of it. So this MUST be a separate system able to
  *reject* a proposal and fall back to HOLD -- the electricity-trade
  analog of `cloud-itonami-isic-3512`'s Grid Policy Governor.

  Seven checks, in priority order, ALL HARD violations: a human approver
  CANNOT override them. You do not get to approve your way past a
  fabricated market spec-basis, an unlicensed sale, a seller offering
  more power than it could physically generate, an order the matching
  engine itself refuses, an unresolved market-abuse flag, a settlement
  over unmetered delivery, or a double settlement.

  The confidence/actuation gate is SOFT: it asks a human to look (low
  confidence / actuation), and the human may approve -- but see
  `trade.phase`: for `:stake :actuation/place-order`/`:actuation/settle-
  interval` NO phase ever allows auto-commit either. Two independent
  layers agree that trading is always a human call.

    1. Spec-basis            -- did the proposal cite an OFFICIAL source
                                (`trade.facts`), or invent one?
    2. Evidence incomplete   -- for both actuations, has the participant
                                actually been verified against the
                                jurisdiction's full evidence checklist?
    3. Unlicensed sell       -- for a SELL order, does the participant's
                                COMMITTED licence basis actually contain
                                the `:sell` right? Anyone may register
                                and anyone may generate; selling is what
                                the jurisdiction gates, and this is
                                where that gate lives.
    4. Capacity exceeded     -- for a SELL order, INDEPENDENTLY recompute
                                from the participant's own registered
                                nameplate capacity and the interval's own
                                duration whether the resulting position
                                is physically deliverable. Needs no
                                proposal inspection and no stored verdict:
                                its inputs are ground-truth fields plus
                                the public order log.
    5. Engine rejection      -- REPLAY the interval's public order log,
                                re-derive the book, and check that
                                `trade.matching/submit` actually accepts
                                this order. Catches gate closure, a
                                broken sequence, a duplicate id, a
                                malformed order, and self-trade/wash
                                trading -- each as the engine's own
                                verdict rather than as a policy opinion
                                the governor could be talked out of.
    6. Market-abuse flag
       unresolved            -- reported by THIS proposal itself, or
                                already on file for the participant.
                                Evaluated UNCONDITIONALLY (not scoped to
                                one op), the same discipline every prior
                                sibling's unresolved-flag check uses.
    7. Unmetered settlement  -- for `:actuation/settle-interval`, every
                                participant that ended up on either side
                                of a fill MUST have a committed meter
                                reading. Settling money against
                                unmeasured delivery is the single worst
                                thing this actor could do, and it is
                                refused structurally rather than
                                discouraged.

  One more guard, double-settlement prevention, is enforced but NOT
  listed as a numbered HARD check above because it needs no upstream
  comparison at all -- `already-settled-violations` refuses to settle
  the SAME interval twice, off a dedicated `:settled?` fact (never a
  `:status` value) -- the SAME 'check a dedicated boolean, not status'
  discipline every prior sibling governor's guards establish, informed
  by `cloud-itonami-isic-6492`'s status-lifecycle bug (ADR-2607071320).

  ── On check 5 being the engine's verdict, not a rule ──

  Checks 1-4, 6 and 7 are policy: the governor holds an opinion about
  what should be allowed. Check 5 is different in kind -- it does not
  hold an opinion, it re-runs the deterministic engine over the public
  log and reports whether the order is admissible. That means wash
  trading is refused at the engine (reject-taker self-trade prevention,
  inherited from `cryptoexchange.matching`) and the governor merely
  surfaces it. A rule can be argued with; a replay cannot."
  (:require [trade.facts :as facts]
            [trade.matching :as matching]
            [trade.registry :as registry]
            [trade.store :as store]))

(def confidence-floor 0.6)

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  Admitting a binding order into a real delivery interval and clearing a
  real interval's money are the two real-world actuation events this
  actor performs -- a two-member set, matching every prior dual-
  actuation sibling's shape."
  #{:actuation/place-order :actuation/settle-interval})

(def ^:private actuations high-stakes)

;; ----------------------------- helpers -----------------------------

(defn- replayed
  "The interval's book + fills, independently re-derived from the public
  order log. The governor never trusts a cached book."
  [st interval-id]
  (matching/replay-book (store/order-log st interval-id)))

;; ----------------------------- checks ------------------------------

(defn- spec-basis-violations
  "A `:license/verify` (or actuation) proposal with no spec-basis
  citation is a HARD violation -- never invent a jurisdiction's
  market-participation requirements."
  [{:keys [op]} proposal]
  (when (or (= op :license/verify) (contains? actuations op))
    (let [value (:value proposal)]
      (when (or (empty? (:cites proposal))
                (and (contains? value :spec-basis) (nil? (:spec-basis value))))
        [{:rule :no-spec-basis
          :detail "公式spec-basisの引用が無い提案は電力市場参加要件として扱えない"}]))))

(defn- evidence-incomplete-violations
  "For both actuations, the jurisdiction's required evidence must
  actually be satisfied -- do not trust the advisor's self-reported
  confidence alone. For `:actuation/settle-interval` the subject is an
  interval, so the check runs against every participant with fills."
  [{:keys [op subject]} st]
  (when (contains? actuations op)
    (let [ids (if (= op :actuation/place-order)
                [subject]
                (matching/accounts-with-fills (replayed st subject)))
          bad (remove (fn [pid]
                        (let [p (store/participant st pid)
                              lic (store/licence-of st pid)]
                          (and lic
                               (facts/required-evidence-satisfied?
                                (:jurisdiction p) (:checklist lic)))))
                      ids)]
      (when (seq bad)
        [{:rule :evidence-incomplete
          :detail (str "法域の必要書類が充足していない参加者: " (pr-str (vec bad)))}]))))

(defn- unlicensed-sell-violations
  "For a SELL order, the participant's COMMITTED licence basis must
  actually carry the `:sell` right.

  This is the check that makes 'anyone may generate and trade' an honest
  claim rather than a slogan: registration and generation are open, and
  the jurisdiction's own line between notifying that you generate and
  being authorised to sell is enforced here rather than wished away.
  The right is read from the COMMITTED verification (`:permits`), never
  from the proposal -- an advisor cannot license itself."
  [{:keys [op subject]} proposal st]
  (when (and (= op :actuation/place-order)
             (= :sell (get-in proposal [:value :side])))
    (let [lic (store/licence-of st subject)]
      (when-not (contains? (set (:permits lic)) :sell)
        [{:rule :unlicensed-sell
          :detail (str subject " は売却権限(:sell)を持つ確定済みライセンス基盤を持たない")}]))))

(defn- capacity-exceeded-violations
  "For a SELL order, INDEPENDENTLY recompute whether the participant's
  resulting sold-plus-resting position for this interval exceeds what
  its own registered nameplate capacity could physically deliver across
  the interval's own duration (`trade.registry/capacity-exceeded?`).

  Inputs are ground truth (the participant's registered capacity, the
  interval's duration) plus the replayed public order log -- no proposal
  inspection, no stored-verdict lookup. You cannot sell power you cannot
  make."
  [{:keys [op subject]} proposal st]
  (when (and (= op :actuation/place-order)
             (= :sell (get-in proposal [:value :side])))
    (let [p (store/participant st subject)
          iv-id (get-in proposal [:value :interval-id])
          iv (store/interval st iv-id)
          already (matching/contracted-sell-wh (replayed st iv-id) subject)
          proposed (get-in proposal [:value :qty-wh])]
      (when (registry/capacity-exceeded? p (:duration-minutes iv) already proposed)
        [{:rule :capacity-exceeded
          :detail (str subject " の売却総量(" (+ (or already 0) (or proposed 0))
                       "Wh)が自己申告設備容量からの物理上限("
                       (registry/deliverable-wh (:capacity-w p) (:duration-minutes iv))
                       "Wh)を超過")}]))))

(defn- engine-rejection-violations
  "REPLAY the interval's public order log and ask the matching engine
  itself whether it would accept this order. The engine's `:reason`
  (`:gate-closed` / `:self-trade-prevented` / `:out-of-order` /
  `:duplicate-id` / `:malformed`) becomes the violation detail verbatim.

  This is a recomputation, not a rule -- see ns docstring."
  [{:keys [op subject]} proposal st]
  (when (= op :actuation/place-order)
    (let [iv-id (get-in proposal [:value :interval-id])
          log (store/order-log st iv-id)
          {:keys [ok? book] :as re} (matching/replay-book log)]
      (if-not ok?
        [{:rule :order-log-corrupt
          :detail (str iv-id " の注文ログが再生できない (reason=" (:reason re)
                       " at seq=" (:at re) ")")}]
        (let [ev {:kind :order
                  :seq (inc (:last-seq book))
                  :id (get-in proposal [:value :order-id])
                  :account subject
                  :side (get-in proposal [:value :side])
                  :price-minor (get-in proposal [:value :price-minor])
                  :qty-minor (get-in proposal [:value :qty-wh])}
              r (matching/submit book ev)]
          (when-not (:ok? r)
            [{:rule :engine-rejected
              :detail (str "マッチングエンジンが注文を拒否: " (:reason r))}]))))))

(defn- market-abuse-flag-unresolved-violations
  "An unresolved market-abuse flag -- reported by THIS proposal (e.g. a
  `:conduct/screen` that itself just found one), or already on file in
  the store for the participant -- is a HARD, un-overridable hold.
  Evaluated UNCONDITIONALLY so the screening op itself can HARD-hold on
  its own finding.

  For `:actuation/settle-interval` the subject is an interval, so every
  participant with fills is checked: one abusive participant blocks the
  whole interval's settlement rather than being quietly settled around."
  [{:keys [op subject]} proposal st]
  (let [hit-in-proposal? (= :unresolved (get-in proposal [:value :verdict]))
        ids (cond
              (contains? #{:conduct/screen :actuation/place-order} op) [subject]
              (= op :actuation/settle-interval) (matching/accounts-with-fills
                                                 (replayed st subject))
              :else [])
        flagged (filter #(= :unresolved (:verdict (store/conduct-screen-of st %))) ids)]
    (when (or hit-in-proposal? (seq flagged))
      [{:rule :market-abuse-flag-unresolved
        :detail (if (seq flagged)
                  (str "未解決の市場行為フラグを持つ参加者: " (pr-str (vec flagged)))
                  "未解決の市場行為フラグがある状態では進められない")}])))

(defn- unmetered-settlement-violations
  "For `:actuation/settle-interval`, every participant that ended up on
  either side of a fill MUST have a committed meter reading for the
  interval. Settling money against unmeasured delivery is refused
  structurally.

  An interval with NO fills is settleable (it clears to nothing); an
  interval with fills and a missing meter is not."
  [{:keys [op subject]} st]
  (when (= op :actuation/settle-interval)
    (let [ids (matching/accounts-with-fills (replayed st subject))
          missing (remove #(some? (store/meter-reading-of st subject %)) ids)]
      (when (seq missing)
        [{:rule :unmetered-settlement
          :detail (str subject " の計量値が未提出の参加者: " (pr-str (vec missing)))}]))))

(defn- already-settled-violations
  "For `:actuation/settle-interval`, refuses to settle the SAME interval
  twice, off a dedicated `:settled?` fact (never a `:status` value)."
  [{:keys [op subject]} st]
  (when (= op :actuation/settle-interval)
    (when (store/interval-already-settled? st subject)
      [{:rule :already-settled
        :detail (str subject " は既に精算確定済み")}])))

;; ----------------------------- entry point -----------------------------

(defn check
  "Censors an Electricity Trade Advisor proposal against the governor
  rules. Returns {:ok? bool :violations [..] :confidence c :escalate?
  bool :high-stakes? bool :hard? bool}."
  [request _context proposal st]
  (let [hard (into []
                   (concat (spec-basis-violations request proposal)
                           (evidence-incomplete-violations request st)
                           (unlicensed-sell-violations request proposal st)
                           (capacity-exceeded-violations request proposal st)
                           (engine-rejection-violations request proposal st)
                           (market-abuse-flag-unresolved-violations request proposal st)
                           (unmetered-settlement-violations request st)
                           (already-settled-violations request st)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (high-stakes (:stake proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t           :governor-hold
   :op          (:op request)
   :actor       (:actor-id context)
   :subject     (:subject request)
   :disposition :hold
   :basis       (mapv :rule (:violations verdict))
   :violations  (:violations verdict)
   :confidence  (:confidence verdict)})
