(ns trade.phase
  "Phase 0->3 staged rollout -- the electricity-trade analog of
  `cloud-itonami-isic-3512`'s `energy.phase`.

    Phase 0  read-only        -- no writes, still governor-gated.
    Phase 1  assisted-intake  -- participant registration allowed, every
                                 write needs human approval.
    Phase 2  assisted-verify  -- adds licence verification, market-
                                 conduct screening and meter-reading
                                 submission, still approval.
    Phase 3  supervised auto  -- governor-clean, high-confidence
                                 `:participant/register` (no capital
                                 risk yet) may auto-commit.
                                 `:actuation/place-order`/`:actuation/
                                 settle-interval` NEVER auto-commit, at
                                 any phase.

  `:actuation/place-order`/`:actuation/settle-interval` are deliberately
  ABSENT from every phase's `:auto` set, including phase 3 -- a
  permanent structural fact, not a rollout milestone still to come.
  Admitting a financially binding order into a real delivery interval,
  and clearing a real interval's money against real meter readings, are
  the two real-world acts this actor performs; both are always a human
  operator's call.

  This is the invariant that most distinguishes this actor from the
  automated trading systems it superficially resembles. An order book
  that a language model may fill on its own initiative is a machine for
  turning a hallucination into a delivery obligation and then into
  someone's electricity bill. `trade.governor`'s high-stakes gate
  enforces the same invariant independently -- two layers, not one,
  agree on this, and neither is sufficient alone.

  `:conduct/screen` is likewise never auto-eligible, at any phase -- the
  same posture every sibling's screening op has. Phase 3's `:auto` set
  here has only ONE member (`:participant/register`).")

(def read-ops #{})

(def write-ops
  #{:participant/register :license/verify :conduct/screen :meter/submit
    :actuation/place-order :actuation/settle-interval})

;; NOTE the invariant: `:actuation/place-order`/`:actuation/settle-
;; interval` are members of `write-ops` (governor-gated like any write)
;; but are NEVER members of any phase's `:auto` set below. Do not add
;; them there.
(def phases
  "phase -> {:label .. :writes <ops allowed to write> :auto <ops allowed
  to auto-commit when governor-clean>}."
  {0 {:label "read-only"       :writes #{}                          :auto #{}}
   1 {:label "assisted-intake" :writes #{:participant/register}     :auto #{}}
   2 {:label "assisted-verify" :writes #{:participant/register :license/verify
                                         :conduct/screen :meter/submit}
      :auto #{}}
   3 {:label "supervised-auto" :writes write-ops
      :auto #{:participant/register}}})

(def default-phase 3)

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}.

  - a governor HOLD always stays HOLD (compliance wins).
  - a write op not yet enabled in this phase -> HOLD (:phase-disabled).
  - a write op enabled but not auto-eligible -> ESCALATE (:phase-approval),
    even if the governor was clean.
  - `:actuation/place-order`/`:actuation/settle-interval` are never
    auto-eligible at any phase, so they always escalate once the
    governor clears them (or hold if the governor doesn't)."
  [phase {:keys [op]} governor-disposition]
  (let [{:keys [writes auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold governor-disposition)       {:disposition :hold :reason nil}
      (contains? read-ops op)              {:disposition governor-disposition :reason nil}
      (not (contains? writes op))          {:disposition :hold :reason :phase-disabled}
      (and (= :commit governor-disposition)
           (not (contains? auto op)))      {:disposition :escalate :reason :phase-approval}
      :else                                {:disposition governor-disposition :reason nil})))

(defn verdict->disposition
  "Map a Market Conduct Governor verdict to a base disposition before the
  phase gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
