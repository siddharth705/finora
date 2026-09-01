# Fynora Brand Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finish the half-completed Finora → Fynora brand migration across every surface a user can see, without touching the identifiers that would break production if renamed.

**Architecture:** Rename user-facing text only, in independently shippable batches, each guarded by a regression test that fails if new "Finora" copy is ever added back. Internal identifiers (Java packages, env var names, Spring property prefixes) are deliberately excluded — see "Do not rename" below for the evidence.

**Tech Stack:** Spring Boot (backend), React + Vite (frontend, admin-portal), Expo / React Native (mobile).

**Spec:** No separate spec — this plan is self-contained and argues from the audit recorded in "Audit findings" below.

## Global Constraints

- The brand is **Fynora**. The legal/product name in all new user-facing copy is `Fynora`.
- The domain is **fynora.net**. `finoratech.info` was **sold** and is permanently outside this project's control — never reintroduce it, never link to it, never add it to a CSP.
- **Never edit an already-applied Flyway migration file.** Flyway validates a checksum per file on boot; editing an applied migration breaks startup. Brand text inside migrations is `COMMENT ON` metadata only — invisible to users, and explicitly out of scope.
- Java package roots stay `com.finora.*`. New code goes in `com.finora.*` too — a half-renamed package tree is worse than a consistently-old one.
- Every batch must leave the app fully working on its own.

---

## Audit findings (2026-09-02, verified against this worktree)

**The migration is roughly half done already.** Do not redo the completed parts.

| Surface | Still `Finora` | Already `Fynora` |
|---|---|---|
| `backend/src/main/java` | 136 refs (only **21 distinct quoted strings** are user-facing; the rest are comments/Javadoc) | — |
| `frontend/src` | 119 | 123 |
| `admin-portal/src` | 9 | 17 |
| `mobile/src` | 92 (case-insensitive) | 30 |

**Already migrated — leave alone:**
- Mobile app display name: `Fynora` / `Fynora Dev` (`mobile/app.config.ts`).
- iOS bundle identifier and Android applicationId: `com.fynora.app` / `com.fynora.app.dev`. This was a real, completed migration with its own runbook at `docs/engineering/mobile/mobile-setup.md` ("Bundle identifier migration"). **This is the single most dangerous string in the repo** — the backend verifies Apple ID tokens against it (`AppleLoginProperties`), and Firebase registers phone auth, Play Integrity, and the native Google OAuth clients against it. It is already correct. Do not touch it.
- Support and careers mailboxes: both already on the `fynora.net` domain (see `frontend/src/lib/contact.ts`).
- Live domains: `api.fynora.net`, `dev-api.fynora.net`, `app.fynora.net`.
- CSP `connect-src` in `frontend/public/_headers` and `admin-portal/public/_headers` — already correct; the `finoratech.info` text in those files is explanatory comment recording why the sold domain was removed. Keep the comments.

---

## Do not rename (and why)

These are decisions, not open questions. Each has a concrete failure mode.

1. **`com.finora.*` Java package tree** (~200 files). A package rename is compile-checked and internally safe, but: it produces zero user-visible benefit, and it would touch essentially every backend file at once. This repo is worked by **many concurrent sessions across ~18 live worktrees** — a 200-file rename would collide with every in-flight branch simultaneously and force a manual conflict resolution in each. The cost is real and immediate; the benefit is zero. If it is ever done, it must be its own PR on a quiet repo with every other branch merged first, and it should be done with an IDE's automated package refactor, never a find-replace.

2. **`FINORA_ENCRYPTION_KEY`, `FINORA_ENCRYPTION_ACTIVE_KEY_ID`, `FINORA_SETUP_KEY`, `FINORA_BOOTSTRAP_ENABLED`, `FINORA_API_PROXY_TARGET`, `FINORA_E2E_*`.** An environment variable name is a contract with Railway, not a string in the codebase. Renaming `FINORA_ENCRYPTION_KEY` in code without changing Railway's production environment in the same instant means the app boots with the *placeholder* dev key and **cannot decrypt existing user data** — a production data-access outage on a financial app, in exchange for a variable name no user will ever see. Same class of risk for the rest.

