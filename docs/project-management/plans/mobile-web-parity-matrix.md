# Mobile ↔ Web Capability Parity Matrix

**Baselined:** 2026-08-29 · **Evidence base:** `origin/main` @ `a6c96f29` (PR #506)
**Owner decision recorded 2026-08-29:** mobile is intended to become a **full replacement** for the
web application, not a companion experience. This document exists because that decision converts an
open-ended gap into a bounded, prioritised backlog with a release criterion attached.

Supersedes nothing. Feeds [`project-plan-v1.0.md`](./project-plan-v1.0.md) §9 (Mobile track) and §10
(Release gates), both of which were last updated 2026-08-23 and predate this decision.

---

## 1. Method

Measured, not estimated. Three independent passes over `origin/main`, reconciled against each other:

1. **Route inventory** — `frontend/src/App.tsx` `<Route>` table vs.
   `mobile/src/navigation/RootNavigator.tsx` + `AppTabs.tsx`.
2. **API surface** — `export const *Api` in `frontend/src/api/endpoints.ts` vs.
   `mobile/src/api/endpoints.ts`, then per-method.
3. **Call-site sweep** — every `*Api.method` reference per page/screen and per component, so a
   *declared but uncalled* method is not counted as a shipped capability.

Pass 3 matters. `mobile/src/api/endpoints.ts` declares `transactionsApi.create`, `.update`,
`.updateCategory`, `.bulkDelete` and `.bulkRecategorize`; a `git grep` across `mobile/src` finds
**zero callers** for all five. Counting the API layer alone would have overstated mobile by five
capabilities.

---

## 2. The structural finding — parity is one axis, validation is two

A three-column matrix (web / Android / iOS) was requested. The evidence says the implementation
columns cannot diverge:

- **14 `Platform.OS` references exist in `mobile/src`**, all in presentation or auth-affordance code:
  `AmountPromptModal`, `AppleSignInButton`, `AuthScreenLayout`, `DateField`, `OfflineBanner`,
  `RootWarningBanner`, `AuthEntryScreen`, `LoginScreen`, `RegisterScreen`, `ChangeEmailSheet`,
  `ChangePasswordSheet` (keyboard avoidance, date-picker presentation, banner insets, the Apple
  button).
- **Zero of them are in a financial-feature screen.**
- **Zero `.ios.tsx` / `.android.tsx` file variants exist.**

Every capability in §3 is platform-agnostic TypeScript. **Each parity item ships to both platforms
in the same commit** — there is no such thing as "add transaction on Android but not iOS."

So the matrix below has one implementation column. The platform split is real, but it lives on a
different axis and is tracked separately in §5: **implementation is single-track; validation is
per-platform.** Android has device-level proof for the shipped subset; iOS does not.

---

## 3. The matrix

Legend — **Impl:** ✅ shipped · 🟡 partial · ❌ absent.
Priority — **S** store-mandated (submission gate) · **P1** replacement-critical · **P2**
replacement-required · **P3** deferred / decision-gated.

### 3.1 Transactions

| Capability | Web | Impl | Pri | Evidence / note |
|---|---|---|---|---|
| View & paginate ledger | `Ledger.tsx` | ✅ | — | `LedgerScreen` infinite query |
| Keyword search | ✅ | ✅ | — | |
| Income/expense filter | ✅ | ✅ | — | |
| Filter by account, category, date range, amount range | ✅ | ❌ | P1 | `TransactionFilters` is **byte-identical** in both API layers; `LedgerScreen.tsx:30` only ever populates `keyword` + `type` |
| Delete transaction | ✅ | ✅ | — | |
| **Add transaction manually** | `AddTransactionModal.tsx` | ❌ | **P1** | `transactionsApi.create` declared, no caller |
| **Edit / correct transaction** | `Ledger.tsx` | ❌ | **P1** | `transactionsApi.update` declared, no caller |
| Recategorise a transaction | `updateCategory` | ❌ | P1 | declared, no caller |
| Bulk delete / bulk recategorise | API only, both | ❌ | P3 | no UI on either platform — not a gap |
| "Why this category?" explanation | `transactionsApi.explanation` | ❌ | P2 | |
| Ask Once review queue | `AskOnceCard.tsx` | ❌ | P2 | `needsReview` declared on mobile, no caller |

### 3.2 Accounts

| Capability | Web | Impl | Pri | Evidence / note |
|---|---|---|---|---|
| View accounts & balances | `Setup.tsx` | ✅ | — | |
| **Create account** (bank search, type, opening balance, credit limit, due date) | ✅ | ❌ | **P1** | `AccountsScreen.tsx:176` documents the omission as deliberate; `banksApi` absent from mobile entirely |
| Edit account | ✅ | ❌ | P1 | |
| Delete a bank/investment account | ✅ | 🟡 | P1 | investment accounts only, via `InvestmentsScreen` |
| Investment accounts + net worth snapshot | ✅ | ✅ | — | full parity |

### 3.3 Account lifecycle — **store-gated**

| Capability | Web | Impl | Pri | Evidence / note |
|---|---|---|---|---|
| **Delete account (in-app)** | `DeleteAccountModal.tsx` | ❌ | **S** | See §4. `accountLifecycleApi` absent from mobile |
| Deactivate account | `DeactivateAccountModal.tsx` | ❌ | P2 | reactivation-on-login *is* handled (`LoginScreen`) |
| Download my data (ZIP export) | `ExportDataModal.tsx` | ❌ | P2 | needs a native file-save path |

### 3.4 Import

| Capability | Web | Impl | Pri | Evidence / note |
|---|---|---|---|---|
| Stage CSV / PDF, review rows, confirm | ✅ | ✅ | — | incl. duplicate review + `confirmedNotDuplicate` |
| Password-protected PDF | ✅ | ✅ | — | |
| Re-import from statement history | ✅ | ✅ | — | |
| **Async import jobs** — submit, progress, cancel, timeline | `importJobsApi`, `ImportProgress`, `ImportTimeline`, `ImportDetail.tsx` | ❌ | **P1** | entire subsystem absent from mobile |
| Multi-account PDF confirm | `importApi.confirmMulti` | ❌ | P1 | mobile confirms single-account only |
| Resume / list staged sessions | `getSession`, `listSessions` | ❌ | P2 | |
| Failure diagnostics | `importApi.listFailures`, `FailedImportsSection` | ❌ | P2 | |
| Verification panel / report | `VerificationPanel`, `VerificationReport` | ❌ | P2 | |
| Unparseable-rows panel | `UnparseableRowsPanel` | 🟡 | P2 | type present, no panel UI |
| Statement history: list, download, delete | ✅ | ✅ | — | mobile uses the native share sheet |

### 3.5 Dashboard & insights

| Capability | Web | Impl | Pri | Evidence / note |
|---|---|---|---|---|
| Summary metrics, cash-flow chart, category donut | ✅ | ✅ | — | donut verified against the ₹35,500 fixture corpus |
| Reports (monthly, month switching) | ✅ | ✅ | — | verified against backend truth on Android |
| Insights + recurring payments | ✅ | ✅ | — | |
| Budgets (list, upsert) | ✅ | ✅ | — | |
| Goals (create, contribute, delete) | ✅ | ✅ | — | |
| Financial Journey | `FinancialJourney.tsx`, `dashboardApi.journey` | ❌ | P2 | mobile `dashboardApi` exposes `summary` only |
| Quick actions, bank/merchant logos | ✅ | ❌ | P3 | presentation |

### 3.6 Settings, security, identity

| Capability | Web | Impl | Pri | Evidence / note |
|---|---|---|---|---|
| General (low-balance threshold, timezone, theme) | ✅ | ✅ | — | |
| Change password (OTP-gated) | ✅ | ✅ | — | |
| Change email + verify | ✅ | ✅ | — | |
| Device sessions: list, revoke | ✅ | ✅ | — | |
| Categorisation / AI confidence threshold | ✅ | ✅ | — | |
| Data statistics | ✅ | ✅ | — | |
| **Change phone number** | `phoneChangeApi` | ❌ | P2 | mobile can verify a phone, not change one |
| Reset password (from email link) | `ResetPassword.tsx` | ❌ | P2 | needs deep linking |
| Verify email (from email link) | `VerifyEmail.tsx` | ❌ | P2 | needs deep linking |
| App lock (biometric), screenshot guard, root/jailbreak warning | — | ✅ | — | **mobile-only**; no web equivalent |

### 3.7 Growth, billing, integrations

| Capability | Web | Impl | Pri | Evidence / note |
|---|---|---|---|---|
| Gmail connect / status / sync / disconnect | `gmailApi`, `Settings.tsx` | ❌ | P2 | native OAuth is the hard part |
| Gmail review queue | `GmailReview.tsx` | ❌ | P2 | |
| Billing history | `billingApi`, `BillingHistory.tsx` | ❌ | P3 | |
| Referrals | `referralsApi`, `Referrals.tsx` | ❌ | P3 | |
| Premium entitlement gating | `entitlementsApi`, `PremiumFeatureGate.tsx` | ❌ | **P3 — decision-gated** | see §4.2 |

### 3.8 Out of scope for parity

Marketing and legal pages (`Landing`, `About`, `Careers`, `Contact`, `Help`, `Terms`, `Privacy`,
`RefundPolicy`, `ShippingPolicy`) are web surfaces by nature. Store listings link to them; they are
not mobile screens. **Not counted as parity gaps.**

### 3.9 Score

Counted from the tables above by script, not by hand (see §9 — the count is re-derivable).

Two rows are excluded from the denominator: **app lock / screenshot guard / root warning** (mobile-only,
no web equivalent — a bonus, not a parity row) and **bulk delete / recategorise** (absent on both
platforms — not a gap).

**49 parity capabilities: 21 shipped, 2 partial, 26 absent.** Counting a partial as a half, that is
**~45% parity** — or 43% counting only what is fully shipped. Mobile additionally has 3 capabilities
the web does not.

This is lower than the ~60% figure quoted verbally on 2026-08-29 before the rows were counted; that
earlier number was an impression, this one is arithmetic.

---

## 4. Two findings that are not ordinary backlog items

### 4.1 In-app account deletion is probably a submission gate, not a parity item

Apple App Store Review Guideline **5.1.1(v)** requires an app that supports account *creation* to
offer account *deletion* in-app. Google Play's data-deletion policy is equivalent and additionally
expects a web-accessible path.

Mobile ships `RegisterScreen` (account creation) and has **no `accountLifecycleApi` at all**. If that
reading holds against the current guideline text, this is a **hard submission gate for both stores**,
independent of the companion-vs-replacement decision — it would have been mandatory even under the
companion positioning.

**Action: verify against the live guidelines before planning around it.** Treated as priority **S**
here on the assumption it holds. Estimated 3–4 d: the OTP-gated re-auth session it needs already
exists on mobile in `ChangePasswordSheet` (`passwordChangeApi.start` → `verifyOtp` → `complete`), and
the web flow reuses exactly that session via `PasswordChangeService.consumeForAccountDeletion`.

### 4.2 Premium entitlements on iOS trigger Apple's IAP rule

Shipping `entitlementsApi` / `PremiumFeatureGate` to mobile means selling or gating a digital
subscription inside an iOS app, which brings Apple's In-App Purchase requirement and its commission
into scope, with limits on linking out to web payment.

This is a pricing and margin decision, not an engineering one, and it collides with **D-7 (pricing
scope, undefined)** and **D-28 (Plus/Premium billing, unreconciled)** — both open in
`project-plan-v1.0.md` §11. Held at **P3, decision-gated**. It should not be scheduled until D-7 and
D-28 resolve, and it is the one parity item that may be *deliberately* left web-only.

---

## 5. Validation status — the per-platform axis

Implementation is shared (§2); proof is not.

| | Android | iOS |
|---|---|---|
| Native build succeeds | ✅ | ✅ Xcode 26.6, 117 pods |
| App launches | ✅ | ✅ reaches Sign In |
| Login → dashboard → import → reports → logout | ✅ device-verified | ❌ never exercised |
| Import E2E against a known fixture corpus | ✅ 64,500→61,000 / 35,500→39,000 | ❌ |
| Duplicate detection | ✅ 3/3 EXACT | ❌ |
| Real phone-OTP verification | ✅ | ❌ **blocked — see below** |
| EAS build produced | ✅ 89 MB APK | ❌ none ever |
| EAS release config | ✅ 4 profiles | ❌ **no `ios` block in any profile** (`mobile/eas.json`) |
| E2E automation | 🟡 3 Maestro flows (login, dashboard, import) | ❌ not pointed at iOS yet |
| Crash reporting | ❌ **inert on both** — `EXPO_PUBLIC_SENTRY_DSN` not set in EAS | ❌ |

**What blocks iOS validation, precisely.** Not everything. `mobile/.maestro/seed-test-user.sh`
registers through the real `POST /auth/register` and then sets `phone_verified = true` in SQL against
a **local** database — the same bypass `e2e/fixtures/accounts.ts` uses on web. So the core journey is
runnable on the iOS Simulator today, with no Apple Developer account.

Genuinely enrolment-gated, and only these: real phone-OTP verification
(`mobile/src/lib/phoneAuth.ts` has **no reCAPTCHA path on native** — iOS app verification is silent
APNs push, and the push entitlement requires a paid account), Apple/Google Sign-In on iOS, physical
device installs, and any distribution including TestFlight.

---

## 6. Release criteria

Three distinct gates. Conflating them is what turns a 4-week beta into a 12-week one.

### Gate A — Android Closed Beta
1. P0 session-expiry cache leak merged, with a regression test that fails against the old code.
2. Export contents (CSV, PDF), import failure/retry, import→delete→re-import, offline, and
   production logout/re-login all verified.
3. `EXPO_PUBLIC_SENTRY_DSN` set in EAS — crash reporting live, not inert.
4. Rollback procedure written.
5. Backend↔mobile API compatibility confirmed against the deployed backend.

**Does not require parity.** Ships on the shipped subset.

### Gate B — iOS Closed Beta (TestFlight)
1. Gate A met (shared codebase).
2. iOS core journey validated on Simulator against the local backend.
3. `ios` block added to `mobile/eas.json`; `GOOGLE_SERVICES_PLIST` created as an EAS secret.
4. Apple Developer enrolment complete; APNs `.p8` uploaded to Firebase; phone-OTP verified on a
   physical device.
5. First EAS iOS build distributed through TestFlight.

### Gate C — Store submission (either platform)
1. Gates A/B met.
2. **In-app account deletion shipped** (§4.1).
3. Privacy policy naming the data controller (**D-12, open**), ToS, Play Data Safety, Apple App
   Privacy, listings and screenshots.
4. Android only: 12 testers × 14 continuous days on a **closed** track, then production access
   granted.

### Gate D — "Mobile replaces web"
**Every P1 and P2 row in §3 shipped and validated on both platforms**, or explicitly reclassified by
the owner. P3 rows are excluded by definition; §4.2 may be permanently excluded.

Until Gate D passes, mobile is **not** positioned publicly as a web replacement — in store listings,
marketing copy, or in-app messaging. See
[`standards/marketing-claims-checklist.md`](../standards/marketing-claims-checklist.md).

---

## 7. Cost, stated plainly

Estimates, not measured — the completion percentages they build on are measured, the day figures are
not. Discounted where mobile already has the primitives (`OptionPickerModal`, `DateField`,
`AmountPromptModal`, the `ChangePasswordSheet` OTP pattern).

| Band | Items | Est. |
|---|---|---|
| **S** | In-app account deletion | 3–4 d |
| **P1** | Add + edit + recategorise transaction; ledger filter set; accounts CRUD; async import jobs; multi-account confirm | 15–20 d |
| **P2** | Lifecycle (deactivate, export); explanation; Ask Once; import recovery + verification; Financial Journey; phone change; deep links; Gmail | 18–24 d |
| **P3** | Billing, referrals, entitlements, presentation | 6–9 d, decision-gated |
| | **Gate D total (S + P1 + P2)** | **36–48 d** |

At the plan's single-contributor ~10 h/day baseline that is **6–9 weeks of engineering**, serial with
release-readiness work rather than parallel to it — the same person cannot build accounts CRUD and
run an iOS device bring-up simultaneously.

**Consequence to decide on, not to absorb silently:** if Gate D is made a precondition of the beta,
the beta moves out by roughly two months and the Google Play 12-tester clock — three weeks that
cannot be compressed — starts two months later than it needs to. The recommendation is therefore to
**ship the beta on Gate A/B/C and close parity toward Gate D during and after it**, which is what the
owner's own two-track framing already proposes. This section exists so the number behind that choice
is on the record.

---

## 8. Open decisions this document depends on

| ID | Decision | Status |
|---|---|---|
| — | Companion vs. replacement | ✅ **Resolved 2026-08-29: replacement.** This document |
| — | Does Gate D gate the beta? | 🔴 **Open.** §7 recommends no |
| — | Is §4.1 confirmed against live store guidelines? | 🔴 **Open**, and it changes a priority band |
| D-7 / D-28 | Pricing / Plus-Premium billing scope | 🔴 Open — blocks §4.2 |
| D-10 | Who are the 12 Play testers? | 🔴 Open |
| D-12 | Named data controller in the privacy policy | 🔴 Open — Gate C |

---

## 9. Maintenance

This matrix is measured, so it goes stale on every mobile merge. Re-derive rather than hand-edit:
the three passes in §1 are mechanical and take minutes. Re-run at each gate, and fold material
changes into `project-plan-v1.0.md` §9/§10 rather than letting the two documents drift.
