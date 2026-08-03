(ns trade.facts
  "Per-jurisdiction ELECTRICITY MARKET PARTICIPATION catalog -- the
  G2-style spec-basis table the Market Conduct Governor checks every
  `:license/verify` proposal against ('did the advisor cite an OFFICIAL
  source for what this jurisdiction actually requires before a person
  may generate electricity, and before a person may SELL it, or did it
  invent one?').

  This catalog is deliberately NOT the same question as `cloud-itonami-
  isic-3512`'s `energy.facts` (grid-interconnection and tariff
  requirements: 'may this site physically connect and how is it
  billed'). This one asks the TRADE question: 'what authorisation does
  a person need before they may sell electricity to someone else'.
  A site can be lawfully interconnected and still have no right to sell
  its output to a neighbour.

  ── Why `:permits` is the load-bearing field ──

  Every jurisdiction seeded here draws the SAME structural line, and it
  is the line that decides who may do what on this exchange:

    GENERATING electricity is a LOW-barrier act -- typically a
    notification/registration, and below a threshold usually a class
    exemption requiring nothing at all.

    SELLING electricity to another party is a HIGHER-barrier act --
    a licence, an authorisation, or a registration that the regulator
    can refuse and revoke.

  So `:permits` is a SET, not a boolean: `#{:generate}` and
  `#{:generate :sell}` are different market rights, and `trade.governor/
  unlicensed-sell-violations` refuses a SELL order from a participant
  whose committed licence basis does not contain `:sell`. That is the
  whole mechanism by which 'anyone may generate and trade' stays true
  WITHOUT the actor pretending a jurisdiction's supply-licensing regime
  does not exist.

  ── Honesty discipline ──

  Coverage is reported HONESTLY (see `coverage`), the same discipline
  every sibling actor's `facts` namespace uses: a jurisdiction not in
  this table has NO spec-basis, full stop -- the advisor must not
  fabricate one, and the governor holds if it tries.

  Every `:provenance` URL in this catalog was FETCHED AND READ during
  the session that seeded it (2026-08-03); the `:legal-basis` strings
  quote what those sources actually say. Where a source could not be
  fetched, that is recorded in `:verification-note` rather than papered
  over. This is a STARTING catalog of 4 jurisdictions, not a survey of
  all ~194 -- extending it is additive: add one map, cite a real source
  that you actually read, done.

  ── What this catalog deliberately does NOT encode ──

  Numeric exemption thresholds (e.g. 'generators under N kW need no
  licence'). Those exist in every jurisdiction seeded here, they move,
  and this session did not verify a single one of them. Encoding a
  threshold you have not read is exactly the fabrication this catalog
  exists to prevent -- see `:exemption-note`. An operator extending
  this catalog with real thresholds should cite the specific
  instrument that sets them.")

(def catalog
  "iso3 -> requirement map.

    :generate-basis  -- what the jurisdiction requires before a person
                        may GENERATE electricity.
    :sell-basis      -- what it requires before a person may SELL it.
    :permits         -- the market rights a participant obtains once the
                        `:required-evidence` checklist is satisfied.
                        `trade.governor` reads this set directly.
    :required-evidence -- the G2 evidence checklist a `:license/verify`
                        must satisfy before either actuation may commit.
    :provenance      -- the source actually fetched and read."
  {"JPN"
   {:name "Japan"
    :owner-authority "経済産業省資源エネルギー庁 (ANRE, Agency for Natural Resources and Energy)"
    :legal-basis "電気事業法 (Electricity Business Act, 昭和三十九年法律第百七十号)"
    :generate-basis "発電事業: 第二十七条の二十七第一項の届出 (NOTIFICATION). 電気事業法第二条第一項第十五号は「発電事業者」を「第二十七条の二十七第一項の規定による届出をした者」と定義する -- 許可でも登録でもなく届出である"
    :sell-basis "小売電気事業: 登録 (REGISTRATION). 小売電気事業を営もうとする者は経済産業大臣の登録を受けなければならない (電気事業法第二条の二)"
    :permits #{:generate :sell}
    :provenance "https://www.enecho.meti.go.jp/category/electricity_and_gas/electric/summary/entry/ (ANRE 小売電気事業の登録申請・届出); https://www.japaneselawtranslation.go.jp/ja/laws/view/3355 (電気事業法 日本語／英語対訳)"
    :required-evidence ["需給計画記録 (supply-demand-plan-record)"
                        "小売電気事業登録記録 (retail-registration-record)"
                        "発電事業届出記録 (generation-notification-record)"
                        "計量値出所記録 (metering-provenance-record)"]
    :exemption-note "自家消費・小規模発電の届出免除の閾値は本セッションで未検証のため記載しない。実運用で使う前に該当省令を読んで補うこと。"}

   "GBR"
   {:name "United Kingdom (Great Britain)"
    :owner-authority "Gas and Electricity Markets Authority (GEMA), operating as Ofgem -- \"the Authority\" in the Act"
    :legal-basis "Electricity Act 1989 section 6 (fetched and read verbatim 2026-08-03)"
    :generate-basis "s.6(1)(a) generation licence -- \"a licence authorising a person to generate electricity for the purpose of giving a supply to any premises\", granted by the Authority"
    :sell-basis "s.6(1)(d) supply licence -- \"a licence authorising a person to supply electricity to premises\", granted by the Authority"
    :permits #{:generate :sell}
    :provenance "https://www.legislation.gov.uk/ukpga/1989/29/section/6 (Electricity Act 1989 s.6(1)(a)-(e), fetched and read verbatim 2026-08-03)"
    :required-evidence ["Supply-licence record"
                        "Generation-licence-or-exemption record"
                        "Settlement-registration record"
                        "Metering-provenance record"]
    :exemption-note "Class exemptions from the s.6 licence requirement exist for small generators/suppliers (made by order under the Act). This session did NOT fetch the exemption order, so no threshold is encoded here -- do not infer one."}

   "USA"
   {:name "United States (FERC-jurisdictional wholesale sales)"
    :owner-authority "Federal Energy Regulatory Commission (FERC)"
    :legal-basis "Federal Power Act sections 205 and 206; 18 CFR part 35; Order No. 697"
    :generate-basis "No federal generation licence as such for non-hydro/non-nuclear generation -- FERC's jurisdictional hook is the SALE, not the act of generating. State/RTO interconnection requirements apply separately and are NOT encoded here."
    :sell-basis "Market-based rate (MBR) authority. An entity seeking to make wholesale sales of energy, capacity or ancillary services at market-based rates must first obtain authorisation from the Commission by filing under FPA s.205, including a proposed market-based rate tariff."
    :permits #{:generate :sell}
    :provenance "https://www.ferc.gov/power-sales-and-markets/electric-market-based-rates/initial-applications and https://www.ferc.gov/power-sales-and-markets/electric-market-based-rates/frequently-asked-questions-faqs-market-based (FERC's own MBR pages); https://www.congress.gov/crs-product/IF11411 (CRS, The Legal Framework of the Federal Power Act)"
    :required-evidence ["Market-based-rate-authorisation record"
                        "Market-power-screen record"
                        "Tariff-filing record"
                        "Metering-provenance record"]
    :jurisdiction-note "HONEST SCOPE LIMIT: FERC's authority here reaches WHOLESALE sales in interstate commerce. RETAIL sale to an end user is regulated by the relevant STATE commission under state law, and this session verified no state regime. Do not treat this entry as a spec-basis for a retail peer-to-peer sale inside a single state."
    :exemption-note "Categories of seller exempt from, or granted blanket, MBR authority exist; none were verified this session."}

   "DEU"
   {:name "Germany"
    :owner-authority "Bundesnetzagentur (BNetzA)"
    :legal-basis "Energiewirtschaftsgesetz (EnWG) § 5 (fetched and read verbatim 2026-08-03)"
    :generate-basis "EnWG does not subject generation as such to § 5; § 5 attaches to SUPPLYING household customers. Anlagen-side obligations (Marktstammdatenregister registration, technical connection rules) apply separately and are NOT encoded here."
    :sell-basis "EnWG § 5 \"Anzeige der Energiebelieferung von Haushaltskunden; Sicherstellung der wirtschaftlichen Leistungsfähigkeit\" -- \"Energielieferanten, die Haushaltskunden mit Energie beliefern, müssen [...] die Aufnahme und Beendigung der Tätigkeit sowie Änderungen ihrer Firma bei der Bundesnetzagentur anzeigen\", plus demonstrated personelle, technische und wirtschaftliche Leistungsfähigkeit and Zuverlässigkeit der Geschäftsleitung"
    :permits #{:generate :sell}
    :provenance "https://www.gesetze-im-internet.de/enwg_2005/__5.html (EnWG § 5, fetched and read verbatim 2026-08-03)"
    :required-evidence ["BNetzA-Anzeige-Nachweis (supply-notification-record)"
                        "Leistungsfähigkeitsnachweis (capability record)"
                        "Marktstammdatenregister-Eintrag (asset-registration record)"
                        "Messwertherkunftsnachweis (metering-provenance-record)"]
    :exemption-note "§ 5 is scoped to Haushaltskunden (household customers). Supply exclusively to non-household customers is a different case and was not verified this session."}})

(defn spec-basis
  "The jurisdiction's requirement map, or nil -- nil means NO spec-basis,
  and the governor must hold any proposal that tries to place a real
  order or settle a real interval on it."
  [iso3]
  (get catalog iso3))

(defn permits
  "The market rights (`#{:generate :sell}` etc.) a participant in `iso3`
  obtains once its evidence checklist is satisfied. An unknown
  jurisdiction permits NOTHING -- never a permissive default."
  [iso3]
  (:permits (spec-basis iso3) #{}))

(defn permits?
  "May a participant in `iso3` perform `right` (`:generate` | `:sell`)?
  Unknown jurisdiction -> false."
  [iso3 right]
  (contains? (permits iso3) right))

(defn required-evidence-satisfied?
  "Does `submitted` (a set/coll of evidence keywords or strings) satisfy
  every evidence item listed for `iso3`? Missing spec-basis -> never
  satisfied."
  [iso3 submitted]
  (when-let [{:keys [required-evidence]} (spec-basis iso3)]
    (let [need (count required-evidence)
          have (count (filter (set submitted) required-evidence))]
      (= need have))))

(defn evidence-checklist [iso3]
  (:required-evidence (spec-basis iso3) []))

(defn coverage
  "Honest coverage report: how many of the requested jurisdictions
  actually have a spec-basis entry. Never report a missing jurisdiction
  as covered."
  ([] (coverage (keys catalog)))
  ([iso3s]
   (let [have (filter catalog iso3s)
         missing (remove catalog iso3s)]
     {:requested (count iso3s)
      :covered (count have)
      :covered-jurisdictions (vec (sort have))
      :missing-jurisdictions (vec (sort missing))
      :note (str "cloud-itonami-isic-3515 R0: " (count catalog)
                 " jurisdictions seeded with an official, fetched-and-read "
                 "spec-basis for electricity MARKET PARTICIPATION (generate "
                 "vs sell). This is a starting catalog, not a survey of all "
                 "~194 jurisdictions -- extend `trade.facts/catalog`, never "
                 "fabricate a jurisdiction's requirements, and never invent "
                 "an exemption threshold you have not read.")})))
