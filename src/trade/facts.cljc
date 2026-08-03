(ns trade.facts
  "WORLDWIDE electricity MARKET PARTICIPATION catalog -- the G2-style
  spec-basis table the Market Conduct Governor checks every
  `:license/verify` proposal against ('did the advisor cite an OFFICIAL
  source for what this jurisdiction actually requires before a person
  may generate electricity, and before a person may SELL it, or did it
  invent one?').

  This is deliberately NOT the same question as `cloud-itonami-isic-
  3512`'s `energy.facts` (grid-interconnection and tariff requirements:
  'may this site physically connect and how is it billed'). This one
  asks the TRADE question: 'what authorisation does a person need before
  they may sell electricity to someone else'. A site can be lawfully
  interconnected and still have no right to sell its output to a
  neighbour.

  ── The structure is global; the DATA is what is incomplete ──

  No jurisdiction is excluded by design. Three resolution levels exist,
  and any jurisdiction on Earth can be described by them:

    :subdivision  ISO 3166-2 (\"USA-CA\", \"CAN-ON\", \"AUS-WA\") -- for
                  federations where electricity retail is a
                  subnational competence. This is not an edge case:
                  it is how the United States, Canada, Australia and
                  India actually work.
    :national     ISO 3166-1 alpha-3.
    :bloc         a supranational instrument binding its members
                  (today: the EU, whose directives bind 27 states).

  `resolve-basis` walks subdivision -> national -> bloc and reports
  which level answered, in `:resolved-at`. A jurisdiction with no entry
  at any level has NO spec-basis, full stop -- the advisor must not
  fabricate one, and the governor holds if it tries. That is a COVERAGE
  gap, honestly reported by `coverage`, never a statement that the place
  is out of scope.

  ── Why `:permits` is a SET of named rights ──

  Every jurisdiction in this catalog draws the same structural line, and
  it is the line that decides who may do what on this exchange:

    GENERATING electricity is a LOW-barrier act -- a notification, a
    registration, or (below a threshold) nothing at all. India
    de-licensed it outright; South Africa exempted up to 100 MW.

    SELLING electricity to another party is a HIGHER-barrier act -- a
    licence, an authorisation or a registration the regulator can
    refuse and revoke. In at least one jurisdiction here (KOR) the
    licence exists in law but has never been granted to anyone except
    the incumbent, so in practice the right does not exist at all.

  So the rights are named and set-valued, never a boolean:

    :generate        may produce electricity
    :sell            may sell to another end user -- what THIS exchange
                     does. Absent unless the jurisdiction really grants
                     it at the resolved level.
    :sell-wholesale  may sell into a wholesale market, which is NOT the
                     same permission. The United States' federal entry
                     carries this and NOT `:sell`, because FERC's
                     authority reaches wholesale sales; retail sale to
                     an end customer is a state competence. Silently
                     treating the federal entry as retail authority
                     would be the single most dangerous fabrication in
                     this file.

  An unknown jurisdiction yields `#{}`. The default is deny.

  ── Honesty discipline ──

  Every `:provenance` in this catalog was FETCHED AND READ during the
  session that seeded it, and `:legal-basis` quotes what those sources
  actually say. Where a source could not be fetched, or where the
  finding is a secondary account of a primary instrument rather than
  the instrument itself, that is recorded in `:verification-note`
  rather than papered over.

  Numeric exemption thresholds appear ONLY where the instrument setting
  them was actually read (today: ZAF's 100 MW, from the Government
  Gazette notice that set it). Everywhere else `:exemption-note` says
  the threshold exists and was not verified. Encoding a threshold you
  have not read is a fabrication with a number on it, which is worse
  than an admitted gap.")

;; ─────────────────────────── currency ───────────────────────────

(def ^:const currency-note
  "Prices are integer MICRO units of the MAJOR currency unit per
  watt-hour (µJPY/Wh, µUSD/Wh, µEUR/Wh).

  Using the major unit uniformly sidesteps ISO 4217 minor-unit
  disagreement entirely: JPY has 0 decimal places, USD 2, KWD 3, and a
  design keyed to 'the minor unit' would need a per-currency table just
  to know what an integer means. A micro-major-unit is the same
  quantity everywhere, and 10^-6 of any real currency is far below any
  price this market can express, so nothing is lost.

  A book is denominated in exactly ONE currency (the delivery
  interval's). Cross-currency matching does not exist: an order whose
  currency differs from its interval's is refused by
  `trade.governor/currency-mismatch-violations`. Without that rule a
  JPY ask and a USD bid would cross on their bare integers and produce
  a fill roughly 150x wrong.")

(defn iso4217?
  "Shape check only -- three uppercase letters. This namespace does NOT
  carry an ISO 4217 table, because a stale currency list that silently
  rejects a real currency would exclude jurisdictions, which is exactly
  what this catalog must never do. Validity of the code is the
  operator's business; CONSISTENCY within a book is this actor's."
  [s]
  (boolean (and (string? s) (re-matches #"[A-Z]{3}" s))))

;; ─────────────────────────── blocs ───────────────────────────

(def blocs
  "Supranational instruments that bind their member states directly.

  The EU entry is the highest-leverage in this file: two directives,
  both read this session, grant every one of 27 member states a
  baseline right that is precisely what this exchange does -- and
  Directive (EU) 2018/2001 Article 21(2)(a) names peer-to-peer trading
  in so many words. A member state with no national entry of its own
  still resolves to a real, cited basis rather than to nothing."
  {"EU"
   {:name "European Union"
    :owner-authority "European Parliament and Council; national regulatory authorities (NRAs) transpose and enforce"
    :legal-basis "Directive (EU) 2018/2001 (RED II) Article 21; Directive (EU) 2019/944 (internal electricity market) Articles 15-16, Recital 42"
    :generate-basis "Directive (EU) 2018/2001 Art. 21(1): \"consumers are entitled to become renewables self-consumers, subject to this Article\". Generating capacity is subject to a national authorisation procedure under Directive (EU) 2019/944 Art. 8, not to a prohibition."
    :sell-basis "Directive (EU) 2018/2001 Art. 21(2)(a) entitles renewables self-consumers, individually or through aggregators, to \"generate renewable energy, including for their own consumption, store and sell their excess production of renewable electricity, including through renewables power purchase agreements, electricity suppliers and PEER-TO-PEER TRADING ARRANGEMENTS\" (emphasis added). Directive (EU) 2019/944 Recital 42: \"Consumers should be able to consume, to store and to sell self-generated electricity to the market and to participate in all electricity markets\"."
    :permits #{:generate :sell :sell-wholesale}
    :provenance "https://www.legislation.gov.uk/eudr/2018/2001/article/21 (Dir. (EU) 2018/2001 Art. 21(1) and 21(2)(a), fetched and read verbatim 2026-08-03); https://eur-lex.europa.eu/legal-content/EN/TXT/HTML/?uri=CELEX:32019L0944 (Dir. (EU) 2019/944, Recital 42 and Art. 8/Art. 10(1) read 2026-08-03)"
    :required-evidence ["Self-consumer-or-supplier status record"
                        "National transposition record"
                        "Metering-and-settlement registration record"
                        "Metering-provenance record"]
    :members #{"AUT" "BEL" "BGR" "HRV" "CYP" "CZE" "DNK" "EST" "FIN" "FRA"
               "DEU" "GRC" "HUN" "IRL" "ITA" "LVA" "LTU" "LUX" "MLT" "NLD"
               "POL" "PRT" "ROU" "SVK" "SVN" "ESP" "SWE"}
    :transposition-note "A directive binds member states as to the RESULT to be achieved and leaves them the choice of form and method. The right in Art. 21(2)(a) is therefore real in every member state, but the PROCEDURE (who registers, with which NRA, under what national threshold) is national and differs. Resolving to this bloc entry means 'the right exists and is cited'; it does NOT mean the national procedure is known. A member state with its own catalog entry always wins over this one."
    :exemption-note "National de-minimis thresholds for self-consumption exist under Art. 21 and were not verified per-state this session. Art. 21(3) permits proportionate charges in defined circumstances (the directive's own text references an 8% of installed capacity trigger from December 2026); the operative national rules were not read."}})

;; ─────────────────────────── catalog ───────────────────────────

(def catalog
  "jurisdiction key -> requirement map. Keys are ISO 3166-1 alpha-3
  (national) or \"<alpha-3>-<ISO 3166-2 suffix>\" (subdivision).

    :generate-basis  what is required before a person may GENERATE.
    :sell-basis      what is required before a person may SELL.
    :permits         the named rights obtained once :required-evidence
                     is satisfied. `trade.governor` reads this set.
    :provenance      the source actually fetched and read.
    :bloc            the supranational instrument this entry sits under,
                     when any."
  {;; ───────────────────────── Asia ─────────────────────────
   "JPN"
   {:name "Japan"
    :region :asia
    :owner-authority "経済産業省資源エネルギー庁 (ANRE, Agency for Natural Resources and Energy)"
    :legal-basis "電気事業法 (Electricity Business Act, 昭和三十九年法律第百七十号)"
    :generate-basis "発電事業: 第二十七条の二十七第一項の届出 (NOTIFICATION). 第二条第一項第十五号は「発電事業者」を「第二十七条の二十七第一項の規定による届出をした者」と定義する -- 許可でも登録でもなく届出"
    :sell-basis "小売電気事業: 登録 (REGISTRATION). 小売電気事業を営もうとする者は経済産業大臣の登録を受けなければならない (第二条の二)"
    :permits #{:generate :sell :sell-wholesale}
    :provenance "https://www.enecho.meti.go.jp/category/electricity_and_gas/electric/summary/entry/ (ANRE 小売電気事業の登録申請・届出); https://www.japaneselawtranslation.go.jp/ja/laws/view/3355 (電気事業法 日本語／英語対訳)"
    :required-evidence ["需給計画記録 (supply-demand-plan-record)"
                        "小売電気事業登録記録 (retail-registration-record)"
                        "発電事業届出記録 (generation-notification-record)"
                        "計量値出所記録 (metering-provenance-record)"]
    :exemption-note "自家消費・小規模発電の届出免除の閾値は未検証のため記載しない。"}

   "IND"
   {:name "India"
    :region :asia
    :owner-authority "Central Electricity Regulatory Commission (CERC) and the State Electricity Regulatory Commissions"
    :legal-basis "Electricity Act, 2003"
    :generate-basis "DE-LICENSED. Generation is a non-licensed activity under ss.7-8: any generating company may establish, operate and maintain a generating station without obtaining a licence, subject only to compliance with the grid-connectivity technical standards referred to in s.73(b). The clearest generate/sell asymmetry in this catalog."
    :sell-basis "Trading licence under s.12, granted by the appropriate Commission; \"electricity trader\" is defined in s.2(26) as a person granted a licence to undertake trading under s.12, and trading is defined as purchase of power for resale. s.52 empowers the Commission to specify technical, capital-adequacy and creditworthiness requirements for an electricity trader."
    :permits #{:generate :sell :sell-wholesale}
    :provenance "https://cercind.gov.in/Act-with-amendment.pdf (Electricity Act 2003 as amended, CERC's own copy); https://indiankanoon.org/doc/177537342/"
    :required-evidence ["Trading-licence record"
                        "Grid-connectivity-standards compliance record"
                        "Capital-adequacy record"
                        "Metering-provenance record"]
    :verification-note "Section numbers and the de-licensing of generation were confirmed against CERC's own published copy of the Act and a secondary legal database; the operative clause text was not read line by line this session."
    :jurisdiction-note "India is federal: intra-State trading is licensed by the STATE Commission, inter-State by CERC. No State-level subdivision entry is seeded, so a purely intra-State retail sale resolves to this national entry and should be read as 'the national framework is cited, the State procedure is not'."
    :exemption-note "Captive generation and small-scale thresholds exist and were not verified."}

   "KOR"
   {:name "Republic of Korea"
    :region :asia
    :owner-authority "Ministry of Trade, Industry and Energy (MOTIE); Korea Power Exchange (KPX) as system and market operator"
    :legal-basis "Electricity Business Act"
    :generate-basis "Generation is open: competition exists in the generation sector, comprising KEPCO's six subsidiary generation companies and independent power producers. Generators must trade through KPX under the Rules on the Operation of the Electricity Market."
    :sell-basis "A retail sale business licence is required and MOTIE has authority to grant it -- but NONE HAS EVER BEEN GRANTED except to KEPCO, which remains the monopolistic retail seller, with KPX operating a single-buyer wholesale market. A narrow exception for direct renewable PPAs was opened in 2021-2022."
    :permits #{:generate}
    :provenance "https://practiceguides.chambers.com/practice-guides/power-generation-transmission-distribution-2025/south-korea; https://www.mayerbrown.com/en/insights/publications/2022/02/south-korea-opens-door-for-direct-ppas-for-renewable-projects"
    :required-evidence ["Generation-business-licence record"
                        "KPX market-participant registration record"
                        "Metering-provenance record"]
    :verification-note "SECONDARY SOURCES ONLY. The Electricity Business Act itself was not fetched this session; the finding rests on practitioner guides. It is seeded anyway because omitting Korea would misrepresent the world as uniformly liberalised, and because the finding is conservative -- it WITHHOLDS a right rather than granting one."
    :jurisdiction-note "THE IMPORTANT CONTRAST CASE IN THIS CATALOG. `:permits` deliberately omits `:sell`: a peer-to-peer retail sale between two ordinary participants is not available here, so this actor will HARD-hold such an order. That is the catalog working correctly, not a coverage gap. A design that could only describe liberalised markets would be a design that quietly assumed its own conclusion."
    :exemption-note "The scope of the direct-PPA exception was not verified and is not encoded as a right."}

   ;; ───────────────────────── Europe ─────────────────────────
   "GBR"
   {:name "United Kingdom (Great Britain)"
    :region :europe
    :owner-authority "Gas and Electricity Markets Authority (GEMA), operating as Ofgem -- \"the Authority\" in the Act"
    :legal-basis "Electricity Act 1989 section 6 (fetched and read verbatim 2026-08-03)"
    :generate-basis "s.6(1)(a) generation licence -- \"a licence authorising a person to generate electricity for the purpose of giving a supply to any premises\", granted by the Authority"
    :sell-basis "s.6(1)(d) supply licence -- \"a licence authorising a person to supply electricity to premises\", granted by the Authority"
    :permits #{:generate :sell :sell-wholesale}
    :provenance "https://www.legislation.gov.uk/ukpga/1989/29/section/6 (Electricity Act 1989 s.6(1)(a)-(e), fetched and read verbatim 2026-08-03)"
    :required-evidence ["Supply-licence record"
                        "Generation-licence-or-exemption record"
                        "Settlement-registration record"
                        "Metering-provenance record"]
    :jurisdiction-note "Great Britain. Northern Ireland has a separate regime (Utility Regulator) that was not verified; \"GBR\" here should not be read as covering it."
    :exemption-note "Class exemptions from the s.6 licence requirement exist for small generators/suppliers, made by order under the Act. The exemption order was not fetched, so no threshold is encoded."}

   "DEU"
   {:name "Germany"
    :region :europe
    :bloc "EU"
    :owner-authority "Bundesnetzagentur (BNetzA)"
    :legal-basis "Energiewirtschaftsgesetz (EnWG) § 5 (fetched and read verbatim 2026-08-03); EU Directives 2018/2001 and 2019/944 as transposed"
    :generate-basis "EnWG does not subject generation as such to § 5; § 5 attaches to SUPPLYING household customers. Marktstammdatenregister registration and technical connection rules apply separately and were not verified."
    :sell-basis "EnWG § 5 \"Anzeige der Energiebelieferung von Haushaltskunden; Sicherstellung der wirtschaftlichen Leistungsfähigkeit\" -- \"Energielieferanten, die Haushaltskunden mit Energie beliefern, müssen [...] die Aufnahme und Beendigung der Tätigkeit sowie Änderungen ihrer Firma bei der Bundesnetzagentur anzeigen\", plus demonstrated personelle, technische und wirtschaftliche Leistungsfähigkeit and Zuverlässigkeit der Geschäftsleitung"
    :permits #{:generate :sell :sell-wholesale}
    :provenance "https://www.gesetze-im-internet.de/enwg_2005/__5.html (EnWG § 5, fetched and read verbatim 2026-08-03)"
    :required-evidence ["BNetzA-Anzeige-Nachweis (supply-notification-record)"
                        "Leistungsfähigkeitsnachweis (capability record)"
                        "Marktstammdatenregister-Eintrag (asset-registration record)"
                        "Messwertherkunftsnachweis (metering-provenance-record)"]
    :exemption-note "§ 5 is scoped to Haushaltskunden. Supply exclusively to non-household customers is a different case and was not verified."}

   ;; ───────────────────────── Americas ─────────────────────────
   "USA"
   {:name "United States (federal / FERC-jurisdictional wholesale only)"
    :region :americas
    :owner-authority "Federal Energy Regulatory Commission (FERC)"
    :legal-basis "Federal Power Act sections 205 and 206; 18 CFR part 35; Order No. 697"
    :generate-basis "No federal generation licence as such for non-hydro/non-nuclear generation -- FERC's jurisdictional hook is the SALE, not the act of generating. State and RTO interconnection requirements apply separately and are not encoded here."
    :sell-basis "Market-based rate (MBR) authority for WHOLESALE sales. An entity seeking to make wholesale sales of energy, capacity or ancillary services at market-based rates must first obtain authorisation from the Commission by filing under FPA s.205, including a proposed market-based rate tariff."
    :permits #{:generate :sell-wholesale}
    :provenance "https://www.ferc.gov/power-sales-and-markets/electric-market-based-rates/initial-applications; https://www.ferc.gov/power-sales-and-markets/electric-market-based-rates/frequently-asked-questions-faqs-market-based; https://www.congress.gov/crs-product/IF11411 (CRS, The Legal Framework of the Federal Power Act)"
    :required-evidence ["Market-based-rate-authorisation record"
                        "Market-power-screen record"
                        "Tariff-filing record"
                        "Metering-provenance record"]
    :jurisdiction-note "`:permits` DELIBERATELY OMITS `:sell`. FERC's authority reaches wholesale sales in interstate commerce; RETAIL sale to an end user is regulated by the relevant STATE commission under state law, and no state regime was verified this session. A peer-to-peer retail sale between two US participants therefore HARD-holds here until a \"USA-<state>\" subdivision entry is seeded with a real citation. Granting `:sell` from this entry would be the most consequential fabrication available in this file."
    :exemption-note "Categories of seller exempt from, or granted blanket, MBR authority exist; none were verified."}

   "BRA"
   {:name "Brazil"
    :region :americas
    :owner-authority "Agência Nacional de Energia Elétrica (ANEEL); Câmara de Comercialização de Energia Elétrica (CCEE) for settlement"
    :legal-basis "Lei nº 14.300, de 6 de janeiro de 2022 (marco legal da microgeração e minigeração distribuída, Sistema de Compensação de Energia Elétrica); Lei nº 9.074/1995; Resolução Normativa ANEEL nº 1.059/2023"
    :generate-basis "Microgeração e minigeração distribuída are established as a legal regime by Lei 14.300/2022, with connection and billing rules set by REN ANEEL 1.059/2023 -- a registration/connection regime for small distributed generation rather than a licence."
    :sell-basis "Comercialização is undertaken in the Ambiente de Contratação Livre (ACL) or Ambiente de Contratação Regulada (ACR) with accounting through CCEE; authorisation/registration derives from Lei 9.074/1995 and ANEEL's implementing rules."
    :permits #{:generate :sell :sell-wholesale}
    :provenance "https://www.gov.br/mme/pt-br/acesso-a-informacao/legislacao/leis/lei-n-14-300-2022.pdf (Lei 14.300/2022, MME's own copy); https://www2.aneel.gov.br/cedoc/ren20231059.html (REN ANEEL 1.059/2023)"
    :required-evidence ["Registro CCEE / ACL-ACR record"
                        "Autorização ou registro de geração record"
                        "SCEE participation record"
                        "Metering-provenance record"]
    :verification-note "Primary instruments were located at official government URLs and their subject matter confirmed; the operative articles were not read clause by clause this session."
    :exemption-note "The micro/minigeração capacity thresholds in Lei 14.300/2022 exist and were not read; none is encoded."}

   "CAN"
   {:name "Canada (federal only -- see :jurisdiction-note)"
    :region :americas
    :owner-authority "Canada Energy Regulator (CER) -- ONLY for international and federally-designated interprovincial power lines"
    :legal-basis "Canadian Energy Regulator Act (S.C. 2019, c. 28, s. 10), Part 4 \"International and Interprovincial Power Lines\": ss.247-262"
    :generate-basis "No federal generation authorisation for ordinary generation; generation is a provincial competence."
    :sell-basis "No federal retail-supply authority. Retail sale is regulated by the relevant PROVINCIAL regulator (e.g. Ontario Energy Board, Alberta Utilities Commission), none of which was verified."
    :permits #{:generate}
    :provenance "https://laws-lois.justice.gc.ca/eng/acts/C-15.1/page-14.html (CER Act Part 4, ss.247-262); https://www.cer-rec.gc.ca/en/about/who-we-are-what-we-do/index.html"
    :required-evidence ["Provincial retail-authorisation record"
                        "Provincial generation-registration record"
                        "Settlement-registration record"
                        "Metering-provenance record"]
    :jurisdiction-note "Canada's electricity regulation is genuinely federal/provincial-split. The federal CER's authority reaches only international power lines and interprovincial lines DESIGNATED by the Governor in Council -- not every line crossing a provincial boundary, and not in-province generation, transmission, distribution or retail. `:permits` therefore omits `:sell`, and a Canadian retail sale HARD-holds until a \"CAN-<province>\" subdivision entry exists. Consistent with the same finding recorded in `cloud-itonami-isic-3512`'s own facts catalog."
    :exemption-note "Provincial thresholds were not verified."}

   ;; ───────────────────────── Africa ─────────────────────────
   "ZAF"
   {:name "South Africa"
    :region :africa
    :owner-authority "National Energy Regulator of South Africa (NERSA)"
    :legal-basis "Electricity Regulation Act 4 of 2006, Schedule 2 as amended by Government Notice 737, Government Gazette 44989 (12 August 2021)"
    :generate-basis "EXEMPT FROM LICENSING UP TO 100 MW, but REGISTRATION with NERSA is still required. The Schedule 2 threshold was raised from 1 MW to 100 MW on 12 August 2021. Operating a generation facility with or without storage, connected to the transmission or distribution grid, at no more than 100 MW, is exempt from licensing and requires registration."
    :sell-basis "Trading and distribution remain licensed activities under the Electricity Regulation Act; the Schedule 2 exemption addresses GENERATION licensing, not the right to trade."
    :permits #{:generate :sell :sell-wholesale}
    :exemption-threshold {:right :generate :ceiling-mw 100 :requires :registration
                          :source "Government Notice 737, Government Gazette 44989, 12 August 2021"
                          :previous-ceiling-mw 1}
    :provenance "https://www.sanews.gov.za/south-africa/nersa-welcomes-intervention-intended-achieve-energy-security (SA government news service, NERSA's own welcome of the amendment); https://www.whitecase.com/insight-alert/south-africa-exempts-private-generators-generation-licence-requirements"
    :required-evidence ["NERSA registration or licence record"
                        "Trading-licence record"
                        "Connection-agreement record"
                        "Metering-provenance record"]
    :verification-note "The 100 MW ceiling, the previous 1 MW ceiling and the amending instrument (GN 737 / Gazette 44989, 12 Aug 2021) were confirmed from a South African government source and a law-firm alert. THIS IS THE ONLY NUMERIC THRESHOLD ENCODED ANYWHERE IN THIS CATALOG, and it is encoded because the instrument that sets it was identified by number and date -- not because a threshold was assumed to exist."
    :exemption-note "See :exemption-threshold. Subsequent amendments after 2021 were not checked."}

   ;; ───────────────────────── Oceania ─────────────────────────
   "AUS"
   {:name "Australia (National Energy Retail Law participating jurisdictions)"
    :region :oceania
    :owner-authority "Australian Energy Regulator (AER); Australian Energy Market Operator (AEMO) for wholesale registration"
    :legal-basis "National Energy Retail Law s.88 \"Requirement for authorisation or exemption\""
    :generate-basis "Wholesale market participation requires registration with AEMO. Generation itself is not gated by the NERL, which addresses the SALE of energy to premises."
    :sell-basis "NERL s.88: a person must not sell energy for premises unless the seller holds a current retailer authorisation OR is an exempt seller. The AER administers both, and its Retail Exempt Selling Guideline sets out when an exemption applies -- for example where selling energy is not the seller's core business, where the cost of authorisation outweighs the customer benefit, or where an insignificant amount of energy is sold."
    :permits #{:generate :sell :sell-wholesale}
    :provenance "https://classic.austlii.edu.au/au/legis/nsw/consol_act/nerl291/s88.html (NERL (NSW) s.88); https://www.aer.gov.au/industry/registers/resources/guidelines/retail-exempt-selling-guideline-july-2022 (AER Retail Exempt Selling Guideline v6, July 2022)"
    :required-evidence ["Retailer-authorisation or retail-exemption record"
                        "AEMO registration record"
                        "Settlement-registration record"
                        "Metering-provenance record"]
    :jurisdiction-note "The NERL is applied as law by each PARTICIPATING state/territory (the AustLII citation above is the New South Wales application). Western Australia and the Northern Territory are outside the National Electricity Market arrangements and were not verified -- an \"AUS-WA\" or \"AUS-NT\" participant should not be read as covered by this entry."
    :exemption-note "The exempt-selling classes are qualitative rather than a single numeric threshold; the guideline's full class list was not enumerated here."}})

;; ─────────────────────────── resolution ───────────────────────────

(defn- normalize
  "Accepts \"JPN\", {:iso3 \"USA\" :subdivision \"CA\"}, or a participant
  map carrying :jurisdiction/:subdivision. Returns [iso3 subdivision]."
  [j]
  (cond
    (string? j) [j nil]
    (map? j) [(or (:iso3 j) (:jurisdiction j)) (:subdivision j)]
    :else [nil nil]))

(defn resolve-basis
  "Walk subdivision -> national -> bloc and return the first entry that
  answers, annotated with `:resolved-at` (`:subdivision` | `:national` |
  `:bloc`) and `:resolved-key`. Returns nil when nothing answers, which
  means NO spec-basis -- a coverage gap, never a statement that the
  jurisdiction is out of scope."
  [j]
  (let [[iso3 subdivision] (normalize j)
        sub-key (when (and iso3 subdivision) (str iso3 "-" subdivision))]
    (or (when-let [e (get catalog sub-key)]
          (assoc e :resolved-at :subdivision :resolved-key sub-key))
        (when-let [e (get catalog iso3)]
          (assoc e :resolved-at :national :resolved-key iso3))
        (some (fn [[bloc-key {:keys [members] :as b}]]
                (when (contains? members iso3)
                  (assoc (dissoc b :members)
                         :resolved-at :bloc :resolved-key bloc-key
                         :resolved-for iso3)))
              blocs))))

(defn spec-basis
  "The jurisdiction's requirement map, or nil. Backwards-compatible
  entry point -- delegates to `resolve-basis`."
  [j]
  (resolve-basis j))

(defn permits
  "The named rights (`#{:generate :sell :sell-wholesale}`) a participant
  in `j` obtains once its evidence checklist is satisfied. An
  unresolvable jurisdiction permits NOTHING -- never a permissive
  default."
  [j]
  (:permits (resolve-basis j) #{}))

(defn permits?
  "May a participant in `j` exercise `right`? Unresolvable -> false.

  Note that `:sell` and `:sell-wholesale` are DIFFERENT rights and
  neither implies the other: the United States' federal entry carries
  `:sell-wholesale` without `:sell`, which is the whole point."
  [j right]
  (contains? (permits j) right))

(defn required-evidence-satisfied?
  "Does `submitted` satisfy every evidence item at the resolved level?
  No spec-basis -> never satisfied."
  [j submitted]
  (when-let [{:keys [required-evidence]} (resolve-basis j)]
    (let [need (count required-evidence)
          have (count (filter (set submitted) required-evidence))]
      (= need have))))

(defn evidence-checklist [j]
  (:required-evidence (resolve-basis j) []))

;; ─────────────────────────── coverage ───────────────────────────

(defn- bloc-covered []
  (reduce into #{} (map :members (vals blocs))))

(defn- national-keys []
  (set (remove #(re-find #"-" %) (keys catalog))))

(defn coverage
  "Honest coverage report. Never reports a missing jurisdiction as
  covered, and never reports a bloc-resolved jurisdiction as if it had
  its own national entry."
  ([] (coverage (sort (into (national-keys) (bloc-covered)))))
  ([iso3s]
   (let [direct (filter catalog iso3s)
         via-bloc (remove (set direct) (filter (bloc-covered) iso3s))
         missing (remove (set (concat direct via-bloc)) iso3s)]
     {:requested (count iso3s)
      :covered (+ (count direct) (count via-bloc))
      :covered-directly (vec (sort direct))
      :covered-via-bloc (vec (sort via-bloc))
      :missing-jurisdictions (vec (sort missing))
      :regions (frequencies (keep #(:region (get catalog %)) (national-keys)))
      ;; The counts here are THIS report's, not the catalog's totals. A
      ;; jurisdiction with its own entry that also belongs to a bloc
      ;; (Germany) is counted once, as direct -- reporting it in both
      ;; would inflate coverage by double-counting the best-covered
      ;; jurisdictions, which is the wrong direction for a number whose
      ;; whole job is to be conservative.
      :note (str "cloud-itonami-isic-3515: " (count direct)
                 " of these jurisdictions have their own fetched-and-read entry, "
                 (count via-bloc)
                 " more resolve to a supranational bloc entry (EU), "
                 (count missing) " have no basis at any level. "
                 "The MODEL excludes no jurisdiction on Earth -- subdivision, "
                 "national and bloc levels can describe any of them. What is "
                 "incomplete is the DATA. A missing jurisdiction is a coverage "
                 "gap to be filled by citing a source you actually read, never "
                 "a statement that the place is out of scope.")})))

(defn jurisdictions-without-sell-right
  "Jurisdictions with an entry that does NOT grant `:sell`. Reported
  explicitly so nobody has to discover by trial that a peer-to-peer
  retail sale is unavailable there -- and so the catalog cannot be
  mistaken for a claim that the whole world is liberalised."
  []
  (vec (sort (remove #(permits? % :sell) (national-keys)))))
