(ns trade.tradeadvisor
  "Electricity Trade Advisor client -- the *contained intelligence node*
  for the electricity-trade actor.

  It normalizes participant registration, drafts a per-jurisdiction
  market-participation evidence checklist, screens participants for an
  unresolved market-abuse flag, records metered delivery, drafts a real
  order, and drafts an interval settlement. CRITICAL: it is a smart-but-
  untrusted advisor. It returns a *proposal* (with a rationale + the
  fields it cited), never a committed record, never an admitted order
  and never a settled interval. Every output is censored downstream by
  `trade.governor` before anything touches the SSoT, and `:actuation/
  place-order`/`:actuation/settle-interval` proposals NEVER auto-commit
  at any phase -- see README `Actuation`.

  Like every sibling actor's advisor, this is a deterministic mock so the
  actor graph runs offline and the governor contract is exercised end-
  to-end. In production this calls a real LLM (kotoba-llm or equivalent)
  with the same proposal shape.

  ── Why the advisor never picks a price ──

  Note what `propose-order` does and does not do: the caller supplies
  side, quantity and price, and the advisor's job is to normalize them,
  cite the participant's own licence and capacity facts, and lower its
  own confidence when those facts do not support the order. It does not
  invent a price, does not decide what to trade, and does not choose a
  counterparty -- the book does that, deterministically, from price-time
  priority.

  An advisor that chose prices would be a trading algorithm wearing a
  compliance actor's clothes, and the fleet's whole containment argument
  would be void: you cannot meaningfully govern a model whose output IS
  the market position. Keeping price formation in the deterministic
  engine and keeping the model on normalization and evidence is what
  makes the governor's independent replay a real check rather than a
  rubber stamp.

  Proposal shape (all kinds):
    {:summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- SCANNED by the spec-basis gate
     :cites      [kw|str ..]    ; facts/sources the LLM used -- SCANNED too
     :effect     kw             ; how a commit would mutate the SSoT
     :stake      kw|nil         ; :actuation/place-order | :actuation/settle-interval | nil
     :confidence 0..1}"
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [trade.facts :as facts]
            [trade.matching :as matching]
            [trade.registry :as registry]
            [trade.store :as store]
            [langchain.model :as model]))

(defn- normalize-registration
  "Participant directory upsert -- the LLM only normalizes/validates the
  patch; it does not invent the participant, its capacity or its
  jurisdiction. High confidence, low stakes.

  This is the op that makes the market open: anyone may register, in any
  role, with any (self-declared) capacity. What that registration
  entitles them to do is decided later, by `:license/verify` and the
  governor -- not by a gate on the door."
  [_db {:keys [patch]}]
  {:summary    (str "参加者記録更新: " (pr-str (keys patch)))
   :rationale  "入力 patch の正規化のみ。新規事実の生成なし。"
   :cites      (vec (keys patch))
   :effect     :participant/upsert
   :value      patch
   :stake      nil
   :confidence 0.97})