3. **The `finora:` Spring property prefix** (`finora.security.encryption.*` in `application.yml`). Same reasoning as (2): silently breaks at runtime unless every yml, every env var, and the Railway environment change in perfect lockstep. Not compile-checked.

4. **Applied Flyway migration files.** `V68__layout_registry.sql:158` contains "Finora" inside a `COMMENT ON COLUMN`. It is database metadata, invisible to every user. Editing the file breaks Flyway checksum validation on next boot. Leave it.

5. **`slug: 'finora-mobile'`** (`mobile/app.config.ts:66`). The EAS project slug binds the local project to the EAS/Expo build project. Changing it re-points builds at a project that does not exist. Cosmetic, internal, and build-breaking — not worth it.

**If you want any of items 1–3 done anyway,** that is a legitimate call, but it belongs in its own dedicated PR with a maintenance window and a Railway change staged in advance — never bundled into a copy change.

---

## Owner actions (cannot be done from the codebase)

- [ ] **Instagram handle.** `frontend/src/pages/landing/landing-config.ts:257-258` links the live landing page to `https://www.instagram.com/finoratech.info/` with handle `@finoratech.info`. That handle is named after a **domain that was sold**. The code change is trivial, but it needs you to first decide: rename the Instagram account to a Fynora handle, or remove the link. Until you do, the code cannot be corrected — Task 5 is blocked on this answer.
- [ ] **Email sender identity.** Confirm whether the Resend sender display name and any pre-registered SMS template header need re-registering with the provider before their brand text changes. An SMS template header registered with an Indian operator under an exact string will have messages **rejected** if the sent text no longer matches the registered template.

---

## Task 1: Add the brand regression guard (do this first)

**Files:**
- Create: `backend/src/test/java/com/finora/branding/BrandNameGuardTest.java`

**Interfaces:**
- Produces: a failing test that enumerates every user-facing "Finora" string still in the backend. Later tasks shrink its allowlist to empty. Running it first means every subsequent batch has an objective, checkable definition of done.

- [ ] **Step 1: Write the guard test**

```java
package com.finora.branding;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Fails when a user-facing string still says "Finora" instead of "Fynora".
 *
 * <p>Deliberately scoped to string literals only. Package names, imports, and comments are
 * excluded -- those are internal identifiers the brand migration does not touch, for reasons
 * recorded in docs/superpowers/plans/2026-09-02-fynora-brand-migration.md.
 */
class BrandNameGuardTest {

    /** A double-quoted Java string literal containing "Finora". */
    private static final Pattern USER_FACING_FINORA =
            Pattern.compile("\"[^\"]*Finora[^\"]*\"");

    private static final Path SOURCE_ROOT = Path.of("src/main/java");

    @Test
    @DisplayName("no user-facing string literal says Finora")
    void noUserFacingFinoraStrings() throws IOException {
        List<String> offenders = new ArrayList<>();

        try (Stream<Path> files = Files.walk(SOURCE_ROOT)) {
            files.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                List<String> lines;
                try {
                    lines = Files.readAllLines(p, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new IllegalStateException("could not read " + p, e);
                }
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    // Skip comment lines -- comments are out of scope for the brand migration.
                    String trimmed = line.strip();
                    if (trimmed.startsWith("//") || trimmed.startsWith("*")
                            || trimmed.startsWith("/*")) {
                        continue;
                    }
                    Matcher m = USER_FACING_FINORA.matcher(line);
                    while (m.find()) {
                        offenders.add(p + ":" + (i + 1) + " -> " + m.group());
                    }
                }
            });
        }

        assertThat(offenders)
                .describedAs(
                        "User-facing strings must say Fynora, not Finora. "
                                + "Fix the copy; do not add to an allowlist.")
                .isEmpty();
    }
}
```

- [ ] **Step 2: Run it and record the real baseline**

```bash
cd backend && ./mvnw test -Dtest=BrandNameGuardTest
```

