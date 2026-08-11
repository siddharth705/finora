# Privacy policy research — background for a professional consultation

**Not legal advice.** This is background research to prepare for D-12/D-7 (see
`docs/engineering/project-plan-v1.0.md` §11) — a real lawyer needs to review before anything here
becomes a published policy. Every open question below is something to bring to them, not something
resolved on your behalf.

**Context assumed:** Finora is operated by an individual (no registered company or legal entity) in
India. It ingests user-uploaded bank statements (PDF/CSV), stores statement bytes in Cloudflare R2
and structured data in Railway Postgres, and performs no lending, payments, trading, or investment
advice — read-only statement ingestion, categorization, budgeting, reporting. Launching on both
Apple App Store and Google Play.

---

## 1. India's DPDP Act 2023 and DPDP Rules 2025

**Commencement is phased, and this is the single most consequential fact here.** The Rules were
gazetted 14 Nov 2025, but the bulk of what a privacy policy is actually built around — notice
content, consent standards, breach notification, retention/deletion, data principal rights — does
not become legally enforceable until **13 May 2027**. Only administrative provisions (the Data
Protection Board's establishment) are live now.

- **Open question for the lawyer:** should Finora be DPDP-shaped *now*, ahead of the 2027
  enforcement date, purely for app-store approval and user trust — independent of legal obligation?
  (Likely yes in practice, but that's a business call worth having stated explicitly.)

**An individual is a "Data Fiduciary" in exactly the same legal sense a company is** — the Act's
definition of "person" includes individuals, and does not carve out solo operators. There is no
size or entity-type exemption.

**What a DPDP-compliant notice needs, once relevant:** a standalone, plain-language document (not
buried in ToS) with an itemised list of data collected, stated purpose, and mechanisms to withdraw
consent, exercise data-principal rights, and file a complaint — offered in English or any Eighth
Schedule language.

**Grievance contact, not a formal DPO:** a full Data Protection Officer is required only for
government-notified "Significant Data Fiduciaries" — no SDF notifications have been issued to date,
and nothing suggests a personal-finance app at Finora's scale would be one. An ordinary Data
Fiduciary only needs to publish contact details for someone able to answer grievances on the
operator's behalf, per Section 13(1).

**DPDP has no "sensitive personal data" category at all** — unlike GDPR or India's older SPDI Rules,
DPDP applies uniform obligations regardless of data type. There is no statutory tier that singles
out financial data for heavier treatment under DPDP itself.

- **Open question / conflicting sources:** the older **IT Rules 2011 (SPDI Rules)** — which *do*
  explicitly list bank/financial account data as "sensitive personal data" requiring consent and
  security practices like an ISO 27001-style safe harbor — remain technically in force until DPDP's
  substantive provisions trigger their repeal. Whether SPDI Rules apply to Finora *today* (Aug 2026)
  is unresolved by this research and needs direct confirmation.

## 2. RBI / financial-sector rules

**RBI's payment-system data localization circular (2018)** is scoped to licensed Payment System
Operators and their direct service providers — it covers transaction/settlement message data for
entities that actually operate a payment system. On its face this does not reach Finora, which
neither processes payments nor operates a payment system.

**The Account Aggregator (NBFC-AA) framework** governs consent-based *API* retrieval of financial
data directly from a regulated institution by a licensed intermediary. Finora's model — a user
uploads a file they already downloaded themselves — does not fetch data from a bank via API or
credentials, which on these facts appears to sit outside AA licensing scope.

- **Open question:** this reading (self-uploaded document vs. API-pulled aggregation) needs
  explicit confirmation against the actual NBFC-AA Master Direction text or an RBI FAQ, not just
  inference — a lawyer should confirm Finora is outside this perimeter before you rely on it.

No other RBI master direction was found that plausibly reaches a non-lending, non-payments app that
only reads statements the user already has. That said, an absence of a hit in this research is not
proof of absence — worth a direct confirmation rather than treating silence as clearance.

## 3. Google Play requirements

- Privacy policy must be a live, public URL — not a PDF, not gated.
- The **Data Safety form** requires declaring "Financial info" sub-categories (payment info,
  purchase history, credit score, other financial info like salary/debts) if collected, with
  prominent in-app disclosure separate from the form itself.
- A separate **Financial Features declaration** is required of every app, even to certify "no
  financial features" — Finora would likely select that option since it doesn't lend, move money,
  or trade, but Google makes the final call at review.
- **Open question, practically urgent:** Google requires an **Organization-type developer account**
  (with a D-U-N-S number) for apps providing "financial products and services" — the example list
  given is banking/loans/trading/crypto, not budgeting apps, but Google's own documentation doesn't
  explicitly clarify whether a read-only statement-categorization app counts. This affects whether
  the Play Console listing can even be set up as an individual account and is worth confirming
  directly with Play support before relying on an assumption either way.

## 4. Apple App Store requirements

- The App Privacy "nutrition label" has explicit **Financial Info** categories (payment info,
  credit info, other financial info) that must be declared per data type collected.
- Standard privacy-policy content requirements under Guideline 5.1.1(i): what's collected, third
  parties involved, retention/deletion, and how consent is revoked.

- **Open question, potentially the highest-stakes item in this whole research pass:** two App Review
  guidelines are directly relevant to Finora's situation as a solo, unregistered operator —
  - **5.1.1(ix):** apps in "highly regulated fields... or that require sensitive user information
    should be submitted by a legal entity... and not by an individual developer."
  - **3.2.1(viii):** apps for "financial trading, investing, or money management should be submitted
    by the financial institution performing such services."

  Read literally, either could be read to reach a budgeting app. In practice, real solo-operator
  finance-adjacent apps do exist on the App Store (one example found: **ByJo**, a personal-finance
  app by an individual developer — though it appears to store data locally rather than server-side,
  a materially simpler posture than Finora's), which suggests Apple applies these narrowly to actual
  banking/brokerage/lending apps rather than budgeting tools. But no authoritative Apple statement
  drawing that line was found — this is inferred from precedent, not confirmed policy. **Worth
  confirming directly with Apple Developer Support or a lawyer before submission**, since the
  downside (a late rejection requiring incorporation) has real time cost against the store-review
  critical path already tracked in the project plan (§9a).

## 5. Precedent

Indian personal-finance apps found (Money View, INDmoney, Walnut) are all run by registered
companies, not individuals — this may itself be informative: it's plausible Indian finance-adjacent
apps skew corporate precisely because of the Play/Apple organizational pressures and RBI-adjacent
risk above, which is a relevant data point for a "should Finora incorporate before launch" business
decision, though that decision is explicitly not mine to make.

No Indian solo-operator bank-statement app with a public privacy policy was found to use as a direct
structural template. That's a genuine precedent gap, not just a search miss.

---

## Summary — questions to bring to the lawyer, in priority order

1. **Apple 5.1.1(ix) / 3.2.1(viii)** — does Finora, as a read-only budgeting app with no legal
   entity, actually trigger these, and if there's real risk, should you incorporate before
   submission? (Time-sensitive against the store-review critical path.)
2. **Google Play Organization-account requirement** — does "financial info" handling force an
   Organization account (D-U-N-S number) even without lending/payments/trading?
3. Is Finora expected to be DPDP-shaped *now* for app-store/trust purposes, ahead of the May 2027
   enforcement date?
4. Does the SPDI Rules regime (2011) still apply to Finora today, and if so what does its security
   safe-harbor require of a solo operator?
5. Does the RBI Account Aggregator framework reach an app that only ingests user-uploaded files (no
   API pull, no credential scraping) — confirm Finora sits outside AA licensing scope.
6. Confirm there's no DPDP-specific elevated tier for financial data (research found none, but flag
   for explicit sign-off given one secondary source claimed otherwise).

Sources are cited inline in the research pass this document summarizes; ask if you want the full
citation list rather than this synthesis.