(defn- verify-licence
  "Per-jurisdiction market-participation evidence checklist draft.
  `:no-spec?` injects the failure mode we must defend against: proposing
  a checklist for a jurisdiction with NO official spec-basis in
  `trade.facts` -- the Market Conduct Governor must reject this (never
  invent a jurisdiction's requirements).

  The committed payload carries `:permits`, the set of market rights the
  participant obtains. `trade.governor/unlicensed-sell-violations` reads
  that set; an unknown jurisdiction yields the empty set, so the default
  is 'may not sell', never 'may sell'."
  [db {:keys [subject no-spec?]}]
  (let [p (store/participant db subject)
        target (if no-spec? {:jurisdiction "ATL"} p)
        iso3 (:jurisdiction target)
        sb (facts/resolve-basis target)]
    (if (nil? sb)
      {:summary    (str iso3 " の公式spec-basisが見つかりません")
       :rationale  "trade.facts に未登録の法域。市場参加要件を推測で作らない。"
       :cites      []
       :effect     :licence/set
       :value      {:jurisdiction iso3 :subdivision (:subdivision target)
                    :checklist [] :permits #{} :spec-basis nil}
       :stake      nil
       :confidence 0.9}
      {:summary    (str iso3 " (" (:owner-authority sb) ") 向け必要書類 "
                        (count (:required-evidence sb)) " 件を提案 / 付与権限 "
                        (pr-str (sort (:permits sb)))
                        " [解決レベル " (name (:resolved-at sb)) "=" (:resolved-key sb) "]")
       :rationale  (str "公式ソース: " (:provenance sb)
                        " / 法的根拠: " (:legal-basis sb)
                        " / 発電: " (:generate-basis sb)
                        " / 売却: " (:sell-basis sb))
       :cites      [(:legal-basis sb) (:provenance sb)]
       :effect     :licence/set
       :value      {:jurisdiction iso3
                    :subdivision (:subdivision target)
                    :checklist (:required-evidence sb)
                    :permits (:permits sb)
                    :resolved-at (:resolved-at sb)
                    :resolved-key (:resolved-key sb)
                    :spec-basis (:provenance sb)
                    :legal-basis (:legal-basis sb)}
       :stake      nil
       :confidence 0.9})))

(defn- screen-conduct
  "Market-conduct screening draft. `:market-abuse-flag-unresolved?` on
  the participant record injects the failure mode: the Market Conduct
  Governor must HOLD, un-overridably, on any unresolved flag."
  [db {:keys [subject]}]
  (let [p (store/participant db subject)]
    (cond
      (nil? p)
      {:summary "対象参加者記録が見つかりません" :rationale "no participant record"
       :cites [] :effect :conduct-screen/set
       :value {:participant-id subject :verdict :unknown}
       :stake nil :confidence 0.0}

      (true? (:market-abuse-flag-unresolved? p))
      {:summary    (str (:display-name p) ": 未解決の市場行為フラグを検出")
       :rationale  "スクリーニングが未解決の市場行為フラグを検出。人手確認とホールドが必須。"
       :cites      [:conduct-check]
       :effect     :conduct-screen/set
       :value      {:participant-id subject :verdict :unresolved}
       :stake      nil
       :confidence 0.95}

      :else
      {:summary    (str (:display-name p) ": 未解決の市場行為フラグなし")
       :rationale  "市場行為スクリーニング完了。"
       :cites      [:conduct-check]
       :effect     :conduct-screen/set
       :value      {:participant-id subject :verdict :resolved}
       :stake      nil
       :confidence 0.9})))

(defn- submit-meter
  "Metered-delivery record draft for one participant in one interval.
  Sign convention: positive = net EXPORT to the market, negative = net
  import. Normalize-only, like registration -- the advisor does not
  invent a reading, and a missing reading stays missing rather than
  becoming a convenient zero."
  [_db {:keys [subject participant-id metered-wh]}]
  {:summary    (str subject " / " participant-id " の計量値 " metered-wh "Wh を記録")
   :rationale  "入力計量値の正規化のみ。値の推定・補完は行わない。"
   :cites      [:meter-reading]
   :effect     :meter/set
   :value      {:interval-id subject :participant-id participant-id
                :metered-wh metered-wh}
   :stake      nil
   :confidence (if (number? metered-wh) 0.95 0.0)})

(defn- propose-order
  "Draft the actual ORDER-PLACEMENT action -- admitting a financially
  binding buy/sell order into a real delivery interval's book. ALWAYS
  `:stake :actuation/place-order` -- this creates a real delivery
  obligation and a real payment, never a draft the actor may auto-run.
  See README `Actuation`: no phase ever adds this op to a phase's
  `:auto` set (`trade.phase`); the governor also always escalates on
  `:actuation/place-order`. Two independent layers agree, deliberately.

  Confidence drops when the participant's own facts do not support the
  order -- an unlicensed seller or a physically undeliverable quantity.
  That is a courtesy signal for the human approver, NOT the enforcement
  path: `trade.governor` recomputes both independently and holds HARD,
  so a mock or an LLM reporting 0.99 here changes nothing."
  [db {:keys [subject interval-id order-id side qty-wh price-minor currency]}]
  (let [p (store/participant db subject)
        iv (store/interval db interval-id)
        lic (store/licence-of db subject)
        already (matching/contracted-sell-wh
                 (matching/replay-book (store/order-log db interval-id)) subject)
        sell? (= side :sell)
        licensed? (contains? (set (:permits lic)) :sell)
        over? (and sell? (registry/capacity-exceeded?
                          p (:duration-minutes iv) already qty-wh))]
    {:summary    (str subject " の " (name (or side :?)) " 注文提案: "
                      qty-wh "Wh @ " price-minor "µ" (or currency "???")
                      "/Wh (" interval-id ")")
     :rationale  (if p
                   (str "role=" (:role p)
                        " capacity-w=" (:capacity-w p)
                        " interval-minutes=" (:duration-minutes iv)
                        " deliverable-wh=" (registry/deliverable-wh
                                            (:capacity-w p) (:duration-minutes iv))
                        " already-contracted-wh=" already
                        " order-currency=" (pr-str currency)
                        " book-currency=" (pr-str (:currency iv))
                        " permits=" (pr-str (sort (or (:permits lic) []))))
                   "参加者記録が見つかりません")
     :cites      (if p [subject interval-id] [])
     :effect     :interval/place-order
     :value      {:participant-id subject
                  :interval-id interval-id
                  :order-id order-id
                  :side side
                  :qty-wh qty-wh
                  :price-minor price-minor
                  :currency currency}
     :stake      :actuation/place-order
     :confidence (cond (nil? p) 0.2
                       (and sell? (not licensed?)) 0.3
                       (not= currency (:currency iv)) 0.2
                       over? 0.3
                       :else 0.9)}))

(defn- propose-settlement
  "Draft the actual INTERVAL-SETTLEMENT action -- clearing a real
  delivery interval: freezing its fills and reconciling every
  participant's contracted volume against its METERED actual delivery.
  ALWAYS `:stake :actuation/settle-interval` -- this is where money
  moves, never a draft the actor may auto-run.

  Confidence drops when any participant with fills is unmetered. Again a
  courtesy signal only: `trade.governor/unmetered-settlement-violations`
  holds HARD on the same condition, and `trade.registry/register-
  settlement` throws rather than build a record over a nil reading. Three
  layers refuse to settle unmeasured power."
  [db {:keys [subject]}]
  (let [iv (store/interval db subject)
        re (matching/replay-book (store/order-log db subject))
        ids (matching/accounts-with-fills re)
        unmetered (remove #(some? (store/meter-reading-of db subject %)) ids)]
    {:summary    (str subject " の精算確定提案 (約定参加者 " (count ids) " 名"
                      (when (seq unmetered)
                        (str " / 計量値未提出 " (count unmetered) " 名"))
                      ")")
     :rationale  (if iv
                   (str "fills=" (count (:fills re))
                        " participants=" (pr-str ids)
                        " unmetered=" (pr-str (vec unmetered)))
                   "対象インターバル記録が見つかりません")
     :cites      (if iv [subject] [])
     :effect     :interval/mark-settled
     :value      {:interval-id subject}
     :stake      :actuation/settle-interval
     :confidence (cond (nil? iv) 0.2
                       (seq unmetered) 0.3
                       :else 0.9)}))

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :subject id ...op-specific...}"
  [db {:keys [op] :as request}]
  (case op
    :participant/register          (normalize-registration db request)
    :license/verify                (verify-licence db request)
    :conduct/screen                (screen-conduct db request)
    :meter/submit                  (submit-meter db request)
    :actuation/place-order         (propose-order db request)
    :actuation/settle-interval     (propose-settlement db request)
    {:summary "未対応の操作" :rationale (str op) :cites []
     :effect :noop :stake nil :confidence 0.0}))

;; ----------------------------- Advisor protocol -----------------------------

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere."
  [] (reify Advisor (-advise [_ st req] (infer st req))))

(def ^:private system-prompt
  (str "あなたは電力取引所(ISIC 3515 電力売買)の取引・精算エージェントの助言者です。"
       "与えられた事実のみに基づき、提案を1つだけEDNマップで返します。説明や前置きは"
       "一切書かず、EDNだけを出力します。\n"
       "キー: :summary(人向けドラフト) :rationale(根拠/必ず事実から) "
       ":cites(使った事実キーのベクタ) "
       ":effect(:participant/upsert|:licence/set|:conduct-screen/set|"
       ":meter/set|:interval/place-order|:interval/mark-settled) "
       ":stake(:actuation/place-order か :actuation/settle-interval か nil) :confidence(0..1)。\n"
       "重要: 登録されていない法域の市場参加要件を絶対に創作してはいけません。"
       "spec-basisが無い場合は :cites を空にし confidence を上げないこと。\n"
       "重要: 価格を自分で決めてはいけません。価格・数量・売買方向は入力で与えられたものを"
       "正規化するだけで、あなたが相場を判断したり相手方を選んだりすることはありません。"))

(defn- facts-for [st {:keys [op subject interval-id]}]
  (case op
    :license/verify            {:participant (store/participant st subject)}
    :conduct/screen            {:participant (store/participant st subject)}
    :meter/submit              {:interval (store/interval st subject)}
    :actuation/place-order     {:participant (store/participant st subject)
                                :interval (store/interval st interval-id)
                                :licence (store/licence-of st subject)}
    :actuation/settle-interval {:interval (store/interval st subject)
                                :order-log (store/order-log st subject)}
    {:participant (store/participant st subject)}))

(defn- parse-proposal
  "Parse the model's EDN proposal defensively. Any parse/shape failure
  yields a safe low-confidence noop so the Market Conduct Governor
  escalates/holds -- an LLM hiccup can never admit an order or settle an
  interval."
  [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p
          (update :cites #(vec (or % [])))
          (update :confidence #(if (number? %) (double %) 0.0))
          (update :effect #(or % :noop)))
      {:summary "LLM応答を解釈できませんでした" :rationale (str content)
       :cites [] :effect :noop :stake nil :confidence 0.0})))

(defn llm-advisor
  "An advisor backed by a `langchain.model/ChatModel` (real inference)."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ st req]
       (let [msgs [{:role :system :content system-prompt}
                   {:role :user :content (str "操作: " (:op req)
                                              "\n対象: " (:subject req)
                                              "\n事実: " (pr-str (facts-for st req)))}]
             resp (model/-generate chat-model msgs gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace
  "Decision-grounded audit record -- persisted to the :audit channel."
  [request proposal]
  {:t          :tradeadvisor-proposal
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :confidence (:confidence proposal)})