Expected: **FAIL**, listing roughly 21 distinct offending strings. Copy that list — it is the exact worklist for Task 2. If the count is far from 21, the codebase moved since this plan was written; re-audit before continuing.

- [ ] **Step 3: Commit the failing guard**

Commit it failing, and disable it in CI for exactly one commit if the pipeline blocks on red tests:

```bash
git add backend/src/test/java/com/finora/branding/BrandNameGuardTest.java
git commit -m "test: add brand-name guard for user-facing strings (currently failing)"
```

---

## Task 2: Rename backend user-facing strings

**Files:**
- Modify: the ~21 files named by Task 1's failure output.

**Interfaces:**
- Consumes: the offender list from Task 1.
- Produces: `BrandNameGuardTest` passing.

- [ ] **Step 1: Fix each string from the Task 1 list**

Work down the list literally. Examples of what these look like (from the audit):

```
"Finora could not find a transaction table anywhere in this statement."
"Finora is processing a lot of statement imports right now. Please try again in a moment."
"That Google account is already connected to another Finora account."
"Merchants Finora has recognized from your transactions."
"Is Finora healthy?"
```

Each becomes the same sentence with `Fynora`. **Do not reword** — this task changes one word per string and nothing else, so the diff is reviewable at a glance.

Two need judgement rather than a blind swap:
- `"Finora API"` and `"Finora Admin"` — these are OpenAPI/Swagger document titles. Rename them, but check first whether any generated client or saved Postman collection keys off the title.
- The **email FROM identity** (a `"Finora <...>"` sender string in the email config — grep for it rather than pasting the address around). This is not body copy. Renaming the display-name half is safe; the address half sits on the email provider's shared test domain, which is a separate pre-existing issue. Change only the display name here, and flag the test-domain sender to the owner rather than fixing it in this plan.

- [ ] **Step 2: Run the guard**

```bash
cd backend && ./mvnw test -Dtest=BrandNameGuardTest
```

Expected: PASS.

- [ ] **Step 3: Run the full backend suite**

```bash
cd backend && ./mvnw test
```

Expected: PASS. Some tests assert on exact message text and will need the same one-word change — that is the guard working correctly, not a regression.

- [ ] **Step 4: Commit**

```bash
git add backend/src
git commit -m "feat: rename user-facing backend copy to Fynora"
```

---

## Task 3: Rename frontend user-facing copy

**Files:**
- Modify: files under `frontend/src` containing `Finora` (119 refs at audit time; a mix of copy and comments).

- [ ] **Step 1: List the real copy strings**

```bash
grep -rn "Finora" frontend/src --include="*.ts" --include="*.tsx" | grep -v "^\s*//" | grep -v "^\s*\*"
```

The frontend is already 123-refs-Fynora vs 119-refs-Finora, so expect the remaining ones to cluster in a few untouched pages rather than spread evenly.

- [ ] **Step 2: Change user-visible copy only**

Rename strings that render to a user: headings, body copy, alt text, `<title>`, meta descriptions, button labels, error messages. Leave comments alone — they are out of scope and inflate the diff.

- [ ] **Step 3: Verify nothing user-visible still says Finora**

```bash
grep -rn "Finora" frontend/src --include="*.tsx" | grep -vE "^\S+:\s*(//|\*)"
```

Expected: only comment lines remain.

- [ ] **Step 4: Build and eyeball the landing page**

```bash
cd frontend && npm run build
```

Then start the dev server and confirm the landing page, login, and any legal/footer pages read "Fynora" throughout.

- [ ] **Step 5: Commit**

```bash
git add frontend/src
git commit -m "feat: rename user-facing frontend copy to Fynora"
```

---

## Task 4: Rename admin-portal and mobile user-facing copy

**Files:**
- Modify: `admin-portal/src` (9 refs), `mobile/src` (the remaining `Finora` copy strings).

- [ ] **Step 1: Admin portal**

```bash
grep -rn "Finora" admin-portal/src
```

Only 9 references — rename the user-visible ones, leave comments.

- [ ] **Step 2: Mobile**

```bash
grep -rn "Finora" mobile/src --include="*.ts" --include="*.tsx" | grep -v "^\s*//"
```

