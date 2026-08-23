# Research: How Can Finora Become Smarter at Understanding Indian Bank Statement PDFs?

**Status:** Research / strategic architecture exercise — no implementation implied by this document.
**Scope:** External research on Indian bank statement PDFs and document-intelligence practice, cross-checked against Finora's actual current pipeline, ending in an architecture direction and a set of concrete gaps.
**Companion reading:** [`financial-document-intelligence-principles.md`](../architecture/system-design/financial-document-intelligence-principles.md) is Finora's living governing document for this subsystem — treat it as the source of truth for current rules; this document is the research and framing layer that sits above it.

---

## 0. Headline finding

Before proposing anything: **the "bank-detection → bank-specific parser" architecture this research brief warns against is not what Finora runs today.** That model was tried, named ("Golden Rule" violations get an explicit ❌ in the codebase — e.g. `HdfcParser` is called out by name as the wrong shape), and deliberately replaced with a **capability registry** of ~15 bank-agnostic parsing behaviors (wrapped headers, `DR_CR` suffix conventions, running-balance reconstruction, composite multi-account statements, etc.) composed as peers rather than chained per-bank branches. `docs/product/specs/statement-intelligence-engine-spec.md` — which does spec a per-bank parser design — is explicitly flagged in the principles doc as **stale**, kept only as a record of the superseded intent.

So the real question this document answers is not "should Finora move to a smarter architecture" — it's already moving there — but:

1. Does the external evidence (how Indian banks actually produce PDFs, how OCR/document-AI actually performs on financial documents) validate the direction Finora has chosen?
2. Which of the genuinely open gaps in that direction — confidence scoring, Section Identity Resolver Layer 2, OCR ground-truth at the field level, the Import Decision Engine — matter most next, based on what actually breaks on real Indian bank statements?

---

## 1. How real Indian bank statement PDFs are built

### 1.1 PDF nature: three tiers, and why the tier matters more than the bank

Across HDFC, ICICI, Axis, Kotak, IndusInd, IDFC First, Yes Bank, Federal, RBL, SBI, Bank of Baroda, PNB, Union Bank, Indian Overseas Bank, Central Bank of India, and AU Small Finance Bank, the PDF a customer downloads falls into one of three tiers — and a single bank routinely produces **more than one tier** depending on channel (net-banking self-download vs. branch-generated vs. email-delivered statement):

- **Native digital** — generated directly from the bank's core banking system as a text-layer PDF (most net-banking self-service downloads). Text is selectable, but the "table" is a layout fiction: PDF has no table primitive, so what a human reads as a row of Date / Narration / Debit / Credit / Balance is, to any extraction library, a bag of independently positioned text runs that merely share an approximate y-coordinate. This is exactly the model Finora's `PdfTableLocator` is built around (row-by-y-proximity, column-by-nearest-x-anchor).
- **Hybrid** — common in credit card statements and some public-sector-bank formats: a text layer for most content plus a flattened/rasterized region for summary panels, logos, or barcodes/QR codes. Native extraction returns *some* correct text and silently *misses* the flattened region — there is no error, just an absence, which is a harder failure mode than "OCR needed" because nothing signals it.
- **Scanned/image** — a full-page raster, typically from older branch-issued statements, faxed/re-scanned documents, or some smaller/regional banks and small finance banks whose statement generators still rasterize the whole page. Zero usable text layer; the entire page must go through OCR.

**Why this matters more than "which bank":** two PDFs from the same bank (e.g. HDFC self-download vs. HDFC branch-stamped copy) can land in different tiers. This is the strongest argument for Finora's existing `RoutingTextAcquirer` design — routing on **measured document properties** (does native extraction return zero text runs?) rather than on **bank identity** — over a `bank → strategy` lookup table that would silently misroute the day a bank changes its statement generator or a customer uploads an atypical copy.

### 1.2 Bank-specific terminology and format variance (the genuine bank-dependent part)

Where bank identity *does* matter is vocabulary and layout convention, not extraction mechanics:

