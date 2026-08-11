# Marketing Claims Checklist

Applies to every public-facing surface: the landing page, store listings, the app's own empty
states, screenshots, and anything else a person reads before they trust us with a bank statement.

## Why this exists

Finora asks people to upload their financial records. That trust is the product. A claim that
turns out to be untrue does not cost us a conversion — it costs us the only thing we are selling,
and it is the kind of damage that is cheap to avoid and expensive to repair.

This is not hypothetical. The landing page has already shipped, at various points:

- three invented testimonials with fabricated personas ("Product manager, Bengaluru") under the
  heading "What early users are saying";
- four animated counters presented as live platform metrics (`486,000+ Transactions Processed`)
  that were simply made up;
- "PDF import is on the roadmap" while PDF import was fully built;
- `₹149` and `₹249` price points that nobody had decided on, for tiers with no billing behind them;
- an "Encrypted end to end" badge, when what is true is TLS in transit and bcrypt hashing;
- a newsletter box that thanked you and discarded your address.

Every one of those passed review. None of them was malicious — they were placeholders that
outlived the intention to replace them, or a designer's comp implemented literally. That is the
failure mode this checklist is aimed at.

## The four questions

Ask these of **every sentence** on a public surface. Not every section — every claim.

1. **Is this already implemented?**
2. **If yes, can I point at the implementation?** A file, an endpoint, a migration. "I'm fairly
   sure we do that" is a no.
3. **If not, is it clearly labelled as a future capability?** Labelled where a skimmer will see it,
   not in a footnote. A status badge where the price goes, not a small tag beside a price.
4. **Could a reasonable person read this as promising more than we deliver?** Superlatives and
   comparatives are where this usually goes wrong — "bank-level", "military-grade", "complete",
   "instantly", "guaranteed".

If any answer is unclear, **rewrite the copy**. Softening it is almost always cheap; a page that
says less but is entirely true reads as more confident, not less.

## Specific rules

| Rule | Why |
| --- | --- |
| No testimonials without a real, consenting, attributable person | Fabricated social proof on a financial product is the worst-case version of this failure |
| No customer or employer logos without written permission | Third-party trademarks implying a relationship are a legal exposure, not just a copy problem |
| No usage counters unless queried live from real usage | A hardcoded "12,400 users" is a lie with a number attached |
| No price on a tier that cannot be bought | See `plans.ts` — status goes where the price would go |
| No "encrypted" without naming what and where | We have TLS in transit and bcrypt hashing; say that |
| No form that does not submit anywhere | A mailto that works beats a form that pretends |
| Unreleased ≠ unmentionable | Advertising a future plan is fine. Implying it is available is not |

## Product facts, and where to verify them

Check these before repeating them; they drift.

| Claim | Source of truth |
| --- | --- |
| Number of banks recognized | `BankRegistry` |
| Statement layout capabilities | Capability Registry in `financial-document-intelligence-principles.md` |
| Default categories | `AuthService.DEFAULT_CATEGORIES` |
| Password hashing | `SecurityConfig` (bcrypt, cost 12) |
| Statement file integrity | `ContentAddress` / `StatementStorage` — digest re-derived on read |
| Password-protected PDF support | `ImportController` (`password` request param) |
| What is purchasable | `frontend/src/pages/landing/plans.ts` — nothing but Free |
| Native mobile app status | Built through Phase 5; released to no app store |
| Self-service account deletion | Does not exist — no `DELETE /users/me`, admin only |
| Raw transaction export | Not built. Reports CSV and statement download exist |

## What is automated

`frontend/src/pages/landing/landing-claims.test.tsx` renders the real landing page and fails the
build on the patterns above that can be detected mechanically — fabricated social proof, invented
prices on unavailable tiers, banned security superlatives, dead forms.

It cannot check whether a sentence is *true*. That part is the reviewer's job, and the four
questions are the review. The test exists so that the mistakes we have **already made once** cannot
come back silently.

## When to run the checklist

- Any PR touching `frontend/src/pages/landing/**` or public copy
- Before a store submission
- Before any launch announcement
- When a feature ships, check whether a "coming soon" label somewhere is now stale — an
  out-of-date limitation is a claim too, just in the other direction