Known live inconsistency from the audit: `"Open in the Finora app"` sits alongside `"Can't reach Fynora. Check your connection and try again."` in the same shipped app. Fix the stragglers.

Leave `"Firebase is transactional, Finora's own JWT is the real session"` if it is a comment — verify before changing.

- [ ] **Step 3: Run both test suites**

```bash
cd admin-portal && npm test
```

```bash
cd mobile && npx jest
```

Expected: PASS. Mobile snapshot tests asserting on copy will need updating — review each diff rather than blanket-updating snapshots, so a real regression cannot hide in the noise.

- [ ] **Step 4: Commit**

```bash
git add admin-portal/src mobile/src
git commit -m "feat: rename user-facing admin and mobile copy to Fynora"
```

---

## Task 5: Fix the stale Instagram link (BLOCKED on owner)

**Files:**
- Modify: `frontend/src/pages/landing/landing-config.ts:257-258`

**Do not start this task until the owner has answered the Instagram question above.** The live landing page currently links to an Instagram account named after a sold domain.

- [ ] **Step 1: Apply the owner's decision**

If the account is renamed to a Fynora handle, update both lines to the new handle. If the account is being retired, remove the entry and confirm the landing page renders correctly with one fewer social link — check for a layout that assumes a fixed number of icons.

- [ ] **Step 2: Verify**

```bash
grep -rn "finoratech" frontend/src
```

Expected: no results.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/pages/landing/landing-config.ts
git commit -m "fix: point the landing page social link at the Fynora account"
```

---

## Task 6: Deep-link scheme (OPTIONAL — read the risk first)

**Files:**
- Modify: `mobile/app.config.ts:73`, plus every `finora://` construction site.

The mobile deep-link scheme is still `finora` / `finora-dev`, and links like `finora://email-change-verify` are generated by the backend and **emailed to users**.

**The risk:** a link already sitting in a user's inbox uses the old scheme. If the app ships with only the new scheme registered, those links stop resolving and the user sees "no app can open this". Email-change-verify and password-reset links are exactly the flows where a dead link is most damaging.

**The safe sequence, if you do it:**
1. Register **both** schemes in `app.config.ts` and ship that build first. Both work.
2. Wait past the expiry window of the longest-lived emailed link (check the token TTL in the backend before choosing this window — do not guess).
3. Switch the backend to generate only `fynora://`.
4. Only after another full release cycle, drop the old scheme.

**Recommendation: skip this.** A URL scheme is invisible to users in normal operation — it appears only in an OS "open in app?" prompt. The multi-release coordination cost is real; the benefit is close to zero. Do it only if you have a specific reason.

---

## Plan completion checklist

- [ ] `BrandNameGuardTest` passes.
- [ ] No user-visible string in backend, frontend, admin-portal, or mobile says "Finora".
- [ ] `grep -rn "finoratech" frontend/src admin-portal/src` returns nothing (comments in `_headers` are the deliberate exception and stay).
- [ ] `com.finora.*` packages untouched.
- [ ] No `FINORA_*` environment variable renamed.
- [ ] No existing Flyway migration file modified.
- [ ] Mobile bundle identifier still `com.fynora.app` — unchanged by this work.
- [ ] Full test suites pass on backend, frontend, admin-portal, and mobile.

## Notes for the reviewer

- **This plan deliberately does less than "rename everything."** The audit found that the genuinely dangerous strings — the bundle identifier, the domains, the CSP, the support mailboxes — were **already migrated correctly** in earlier work. What remains is copy, plus a small set of internal identifiers that should stay as they are. Renaming those identifiers carries production-outage risk for zero user benefit; the reasoning is recorded in "Do not rename" above rather than left implicit.
- **The one genuinely live defect found by this audit** is the landing page's Instagram link pointing at a handle named for a sold domain (Task 5). That is a trust issue, not a cosmetic one, and it is the highest-value item in this plan.
- Batch order is deliberate: the guard test (Task 1) comes first so every later batch has an objective definition of done, and Task 5 is isolated because it is blocked on an owner decision rather than on engineering.