| Concern | Examples |
|---|---|
| Debit/withdrawal terminology | "Withdrawal", "Debit", "Dr", "Paid Out", "Cash Withdrawal" |
| Credit/deposit terminology | "Deposit", "Credit", "Cr", "Received" |
| Debit/credit column shape | Separate Debit/Credit columns vs. a single signed-amount column vs. an amount column plus a `Dr`/`Cr` suffix flag |
| UPI narration format | Same Google Pay credit renders as `UPI/CR/XXXXXXXXXXXX/GOOGLEPAY/oksbi` in an SBI statement vs. `UPI-CREDIT-XXXXXXXXXXXX-GOOGLEPAY` in an HDFC statement (`XXXXXXXXXXXX` = UPI transaction reference number) |
| Password convention | Most banks use DOB (`DDMMYYYY`); HDFC uses Customer ID; SBI net-banking uses account number; HSBC / legacy Citi India use surname-letters + `DDMM` |
| Header repetition | Some banks repeat the full column header on every page; others print it once and rely on positional memory across pages |
| Composite statements | Some institutions (HSBC is the documented Finora example) merge multiple accounts/products — e.g. savings + card — into a single PDF with multiple internal sections |

This is precisely the shape of Finora's capability registry: `DR_CR_SUFFIX` (bare and parenthesized variants), `REPEATED_HEADER`, `COMPOSITE_STATEMENTS`, header-vocabulary normalization in `CsvParser.normalizeHeaderCell`. None of these need a bank name in code — they need a named, testable behavior that any bank's document might exhibit, which is exactly the abstraction level the registry sits at.

### 1.3 Layout problems that recur across the corpus, independent of PDF tier

- **Multi-row / wrapped headers** — 2–3 physical lines of text that together form one logical header row, often centre-aligned per column rather than left-aligned (Finora's `WRAPPED_HEADER` capability, with empirically tuned gap/join thresholds derived from real failing documents, e.g. an HDFC composite fixed-deposit schedule that silently produced zero rows before the fix).
- **Wrapped/multi-line transaction descriptions** — a narration wraps to a second physical line; the parser must decide "continuation of the row above" vs. "start of a new row," using column-occupancy and left-margin heuristics rather than any structural signal (Finora's `WRAPPED_DESCRIPTION` / `LEADING_NARRATION_CONTINUATION`).
- **Multiple tables / sections per page or per document** — summary panels (interest earned, average balance, TDS deducted) interleaved with the transaction table; payment-summary panels that can be misdetected as a second transaction header (a real Axis/HDFC failure mode Finora fixed by phantom-section detection).
- **Split/merged columns** — Debit and Credit columns that collapse into one signed-amount column on some layouts, or an unlabeled "S. No." column that collides with the Date column's positional bucket (a real ICICI Savings failure Finora fixed by capping `nearestColumn` distance).
- **Same-day balance ordering ambiguity** — when several transactions post on one calendar date, the printed order is not always chronological; naively taking the day's max/min balance breaks under same-day reversals. This is a genuine financial-correctness bug class, not a layout bug, and Finora's `BalanceSequenceResolver` exists specifically because of it.

### 1.4 Text-extraction and OCR failure modes

**Native extraction** problems are almost entirely reading-order and whitespace artifacts: PDFBox's raw stream order does not always match visual left-to-right/top-to-bottom order; kerned glyphs can under/over-report run width if width is summed naively instead of taken from the last glyph's position (a subtlety Finora's `PdfTextExtractor` already accounts for); words can merge or split unpredictably at column boundaries.

**OCR** problems are a different failure family entirely, and they matter specifically for financial data:

- Character confusions with direct rupee-amount consequences: `0/O`, `1/I`, `5/S`, `8/B` — a `5` misread as `S` in an amount field doesn't fail loudly, it produces a wrong number that parses as if correct.
- Decimal-point and negative-sign loss — a missed `-` sign silently flips a debit to a credit; a shifted decimal point silently changes an amount by 10x or 100x. Both are catastrophic for financial correctness and *invisible* to the extraction layer — only downstream balance validation can catch them, if the printed running balance is present to check against.
- Published production accuracy for OCR sits meaningfully below vendor benchmark claims — commonly cited 2026 field research shows real-world accuracy around 80–95% against advertised 95–99%, and table/line-item detection specifically lags plain-text accuracy (one 2026 comparison found AWS Textract at ~82% and Google Document AI at ~40% on line-item detection on the same purchase-order corpus, despite both scoring well on plain OCR).

This is the direct justification for the finding embedded in Finora's own `ocr-document-intelligence.md`: *Tesseract's own reported per-word confidence (~0.96) was measured against a row it got financially wrong* — i.e., **OCR engine confidence does not predict financial correctness**, so Finora is right not to gate decisions on it, and right to treat deterministic balance/total reconciliation as the actual arbiter.

---

## 2. Extraction approaches: what the tooling landscape actually offers

### 2.1 Traditional PDF extraction libraries

| Library | Strength | Weakness for bank statements |
|---|---|---|
| **PDFBox** (Finora's current choice) | Full access to low-level text-position primitives (glyph x/y, font, width) — the only way to build accurate positional table reconstruction | Provides no table concept at all; every table behavior must be built on top, by hand |
| **iText** | Similar low-level access; stronger PDF-generation/manipulation feature set | Same fundamental gap — no table extraction; commercial licensing (AGPL/commercial) is a real constraint for a product to redistribute |
| **Apache Tika** | Good for document-type detection and bulk text extraction across many file formats (PDF, DOCX, images via embedded OCR parsers) | Delegates to PDFBox under the hood for PDF; does not solve table structure any better; more useful for classification/metadata than for financial row extraction |
| **Tabula / Camelot / pdfplumber** (Python ecosystem, not currently used) | Purpose-built table-region detection heuristics (ruling-line detection, whitespace-based column inference) | Not JVM-native (a real integration cost for Finora's Spring Boot backend); still heuristic, not fundamentally more reliable than a well-tuned in-house locator on documents this irregular; multiple 2026 sources note these tools "struggle with complex layouts, forms, and caption alignment" — i.e., they don't remove the bank-statement-specific tuning work, they just relocate it |

**Implication:** PDFBox plus an in-house positional table locator (Finora's current architecture) is not a stopgap on the way to a "real" table library — for irregular, non-ruled financial tables, there isn't a materially better off-the-shelf option in either ecosystem. The investment belongs in the locator's heuristics and their regression coverage, which is where Finora has already been putting it.

### 2.2 OCR and document-AI options

| Engine | Accuracy signal (2026 field reports) | Cost | Privacy/data residency | Fit for Finora |
|---|---|---|---|---|
| **Tesseract** (current) | Good on clean, high-contrast scans; degrades on complex/multi-column layouts | Free, self-hosted, no per-page cost | Fully on-infrastructure — no financial document ever leaves Finora's servers | Right choice for a privacy-sensitive personal-finance product handling raw bank statements; matches the "acquisition mechanism, not decision-maker" role it already plays |
| **PaddleOCR** | Outperforms classic Tesseract on complex layouts (~85%+ in cited comparisons) while remaining self-hostable | Free, self-hosted | Same on-infra privacy profile as Tesseract | The most promising **swap candidate**, not an addition — same deployment model, reportedly stronger on multi-column financial tables specifically; worth an isolated accuracy bake-off against Finora's existing corpus before any commitment |
| **Google Document AI** | Strong on genuinely low-quality scans (~81% vs. Textract's ~76% on sub-150-DPI input in one benchmark); table parser accuracy is inconsistent (as low as ~40% on some layouts) | ~$1.50/1,000 pages, tiered down at volume | Cloud, third-party data processor — a real, non-optional consideration for raw Indian bank statements | Not compatible with a self-hosted-only stance without a customer-facing data-processing disclosure and consent flow |
| **AWS Textract** | Stronger structured-table/line-item detection in several benchmarks (~82% line-item accuracy) | Same ~$1.50/1,000-page tier as the above | Cloud, third-party processor | Same privacy caveat as Document AI |
| **Azure Document Intelligence** | Cited as leading on printed-text accuracy in some independent benchmarks; has a purpose-built **bank-statement prebuilt model** | Comparable cloud pricing tier | Cloud, third-party processor | Notable because it is the only option here with a bank-statement-specific pretrained model — but still a third-party cloud dependency for a document class (bank statements) that is maximally privacy-sensitive |

**Recommendation direction, not a decision:** stay self-hosted (Tesseract or a PaddleOCR upgrade) for the foreseeable future given the sensitivity of the document class and Finora's existing privacy posture (encryption-at-rest work already shipped for statement storage, per project history). Cloud document-AI APIs should be evaluated only as an **opt-in, disclosed fallback** for documents both native extraction and self-hosted OCR fail on — never as the default path for raw financial statements — and only after a real cost/accuracy bake-off, not on vendor benchmark claims (which the field data above shows overstate real-world accuracy by a wide margin).

---

## 3. Document-intelligence concepts and how they map onto what Finora has already built

| Concept | Industry pattern (2026) | Finora's current equivalent |
|---|---|---|
| Document classification | Classify a document's type before extraction (e.g. bank statement vs. invoice vs. credit-card statement) | Not a separate stage — `RoutingTextAcquirer` classifies **acquisition strategy** (native vs. OCR); product/account-type classification happens later in `com.finora.imports.product` (`FinancialProductClassifier`) |
| Layout understanding | Detect layout regions (header, table, footer, summary panel) before extraction | `PdfTableLocator`'s phantom-section detection, repeated-header detection, and footer/banner exclusion are exactly this, built geometrically rather than via a trained layout model |
| Table extraction | Detect table boundaries and cell structure | The nearest-x-anchor column bucketing + y-proximity row grouping in `PdfTableLocator` |
| Entity recognition | Pull named financial entities (account number, IFSC, statement period) out of free text | `PdfMetadataExtractor`, `StatementSummaryExtractor`, `CreditCardSummaryExtractor` |
| Confidence scoring | Per-field or per-document confidence attached to every extracted value | **Not implemented.** This is Finora's most consequential real gap — see §4 |
| Human-in-the-loop correction | Route low-confidence extractions to a human reviewer; feed the correction back | The confirm-before-persist staging flow (`PreviewGenerator`/`StagingResponse`) already puts a human in the loop for *every* import, not just low-confidence ones — a stronger default than most systems researched, but there is no confidence-driven *routing* (e.g. auto-confirm the high-confidence 90% and only surface the risky 10% for review) |
| Self-learning / feedback loop | Remember a correction on a new layout and apply it to future similar documents without full retraining | `ImportRuleLearningService` does this for **merchant/category learning**, not for extraction-layout learning; `LayoutIntelligenceService`/`LayoutRegistryService` exist per `layout-intelligence-proposal.md` but are explicitly scoped to **observability only** today — recording a `layout_fingerprint` for analysis, not yet reusing it to skip re-derivation on a repeat layout |

The pattern across every row: Finora has built the **mechanical** half of each concept (the thing that produces the data) well ahead of the **decision** half (the thing that says how much to trust it and what to do about low trust). That asymmetry is the single clearest signal for where the next investment should go.

---

## 4. Current Finora limitations (the real, current gaps)

These are drawn directly from the codebase and from Finora's own architecture docs, not hypothesized:

1. **No live confidence score anywhere in the pipeline.** `ImportReliabilityStatus` is a deliberately *unweighted*, rule-based tri-state (`CLEAN` / `REVIEW_RECOMMENDED` / `NEEDS_ATTENTION`) — a real signal, but not a probability, and not comparable across documents or usable to rank "how sure are we" for any individual field. Tesseract's own OCR word-confidence is captured but explicitly not acted on, because it has been measured *not* to predict financial correctness (§1.4). Rebuilding this correctly means calibrating a score against a track record Finora doesn't have yet, which is why the principles doc gates it to a later phase rather than inventing one prematurely — a defensible sequencing choice, but the gap is real today.
2. **Section Identity Resolver Layer 2 is unbuilt.** Layer 1 (`ACCOUNT_IDENTITY_LINE`) only sees a geometric signal (an account-number-shaped line). It cannot use product/institution identity to decide whether a new header after a boundary means a genuinely new account section or a continuation — that reasoning is explicitly deferred to a Layer 2 that doesn't exist. This is the layer that would matter most for composite multi-account statements (HSBC-style) and any layout where the geometric heuristic is ambiguous.
3. **OCR ground truth can't verify financial correctness, only structural correctness.** The ground-truth matcher checks entity/product/transaction-*count*, not per-field values — confirmed by Finora's own mutation test, where a corrupted amount still passed verification. This means OCR-path correctness is currently unmeasured at the level that actually matters (did the rupee amount come out right), which is a meaningfully more serious gap than "OCR is used narrowly," because it hides regressions.
4. **The Import Decision Engine (`AUTO_CONFIRM` / `USER_REVIEW` / `BLOCK_IMPORT`) is designed but not implemented.** Every import today gets the same human-in-the-loop confirm step regardless of how confident the extraction actually was — which is safe, but doesn't scale attention: a user with twenty statements a month gets the same review burden on the ones that were trivially clean as on the ones that had a genuinely ambiguous section boundary.
5. **Hybrid PDFs (text layer + flattened region) have no explicit detection.** `RoutingTextAcquirer`'s zero-vs-nonzero trigger correctly identifies fully scanned documents, but a hybrid document — where native extraction returns *some* text but silently misses a flattened summary panel or QR-code region — produces no signal that anything was missed. This is a plausible, currently invisible failure mode worth deliberately testing for against the real corpus.
6. **Excel, image-only upload, and handwritten-statement support are listed as "Planned" with nothing built.** Lower priority than the above, but worth naming since the research prompt explicitly asked about non-PDF formats.
7. **Layout-fingerprint reuse is scoped to observability only.** `LayoutRegistryService` records a `layout_fingerprint` per document today but is explicitly barred from using it to skip re-derivation of a previously-seen layout — a conservative, correct starting point, but it means Finora captures the raw material for self-learning without yet using it.

None of these are "add OCR" or "add per-bank rules" — Finora already has both, and deliberately does not want more per-bank rules. All seven are **decision-layer** gaps sitting on top of an extraction layer that is, by the external evidence in §§1–2, already built roughly the right way for the actual failure modes real Indian bank statements produce.

---

## 5. Recommended architecture

The prompt's proposed flow (Document Understanding Layer → PDF Quality Detection + Layout Analysis → Extraction Strategy Selection → Transaction Extraction → Validation Engine → Financial Insights) is, at the acquisition/extraction end, **already Finora's architecture** — `RoutingTextAcquirer` is the quality-detection/strategy-selection step, `PdfTableLocator` is the layout-analysis step, the evidence/validator battery is the validation-engine step. The recommendation here is not to replace that shape; it's to close the loop it's currently missing at the far end:

```
                Upload
                  |
                  v
     ┌─── Document Acquisition ───┐        (EXISTS: RoutingTextAcquirer,
     │  native text vs. OCR       │         NativePdfAcquirer, TesseractEngine)
     └────────────┬────────────────┘
                  v
     ┌─── Structural Understanding ┐        (EXISTS: PdfTableLocator capability
     │  rows / columns / sections  │         registry — geometric, bank-agnostic)
     └────────────┬────────────────┘
                  v
     ┌─── Semantic Extraction ─────┐        (EXISTS: product discovery, metadata
     │  fields, products, entities │         extractors, normalizer)
     └────────────┬────────────────┘
                  v
     ┌─── Evidence & Validation ───┐        (EXISTS: evidence pipeline, balance/
     │  cross-checks, trace        │         totals validators, trace service)
     └────────────┬────────────────┘
                  │
                  v
     ┌─── Confidence Synthesis ────┐  <-- GAP #1: per-field/per-document score,
     │  (NEW)                      │      calibrated against the evidence layer's
     └────────────┬────────────────┘      own track record, not invented upfront
                  v
     ┌─── Decision Routing ────────┐  <-- GAP #4: the designed-not-built
     │  AUTO_CONFIRM / REVIEW /    │      Import Decision Engine
     │  BLOCK  (NEW)               │
     └────────────┬────────────────┘
                  v
        Confirm (human, scoped by risk)
                  │
                  v
     ┌─── Feedback Capture ────────┐  <-- extends ImportRuleLearningService's
     │  correction → layout/       │      pattern from "merchant learning" to
     │  capability learning (NEW)  │      "extraction/layout learning"; reuses
     └────────────┬────────────────┘      the LayoutRegistryService fingerprint
                  v
          Financial Insights
```

### 5.1 Components and responsibilities (additive, not a rewrite)

- **Confidence Synthesis** — a new, narrow component that reads the *existing* evidence pipeline's `EvidenceStatus`/`Correlation` outputs plus the capability registry's per-capability maturity metadata plus OCR word-confidence (already captured, currently unused) and produces one calibrated score per field and one per document. It must be trained/calibrated against Finora's own historical confirm/correction data — not hand-tuned weights — because the field evidence in §1.4 shows raw engine confidence doesn't correlate with correctness; only a score calibrated against *this pipeline's own* track record would.
- **Decision Routing (Import Decision Engine)** — consumes the confidence score plus the existing `ImportReliabilityStatus` and validator outcomes to route each staged import to `AUTO_CONFIRM`, `USER_REVIEW`, or `BLOCK_IMPORT`. This is already designed in the principles doc's Phase 2/3; the recommendation here is sequencing: build Confidence Synthesis first, run it in shadow mode (recording, not acting — mirroring the existing `ClosingBalanceEvidenceShadowObserver` pattern) against real imports for a full cycle, and only then wire routing decisions to it.
- **Field-level OCR ground truth** — extend the existing ground-truth corpus/matcher (used today for entity/product/count verification) to assert per-field values on the OCR-path fixtures specifically, closing the gap the mutation test already surfaced.
- **Hybrid-PDF detection** — a small, targeted addition to `RoutingTextAcquirer`: after native extraction, check *page coverage* of extracted text against page geometry (not a proxy metric like character density, which Finora's own doc comments show doesn't correlate with success) — specifically, does every page contain at least one text run, and does the extracted text's bounding region plausibly cover the page's content area? A page with zero text runs inside an otherwise-native document is the hybrid signal worth surfacing, even if the whole-document OCR trigger doesn't change.
- **Feedback capture / layout learning** — when a user corrects a staged import (wrong section boundary, wrong column assignment), record the correction keyed to the document's `layout_fingerprint` (already captured by `LayoutRegistryService`), following the same shape `ImportRuleLearningService` already uses for merchant/category learning. This does not mean auto-applying old corrections to new documents (that reuse is explicitly and correctly excluded in scope today) — it means building the data asset now so that a deliberate, reviewed reuse mechanism has real history to calibrate against later, consistent with how Finora already sequenced OCR (measure, then trust) rather than inventing rules ahead of evidence.

### 5.2 Data and storage requirements

- Confidence scores and decision-routing outcomes should live alongside the existing `ImportTraceService` join (import job ↔ analysis session ↔ learning events), as one more joined fact rather than a new siloed table — preserving the "two-thirds of what support needs was already recorded, the join was the gap" lesson Finora already learned once.
- Field-level OCR ground truth needs a small schema extension to the existing fixture format to carry expected per-field values, not a new corpus infrastructure.
- Layout-correction feedback needs a new table keyed by `layout_fingerprint` + field/section identifier + correction — additive to the existing `LayoutRegistryService` schema, not a replacement.

### 5.3 What this explicitly does not include

No bank-specific parser classes, no LLM-based free-form extraction as the primary path for transaction rows (LLM extraction is probabilistic and unauditable in exactly the way Finora's deterministic-validator philosophy is built to avoid — it may have a role as a *fallback disambiguator* for genuinely ambiguous section boundaries, gated behind human confirmation, but not as the default extraction mechanism for financial amounts), and no cloud OCR/document-AI as a default path given the privacy profile of raw bank statements.

---

## 6. Bank PDF comparison

This table reflects the researched *pattern* across the ecosystem rather than a per-file audit of every bank listed in the prompt (that level of specificity already exists, per-document, in Finora's evidence registry and real-corpus fixtures — see [`evidence-registry.md`](../architecture/system-design/evidence-registry.md)).

| Bank category | Typical PDF type | Text layer | OCR needed | Layout complexity | Characteristic challenges |
|---|---|---|---|---|---|
| Large private banks (HDFC, ICICI, Axis, Kotak) — net-banking self-download | Native digital | Yes | Rarely | Medium–high | Wrapped/multi-row headers, composite statements (some), bank-specific UPI narration formats, DR/CR suffix vs. separate-column conventions |
| Mid-tier private banks (IndusInd, IDFC First, Yes Bank, Federal, RBL) | Native digital, occasionally hybrid | Mostly | Occasionally (hybrid summary panels) | Medium | Less standardized column vocabulary; smaller real-document sample sizes mean more first-encounter layout variance |
| Public sector banks (SBI, Bank of Baroda, PNB, Union Bank, Indian Overseas Bank, Central Bank of India) | Mix of native and scanned/branch-issued | Variable | Yes, for branch-issued/older statements | Medium–high | Opening-balance ambiguity (confirmed real issue for Central Bank of India), repeated-header-per-page conventions, more format inconsistency across branches/eras than private banks |
| Small finance / digital-first banks (AU Small Finance Bank, others) | Native digital, generally cleaner | Yes | Rarely | Low–medium | Newer statement generators tend to be cleaner, but lower real-document sample volume means capability coverage is thinner |
| Credit card statements (any issuer) | Hybrid (text + flattened summary/reward panels) | Partial | For flattened regions | High | Summary-panel/transaction-table conflation, reward-points and minimum-due fields with their own vocabulary, multi-page continuation tables |
| Composite / multi-product statements (e.g. HSBC-style combined account) | Native digital | Yes | Rarely | High | Multiple sections in one PDF, each needing independent section-identity resolution — the concrete case motivating Section Identity Resolver Layer 2 |
| Branch-issued / scanned / faxed | Scanned image | No | Always | Variable | Full OCR pipeline required; character-confusion risk (0/O, 1/I, 5/S, 8/B) directly threatens amount correctness |

---

## 7. Long-term vision

**How can Finora become the most intelligent personal-finance statement analyzer for Indian users?**

Not by chasing bank count. The research above shows the ceiling on "support N more banks" is already low-value on its own terms — bank identity mostly determines *vocabulary*, not extraction mechanics, and Finora's capability registry already generalizes across vocabulary variance without per-bank code. The ceiling that actually matters is **trust under uncertainty**: a system that can say, honestly and per-field, how sure it is, act differently for a document it's sure about versus one it isn't, learn from every correction a real user makes, and never let an OCR engine's self-reported confidence stand in for financial correctness it hasn't actually earned.

Concretely, that means finishing the second half of the architecture Finora has already started building:

1. **Confidence Synthesis and Decision Routing**, calibrated against Finora's own history rather than invented — closing the largest and most consequential gap identified in this research.
2. **Section Identity Resolver Layer 2**, giving composite and ambiguous multi-account statements the same reliability the single-account path already has.
3. **Field-level OCR ground truth**, so "OCR is used narrowly and safely" becomes a measured claim rather than an architectural intention.
4. **A real feedback loop from correction to capability**, extending the merchant-learning pattern Finora already validated to the layout/extraction side, without skipping the evidence-gathering step Finora has consistently and correctly refused to skip elsewhere in this system.

Each of these is a bounded, sequenced extension of the direction already set in `financial-document-intelligence-principles.md` — not a new architecture, and not a coding task to start today. It's the roadmap this research recommends prioritizing when the next document-intelligence work is scoped.

---

## Sources

External research referenced in this document:

- [Bank Statement OCR India: Reconcile Faster With Smarter Tools](https://www.aiaccountant.com/blog/bank-statement-ocr-indian-banks)
- [Bank Statement OCR India: How Lenders Process Scanned and Digital PDFs](https://www.terra-insight.com/insights/bank-statement-ocr-india/)
- [Bank Reconciliation Insights: HDFC, ICICI, SBI, MT940, Narrations](https://www.terra-insight.com/insights/banking/)
- [Indian Bank Statement PDF Passwords: All Banks in One Table](https://mybankstatementanalysis.com/blog/indian-bank-statement-pdf-passwords)
- [Redesigning Banking PDF Table Extraction: a Layered Approach with Java — InfoQ](https://www.infoq.com/articles/redesign-pdf-table-extraction/)
- [Bank Statement PDF Formats Explained: Why Extraction Is Hard — LocalExtract](https://localextract.app/blog/bank-statement-pdf-format-explained)
- [TabSniper: Towards Accurate Table Detection & Structure Recognition for Bank Statements (arXiv)](https://arxiv.org/pdf/2412.12827)
- [PDFParser (Apache PDFBox) — Apache Tika wiki](https://cwiki.apache.org/confluence/display/tika/PDFParser%20(Apache%20PDFBox))
- [Best OCR Software in 2026 — Unstract](https://unstract.com/blog/best-ocr-software/)
- [AWS Textract vs Google Document AI vs Azure Document Intelligence (2026)](https://invoicedataextraction.com/blog/aws-textract-vs-google-document-ai-vs-azure-document-intelligence)
- [Document Processing for Financial Services 2026 — Extend](https://www.extend.ai/resources/real-time-document-processing-financial-services)
- [Bank statement extraction: Top tools & features guide — Mindee](https://www.mindee.com/blog/best-bank-statement-extraction-software)
- [Bank statement US extraction model — Azure Document Intelligence](https://learn.microsoft.com/en-us/azure/ai-services/document-intelligence/prebuilt/bank-statement?view=doc-intel-4.0.0)
- [Custom classification model — Azure Document Intelligence](https://learn.microsoft.com/en-us/azure/ai-services/document-intelligence/train/custom-classifier?view=doc-intel-4.0.0)

Internal sources (this repository):

- [`financial-document-intelligence-principles.md`](../architecture/system-design/financial-document-intelligence-principles.md)
- [`ocr-document-intelligence.md`](../architecture/system-design/ocr-document-intelligence.md)
- [`layout-intelligence-proposal.md`](../architecture/system-design/layout-intelligence-proposal.md)
- [`header-reconstruction-design.md`](../architecture/system-design/header-reconstruction-design.md)
- [`same-day-reversal-closing-balance-investigation.md`](../architecture/system-design/same-day-reversal-closing-balance-investigation.md)
- [`balance-chain-ordering-design.md`](../architecture/system-design/balance-chain-ordering-design.md)
- [`evidence-registry.md`](../architecture/system-design/evidence-registry.md)
- [`IMPORT_ARCHITECTURE_REVIEW.md`](../architecture/system-design/IMPORT_ARCHITECTURE_REVIEW.md)
- `docs/engineering/import/import-flow.md`, `import-verification-framework.md`, `ocr-engine-evaluation.md`
- [`import-engine-improvement-proposal.md`](import-engine-improvement-proposal.md)
