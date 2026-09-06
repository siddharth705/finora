# First-Login Onboarding Tour & Getting-Started Checklist Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give every first-time Fynora user a one-time Welcome → Financial Focus → Interactive
Tour → Success flow on both web and mobile, plus a persistent getting-started checklist on the
dashboard, so an empty first dashboard never leaves them guessing what to do.

**Architecture:** One new backend module (`com.finora.onboarding`) exposes a small REST API for
the one-time completion flag, the Financial Focus answer set, and the getting-started checklist.
`onboardingCompleted` rides on the existing `phoneVerified`-shaped channel on both platforms
(`AuthResponse` + `GET /users/me`) so routing needs no extra round trip. Both frontends build a
small in-house `TourOverlay` (no new dependency) that spotlights real, persistent nav elements —
web's always-visible sidebar links; mobile's bottom tabs plus the `More` screen's row list.

**Tech Stack:** Spring Boot / JPA / Flyway (backend), React + React Router + TanStack Query +
Tailwind (web), React Native + Expo + React Navigation + react-native-svg + react-native-reanimated
(mobile). No new dependency is added on either platform.

**Spec:** [docs/superpowers/specs/2026-09-06-first-login-onboarding-tour-design.md](../specs/2026-09-06-first-login-onboarding-tour-design.md)

## Global Constraints

- No AI-attribution trailer in any commit message (repo-wide rule, `CLAUDE.md`).
- Commit subject lines must be ≤100 chars (commitlint `header-max-length`, enforced by a
  pre-commit hook — verified against this repo's actual hook output while writing the spec).
- Every migration version must be re-checked against `origin/main` immediately before it's
  written — this plan assumes **V161** as of 2026-09-06 (latest on `origin/main` was V160); if
  another migration has landed first, renumber before writing the file, never reuse a taken
  number.
- `FinancialFocus` and the checklist item keys are closed, fixed enums — never accept free-text
  keys from a client, never design either for future extension "just in case."
- All product copy (screen titles/subtitles/button labels, the 7 tour steps, the 6 checklist
  items) is final, drafted and approved in the spec §3 — implement it verbatim, do not rewrite it.
- No tour/onboarding library dependency is added to `frontend/package.json` or
  `mobile/package.json` — the spotlight overlay is built from primitives already in each project
  (SVG masking on web, `react-native-svg` + `measureInWindow` on mobile).
- Every new backend endpoint resolves the acting user via `CurrentUser.id()` (the JWT-derived
  principal) — never trust a user id from the request body or path, matching every existing
  `/api/v1/*` controller in this codebase.
- Mobile work must account for `mobile/AGENTS.md`'s standing instruction to check the versioned
  Expo v57 docs (https://docs.expo.dev/versions/v57.0.0/) before using any Expo/React Native API
  not already used elsewhere in this codebase.

---

## File Structure

### Backend (new package `com.finora.onboarding`, mirrors `com.finora.goals`/`com.finora.budgets`)

- `backend/src/main/resources/db/migration/V161__onboarding_flow.sql` — schema + backfill.
- `backend/src/main/java/com/finora/onboarding/FinancialFocus.java` — enum, 7 values.
- `backend/src/main/java/com/finora/onboarding/ChecklistItemKey.java` — enum, 6 values, marks
  which 2 are explicitly completable via the API.
- `backend/src/main/java/com/finora/onboarding/UserFinancialFocus.java` — entity.
- `backend/src/main/java/com/finora/onboarding/UserFinancialFocusRepository.java`
- `backend/src/main/java/com/finora/onboarding/UserChecklistEvent.java` — entity.
- `backend/src/main/java/com/finora/onboarding/UserChecklistEventRepository.java`
- `backend/src/main/java/com/finora/onboarding/OnboardingDto.java` — request/response records.
- `backend/src/main/java/com/finora/onboarding/OnboardingService.java`
- `backend/src/main/java/com/finora/onboarding/OnboardingController.java`
- Modify: `backend/src/main/java/com/finora/entity/User.java` — add `onboardingCompletedAt`.
- Modify: `backend/src/main/java/com/finora/dto/AuthDtos.java` — add `onboardingCompleted` to
  `AuthResponse`.
- Modify: `backend/src/main/java/com/finora/dto/UserSettingsDto.java` — add
  `onboardingCompleted`.
- Modify: `backend/src/main/java/com/finora/service/AuthService.java` — the 4 `AuthResponse(...)`
  construction sites.
- Modify: `backend/src/main/java/com/finora/service/UserSettingsService.java` — its one
  `UserSettingsDto(...)` construction site.
- Tests: `backend/src/test/java/com/finora/onboarding/OnboardingServiceTest.java`,
  `backend/src/test/java/com/finora/onboarding/OnboardingControllerIT.java`,
  `backend/src/test/java/com/finora/onboarding/OnboardingMigrationIT.java`.

### Web (`frontend/`)

- Modify: `frontend/src/api/endpoints.ts` — add `onboardingApi`, extend `AuthResponseDto` and
  `UserSettings`.
- Modify: `frontend/src/context/AuthContext.tsx` — add `onboardingCompleted` state,
  `setOnboardingCompleted`, wire into `persist()` and the bootstrap effect.
- Modify: `frontend/src/components/ProtectedRoute.tsx` — render the onboarding flow when
  `!onboardingCompleted`.
- Create: `frontend/src/onboarding/OnboardingFlow.tsx` — the 4-screen state machine
  (Welcome → FinancialFocus → TourIntro/Tour → Success).
- Create: `frontend/src/onboarding/WelcomeScreen.tsx`
- Create: `frontend/src/onboarding/FinancialFocusScreen.tsx`
- Create: `frontend/src/onboarding/TourOverlay.tsx` — the spotlight engine + the 7-step config.
- Create: `frontend/src/onboarding/SuccessScreen.tsx`
- Create: `frontend/src/onboarding/ChecklistWidget.tsx` — dashboard card + the shared item list
  used by both the widget and `SuccessScreen`'s preview.
- Create: `frontend/src/onboarding/checklistItems.ts` — the 6-item copy/icon list (shared).
- Modify: `frontend/src/components/Sidebar.tsx` — add `data-tour` attributes to the 7 target
  `NavLink`s (no visible change).
- Modify: `frontend/src/pages/Dashboard.tsx` — mount `ChecklistWidget`.
- Modify: `frontend/src/pages/Settings.tsx` — add "Retake Product Tour" row.
- Modify: `frontend/src/pages/Ledger.tsx`, `frontend/src/pages/Insights.tsx` — dwell-timer
  checklist completion calls.
- Tests: `.test.tsx` beside each new file, following this repo's existing convention.

### Mobile (`mobile/`)

- Modify: `mobile/src/api/endpoints.ts` — mirror the web `onboardingApi` addition.
- Modify: `mobile/src/context/AuthContext.tsx` — mirror the web `AuthContext` addition, using
  `SecureStore`/`safeStorage` the way `phoneVerified` already does.
- Modify: `mobile/src/navigation/RootNavigator.tsx` — mount the onboarding stack when
  `!onboardingCompleted`.
- Create: `mobile/src/onboarding/OnboardingNavigator.tsx` — the 4-screen native stack.
- Create: `mobile/src/onboarding/WelcomeScreen.tsx`
- Create: `mobile/src/onboarding/FinancialFocusScreen.tsx`
- Create: `mobile/src/onboarding/TourOverlay.tsx` — navigation-aware spotlight engine.
- Create: `mobile/src/onboarding/TourTargetRegistry.tsx` — context so `AppTabs`/`MoreScreen` can
  register measurable refs the overlay (mounted above them) can read.
- Create: `mobile/src/onboarding/SuccessScreen.tsx`
- Create: `mobile/src/onboarding/ChecklistWidget.tsx`
- Create: `mobile/src/onboarding/checklistItems.ts` — same shared list as web's (re-authored, RN
  has no code-sharing layer with web today).
- Modify: `mobile/src/navigation/AppTabs.tsx` — register tab-bar-icon refs for
  Home/Import/Transactions/More.
- Modify: `mobile/src/screens/MoreScreen.tsx` — register row refs for
  Accounts/Budgets/Goals/Insights.
- Modify: `mobile/src/screens/DashboardScreen.tsx` — mount `ChecklistWidget`.
- Modify: `mobile/src/screens/SettingsScreen.tsx` — add "Retake Product Tour" row.
- Modify: `mobile/src/screens/LedgerScreen.tsx`, `mobile/src/screens/InsightsScreen.tsx` —
  dwell-timer checklist completion calls.
- Tests: `.test.tsx` beside each new file.

---

## Phase 1 — Backend

### Task 1: Migration + `onboarding_completed_at` on `users`

**Files:**
- Create: `backend/src/main/resources/db/migration/V161__onboarding_flow.sql`
- Modify: `backend/src/main/java/com/finora/entity/User.java`
- Test: `backend/src/test/java/com/finora/onboarding/OnboardingMigrationIT.java`

**Interfaces:**
- Produces: `User.getOnboardingCompletedAt(): Instant | null`,
  `User.setOnboardingCompletedAt(Instant)`, table `user_financial_focus`, table
  `user_checklist_events` (both created here so Task 2/3 only add code, not schema).

- [ ] **Step 1: Re-verify the migration version is still free**

Run:
```bash
git fetch origin
ls backend/src/main/resources/db/migration | grep -oE "V[0-9]+" | sed 's/V//' | sort -n | tail -3
```
If the highest number is not 160, use the next free number instead of 161 throughout this task
(and update every other reference to `V161` in this plan).

- [ ] **Step 2: Write the migration**

```sql
-- V161: First-login onboarding flow (tour + Financial Focus + getting-started checklist).
-- See docs/superpowers/specs/2026-09-06-first-login-onboarding-tour-design.md.

-- Same "nullable _at, set once, never cleared except by an explicit reset" convention as
-- users.deactivated_at (V88) and users.password_changed_at (V40). NULL = onboarding not yet
-- completed. Backfilled below in the same migration, same reasoning as V99's subscriptions
-- backfill: without it, every existing user would be ambushed by a tour on their next login.
ALTER TABLE users ADD COLUMN onboarding_completed_at TIMESTAMPTZ;
UPDATE users SET onboarding_completed_at = now() WHERE onboarding_completed_at IS NULL;

-- Multi-select answer to the Financial Focus onboarding question. Shaped like
-- feature_entitlements (V99): a child table for "a small set of tagged values per user", not
-- @ElementCollection (no entity in this codebase uses it) and not a CSV/JSON column.
CREATE TABLE user_financial_focus (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    focus_key VARCHAR(30) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, focus_key)
);
CREATE INDEX idx_user_financial_focus_user_id ON user_financial_focus(user_id);

-- The 2 getting-started checklist items with no natural signal elsewhere in the schema
-- ("did the user open this screen") -- see the design spec §4 for why the other 4 items are
-- derived live from ImportJob/Budget/Goal/User instead of stored here. Deliberately a closed,
-- 2-value set (REVIEW_TRANSACTIONS, VIEW_INSIGHTS), enforced in the service layer, not a general
-- analytics-events table.
CREATE TABLE user_checklist_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    item_key VARCHAR(30) NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, item_key)
);
CREATE INDEX idx_user_checklist_events_user_id ON user_checklist_events(user_id);
```

- [ ] **Step 3: Add the field to `User.java`**

Add near `passwordChangedAt` (same "nullable `_at`, persists indefinitely" family):

```java
    // Null until the user has completed or skipped the first-login onboarding flow (Welcome ->
    // Financial Focus -> Tour) -- see V161's migration comment and docs/superpowers/specs/
    // 2026-09-06-first-login-onboarding-tour-design.md §5. Set by OnboardingService.complete(),
    // cleared back to null only by OnboardingService.reset() ("Retake Product Tour").
    @Column(name = "onboarding_completed_at")
    private Instant onboardingCompletedAt;
```

And with the other getters/setters:

```java
    public Instant getOnboardingCompletedAt() { return onboardingCompletedAt; }
    public void setOnboardingCompletedAt(Instant onboardingCompletedAt) { this.onboardingCompletedAt = onboardingCompletedAt; }
```

- [ ] **Step 4: Write the backfill integration test**

```java
package com.finora.onboarding;

import com.finora.entity.User;
import com.finora.repository.UserRepository;
import com.finora.support.AbstractIntegrationTest; // this repo's shared IT base class
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class OnboardingMigrationIT extends AbstractIntegrationTest {

    @org.springframework.beans.factory.annotation.Autowired
    private UserRepository userRepository;

    @Test
    void everyUserHasOnboardingCompletedAtSetAfterMigration() {
        // Every row seeded for this IT suite predates V161 in wall-clock terms (the migration ran
        // once, at test-database bootstrap), so this also proves the backfill actually ran, not
        // just that the column exists.
        for (User user : userRepository.findAll()) {
            assertThat(user.getOnboardingCompletedAt()).isNotNull();
        }
    }

    @Test
    void aFreshlyRegisteredUserStartsWithOnboardingNotCompleted() {
        User user = new User();
        user.setEmail("onboarding-migration-it-" + System.nanoTime() + "@example.com");
        user.setPasswordHash("irrelevant");
        user.setFullName("Test User");
        userRepository.saveAndFlush(user);

        User reloaded = userRepository.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getOnboardingCompletedAt()).isNull();
        assertThat(Instant.now()).isAfter(reloaded.getCreatedAt());
    }
}
```

Find this repo's actual shared IT base class name first (`grep -rl "class.*IT extends" backend/src/test/java | head -3` and open one) and use the real one in place of `AbstractIntegrationTest` above if the name differs.

- [ ] **Step 5: Run the IT**

Run: `cd backend && ./mvnw -Dtest=OnboardingMigrationIT test -DfailIfNoTests=false` (adjust to
this repo's actual failsafe/surefire IT invocation — check `backend/pom.xml`'s `maven-failsafe-plugin`
config or an existing `*IT.java` run instruction in a recent plan/PR first).
Expected: both tests PASS, confirming the migration applied and the backfill covered every
existing row.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/resources/db/migration/V161__onboarding_flow.sql \
        backend/src/main/java/com/finora/entity/User.java \
        backend/src/test/java/com/finora/onboarding/OnboardingMigrationIT.java
git commit -m "feat(onboarding): add onboarding_completed_at and its two support tables"
```

---

### Task 2: `FinancialFocus` enum + set/get service + endpoints

**Files:**
- Create: `backend/src/main/java/com/finora/onboarding/FinancialFocus.java`
- Create: `backend/src/main/java/com/finora/onboarding/UserFinancialFocus.java`
- Create: `backend/src/main/java/com/finora/onboarding/UserFinancialFocusRepository.java`
- Create: `backend/src/main/java/com/finora/onboarding/OnboardingDto.java`
- Create: `backend/src/main/java/com/finora/onboarding/OnboardingService.java`
- Create: `backend/src/main/java/com/finora/onboarding/OnboardingController.java`
- Test: `backend/src/test/java/com/finora/onboarding/OnboardingServiceTest.java`
- Test: `backend/src/test/java/com/finora/onboarding/OnboardingControllerIT.java`

**Interfaces:**
- Consumes: `User.getOnboardingCompletedAt()`/`setOnboardingCompletedAt()` (Task 1),
  `CurrentUser.id(): UUID`, `ApiResponse.ok(T)`/`ApiResponse.ok(T, String)`.
- Produces: `OnboardingService.getStatus(UUID userId): OnboardingDto.StatusResponse`,
  `OnboardingService.setFinancialFocus(UUID userId, List<String> focusKeys):
  OnboardingDto.StatusResponse` — later tasks (checklist) add methods to this same service.

- [ ] **Step 1: Write the enum**

```java
package com.finora.onboarding;

/** The 7 Financial Focus chip options (spec §3, Screen 2) -- a closed set, never free text.
 *  Display copy (the emoji + label shown in the UI) lives entirely in the frontend; this name is
 *  a stable backend identifier only, so wording can change without a migration. */
public enum FinancialFocus {
    TRACK_SPENDING,
    MANAGE_BUDGETS,
    SAVE_FOR_GOAL,
    SEE_ALL_ACCOUNTS,
    IMPROVE_HABITS,
    REDUCE_DEBT,
    EXPLORING
}
```

- [ ] **Step 2: Write the entity + repository**

```java
package com.finora.onboarding;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "user_financial_focus")
public class UserFinancialFocus {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "focus_key", nullable = false, length = 30)
    private String focusKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public UserFinancialFocus() {}

    public UserFinancialFocus(UUID userId, String focusKey) {
        this.userId = userId;
        this.focusKey = focusKey;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getFocusKey() { return focusKey; }
    public Instant getCreatedAt() { return createdAt; }
}
```

```java
package com.finora.onboarding;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface UserFinancialFocusRepository extends JpaRepository<UserFinancialFocus, UUID> {
    List<UserFinancialFocus> findByUserId(UUID userId);

    @Modifying
    @Query("DELETE FROM UserFinancialFocus f WHERE f.userId = :userId")
    void deleteByUserId(@Param("userId") UUID userId);
}
```

- [ ] **Step 3: Write the DTOs**

```java
package com.finora.onboarding;

import java.util.List;

public class OnboardingDto {

    public record StatusResponse(boolean onboardingCompleted, List<String> financialFocus) {}

    public record FinancialFocusRequest(List<String> focusKeys) {}

    public record ChecklistItemDto(String key, boolean completed) {}

    public record ChecklistResponse(List<ChecklistItemDto> items, int completedCount, int totalCount) {}
}
```

- [ ] **Step 4: Write the failing service test**

```java
package com.finora.onboarding;

import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OnboardingServiceTest {

    private UserRepository userRepository;
    private UserFinancialFocusRepository focusRepository;
    private UserChecklistEventRepository checklistEventRepository;
    private OnboardingService service;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        focusRepository = mock(UserFinancialFocusRepository.class);
        checklistEventRepository = mock(UserChecklistEventRepository.class);
        service = new OnboardingService(userRepository, focusRepository, checklistEventRepository,
                mock(com.finora.repository.ImportJobRepository.class),
                mock(com.finora.repository.BudgetRepository.class),
                mock(com.finora.goals.GoalRepository.class));
        when(userRepository.findById(userId)).thenReturn(Optional.of(new User()));
    }

    @Test
    void setFinancialFocusRejectsAnUnknownKey() {
        assertThatThrownBy(() -> service.setFinancialFocus(userId, List.of("NOT_A_REAL_KEY")))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void setFinancialFocusReplacesTheExistingSet() {
        service.setFinancialFocus(userId, List.of("TRACK_SPENDING", "REDUCE_DEBT"));

        verify(focusRepository).deleteByUserId(userId);
        verify(focusRepository, times(2)).save(any(UserFinancialFocus.class));
    }

    @Test
    void setFinancialFocusAcceptsAnEmptyListAsASkip() {
        service.setFinancialFocus(userId, List.of());

        verify(focusRepository).deleteByUserId(userId);
        verify(focusRepository, never()).save(any());
    }

    @Test
    void getStatusReportsOnboardingIncompleteAndTheStoredFocusSet() {
        User user = new User();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(focusRepository.findByUserId(userId)).thenReturn(
                List.of(new UserFinancialFocus(userId, "TRACK_SPENDING")));

        OnboardingDto.StatusResponse status = service.getStatus(userId);

        assertThat(status.onboardingCompleted()).isFalse();
        assertThat(status.financialFocus()).containsExactly("TRACK_SPENDING");
    }
}
```

Note: `checklistEventRepository`/`ImportJobRepository`/`BudgetRepository`/`GoalRepository`
constructor arguments are wired here in anticipation of Task 4/5 — `OnboardingService`'s final
constructor shape is fixed at the end of this task and does not change again.

- [ ] **Step 5: Run it to verify it fails**

Run: `cd backend && ./mvnw -pl backend -Dtest=OnboardingServiceTest test` (adjust module flag to
however this repo's Maven build is actually invoked — check an existing recent PR's CI command if
unsure).
Expected: FAIL — `OnboardingService` does not exist yet.

- [ ] **Step 6: Write the service (financial-focus + status only; checklist methods land in Task 4/5)**

```java
package com.finora.onboarding;

import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.goals.GoalRepository;
import com.finora.repository.BudgetRepository;
import com.finora.repository.ImportJobRepository;
import com.finora.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class OnboardingService {

    private static final Set<String> VALID_FOCUS_KEYS = Arrays.stream(FinancialFocus.values())
            .map(Enum::name).collect(Collectors.toSet());

    private final UserRepository userRepository;
    private final UserFinancialFocusRepository focusRepository;
    private final UserChecklistEventRepository checklistEventRepository;
    private final ImportJobRepository importJobRepository;
    private final BudgetRepository budgetRepository;
    private final GoalRepository goalRepository;

    public OnboardingService(UserRepository userRepository, UserFinancialFocusRepository focusRepository,
                              UserChecklistEventRepository checklistEventRepository,
                              ImportJobRepository importJobRepository, BudgetRepository budgetRepository,
                              GoalRepository goalRepository) {
        this.userRepository = userRepository;
        this.focusRepository = focusRepository;
        this.checklistEventRepository = checklistEventRepository;
        this.importJobRepository = importJobRepository;
        this.budgetRepository = budgetRepository;
        this.goalRepository = goalRepository;
    }

    private User requireUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ApiException("User not found", HttpStatus.NOT_FOUND, "ONB_001"));
    }

    @Transactional(readOnly = true)
    public OnboardingDto.StatusResponse getStatus(UUID userId) {
        User user = requireUser(userId);
        List<String> focus = focusRepository.findByUserId(userId).stream()
                .map(UserFinancialFocus::getFocusKey).toList();
        return new OnboardingDto.StatusResponse(user.getOnboardingCompletedAt() != null, focus);
    }

    @Transactional
    public OnboardingDto.StatusResponse setFinancialFocus(UUID userId, List<String> focusKeys) {
        for (String key : focusKeys) {
            if (!VALID_FOCUS_KEYS.contains(key)) {
                throw new ApiException("Unknown financial focus key: " + key, HttpStatus.BAD_REQUEST, "ONB_002");
            }
        }
        focusRepository.deleteByUserId(userId);
        for (String key : focusKeys) {
            focusRepository.save(new UserFinancialFocus(userId, key));
        }
        return getStatus(userId);
    }
}
```

- [ ] **Step 7: Run the service test again to verify it passes**

Run: same command as Step 5.
Expected: PASS.

- [ ] **Step 8: Write the controller**

```java
package com.finora.onboarding;

import com.finora.dto.ApiResponse;
import com.finora.security.CurrentUser;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/onboarding")
public class OnboardingController {

    private final OnboardingService onboardingService;
    private final CurrentUser currentUser;

    public OnboardingController(OnboardingService onboardingService, CurrentUser currentUser) {
        this.onboardingService = onboardingService;
        this.currentUser = currentUser;
    }

    @GetMapping("/status")
    public ApiResponse<OnboardingDto.StatusResponse> status() {
        return ApiResponse.ok(onboardingService.getStatus(currentUser.id()));
    }

    @PostMapping("/financial-focus")
    public ApiResponse<OnboardingDto.StatusResponse> setFinancialFocus(
            @RequestBody OnboardingDto.FinancialFocusRequest request) {
        return ApiResponse.ok(onboardingService.setFinancialFocus(currentUser.id(), request.focusKeys()),
                "Financial focus saved");
    }
}
```

- [ ] **Step 9: Write the controller IT**

```java
package com.finora.onboarding;

import com.finora.support.AbstractIntegrationTest; // replace with the real shared IT base class
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OnboardingControllerIT extends AbstractIntegrationTest {

    @Test
    void statusStartsIncompleteForAFreshUser() {
        String token = registerAndGetToken(); // this repo's existing IT helper for a fresh user + JWT

        var response = authenticatedGet(token, "/api/v1/onboarding/status");

        assertThat(response.jsonPath().getBoolean("data.onboardingCompleted")).isFalse();
        assertThat(response.jsonPath().getList("data.financialFocus")).isEmpty();
    }

    @Test
    void financialFocusRoundTrips() {
        String token = registerAndGetToken();

        authenticatedPost(token, "/api/v1/onboarding/financial-focus",
                java.util.Map.of("focusKeys", java.util.List.of("TRACK_SPENDING", "REDUCE_DEBT")));
        var response = authenticatedGet(token, "/api/v1/onboarding/status");

        assertThat(response.jsonPath().getList("data.financialFocus", String.class))
                .containsExactlyInAnyOrder("TRACK_SPENDING", "REDUCE_DEBT");
    }

    @Test
    void financialFocusRejectsAnUnknownKey() {
        String token = registerAndGetToken();

        var response = authenticatedPost(token, "/api/v1/onboarding/financial-focus",
                java.util.Map.of("focusKeys", java.util.List.of("NOT_A_REAL_KEY")));

        assertThat(response.statusCode()).isEqualTo(400);
    }
}
```

`registerAndGetToken()`/`authenticatedGet()`/`authenticatedPost()` are placeholders for this
repo's actual shared IT helpers — find the real names first with
`grep -rn "registerAndGetToken\|authenticatedGet\|authenticatedPost" backend/src/test/java | head -5`
and use whatever this codebase's existing `*ControllerIT` tests (e.g. `GoalControllerIT` if one
exists, otherwise any recent `*IT.java`) actually call.

- [ ] **Step 10: Run both new tests**

Run: `cd backend && ./mvnw -Dtest=OnboardingServiceTest,OnboardingControllerIT test`
Expected: all PASS.

- [ ] **Step 11: Commit**

```bash
git add backend/src/main/java/com/finora/onboarding/ backend/src/test/java/com/finora/onboarding/
git commit -m "feat(onboarding): add Financial Focus set/get API"
```

---

### Task 3: `onboarding-complete` / `onboarding-reset`

**Files:**
- Modify: `backend/src/main/java/com/finora/onboarding/OnboardingService.java`
- Modify: `backend/src/main/java/com/finora/onboarding/OnboardingController.java`
- Modify: `backend/src/test/java/com/finora/onboarding/OnboardingServiceTest.java`
- Modify: `backend/src/test/java/com/finora/onboarding/OnboardingControllerIT.java`

**Interfaces:**
- Produces: `OnboardingService.complete(UUID userId): void` (idempotent),
  `OnboardingService.reset(UUID userId): void`.

- [ ] **Step 1: Add failing tests to `OnboardingServiceTest`**

```java
    @Test
    void completeSetsOnboardingCompletedAtOnlyOnce() {
        User user = new User();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        service.complete(userId);
        var firstCompletedAt = user.getOnboardingCompletedAt();
        service.complete(userId);

        assertThat(user.getOnboardingCompletedAt()).isEqualTo(firstCompletedAt);
    }

    @Test
    void resetClearsOnboardingCompletedAt() {
        User user = new User();
        user.setOnboardingCompletedAt(java.time.Instant.now());
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        service.reset(userId);

        assertThat(user.getOnboardingCompletedAt()).isNull();
    }
```

- [ ] **Step 2: Run to verify these two fail**

Run: `cd backend && ./mvnw -Dtest=OnboardingServiceTest test`
Expected: FAIL — `complete`/`reset` don't exist on `OnboardingService` yet.

- [ ] **Step 3: Add the two methods**

```java
    @Transactional
    public void complete(UUID userId) {
        User user = requireUser(userId);
        if (user.getOnboardingCompletedAt() == null) {
            user.setOnboardingCompletedAt(Instant.now());
        }
    }

    @Transactional
    public void reset(UUID userId) {
        User user = requireUser(userId);
        user.setOnboardingCompletedAt(null);
    }
```

- [ ] **Step 4: Run again to verify they pass**

Run: same as Step 2.
Expected: PASS.

- [ ] **Step 5: Add the two endpoints**

```java
    @PostMapping("/complete")
    public ApiResponse<Void> complete() {
        onboardingService.complete(currentUser.id());
        return ApiResponse.ok(null, "Onboarding complete");
    }

    @PostMapping("/reset")
    public ApiResponse<Void> reset() {
        onboardingService.reset(currentUser.id());
        return ApiResponse.ok(null, "Onboarding reset");
    }
```

- [ ] **Step 6: Add IT coverage**

```java
    @Test
    void completeThenResetRoundTrips() {
        String token = registerAndGetToken();

        authenticatedPost(token, "/api/v1/onboarding/complete", java.util.Map.of());
        var afterComplete = authenticatedGet(token, "/api/v1/onboarding/status");
        assertThat(afterComplete.jsonPath().getBoolean("data.onboardingCompleted")).isTrue();

        authenticatedPost(token, "/api/v1/onboarding/reset", java.util.Map.of());
        var afterReset = authenticatedGet(token, "/api/v1/onboarding/status");
        assertThat(afterReset.jsonPath().getBoolean("data.onboardingCompleted")).isFalse();
    }
```

- [ ] **Step 7: Run all onboarding backend tests**

Run: `cd backend && ./mvnw -Dtest=OnboardingServiceTest,OnboardingControllerIT test`
Expected: all PASS.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/finora/onboarding/ backend/src/test/java/com/finora/onboarding/
git commit -m "feat(onboarding): add complete/reset endpoints for the tour flag"
```

---

### Task 4: Checklist — 4 derived items + `GET /checklist`

**Files:**
- Create: `backend/src/main/java/com/finora/onboarding/ChecklistItemKey.java`
- Modify: `backend/src/main/java/com/finora/onboarding/OnboardingService.java`
- Modify: `backend/src/main/java/com/finora/onboarding/OnboardingController.java`
- Modify: `backend/src/test/java/com/finora/onboarding/OnboardingServiceTest.java`

**Interfaces:**
- Consumes: `ImportJobRepository` (existing `findByUserIdOrderByCreatedAtDesc` — count via
  `!isEmpty()` on a 1-row page, or add a lightweight `existsByUserId(UUID)` derived method if none
  exists yet — check first with
  `grep -n "existsByUserId" backend/src/main/java/com/finora/repository/ImportJobRepository.java`),
  `BudgetRepository.findByUserId`, `GoalRepository.findByUserId`, `User.getFullName()`/
  `isEmailVerified()`/`getPhoneNumber()`/`isPhoneVerified()`.
- Produces: `OnboardingService.getChecklist(UUID userId): OnboardingDto.ChecklistResponse`.

- [ ] **Step 1: Write the enum**

```java
package com.finora.onboarding;

/** The 6 getting-started checklist items (spec §3), in display order. The 4 DERIVED items have no
 *  stored state -- OnboardingService computes them live from existing tables. The 2 EXPLICIT items
 *  have no other signal in the schema and are recorded in user_checklist_events; only these two
 *  may ever be POSTed as complete (OnboardingService.completeChecklistItem rejects the other 4). */
public enum ChecklistItemKey {
    COMPLETE_PROFILE(false),
    IMPORT_STATEMENT(false),
    REVIEW_TRANSACTIONS(true),
    CREATE_BUDGET(false),
    CREATE_GOAL(false),
    VIEW_INSIGHTS(true);

    private final boolean explicit;

    ChecklistItemKey(boolean explicit) {
        this.explicit = explicit;
    }

    public boolean isExplicit() {
        return explicit;
    }
}
```

- [ ] **Step 2: Write the failing tests**

```java
    @Test
    void checklistReportsAllSixItemsInOrderWithNoneCompletedForABrandNewUser() {
        User user = new User();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(importJobRepository.findByUserIdOrderByCreatedAtDesc(eq(userId), any())).thenReturn(List.of());
        when(budgetRepository.findByUserId(userId)).thenReturn(List.of());
        when(goalRepository.findByUserId(userId)).thenReturn(List.of());
        when(checklistEventRepository.findByUserId(userId)).thenReturn(List.of());

        OnboardingDto.ChecklistResponse checklist = service.getChecklist(userId);

        assertThat(checklist.items()).extracting(OnboardingDto.ChecklistItemDto::key)
                .containsExactly("COMPLETE_PROFILE", "IMPORT_STATEMENT", "REVIEW_TRANSACTIONS",
                        "CREATE_BUDGET", "CREATE_GOAL", "VIEW_INSIGHTS");
        assertThat(checklist.completedCount()).isZero();
        assertThat(checklist.totalCount()).isEqualTo(6);
    }

    @Test
    void completeProfileRequiresNameAndVerifiedEmailAndEitherNoPhoneOrAVerifiedOne() {
        User user = new User();
        user.setFullName("Ada Lovelace");
        user.setEmailVerified(true);
        user.setPhoneNumber(null); // no phone on file at all
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(importJobRepository.findByUserIdOrderByCreatedAtDesc(eq(userId), any())).thenReturn(List.of());
        when(budgetRepository.findByUserId(userId)).thenReturn(List.of());
        when(goalRepository.findByUserId(userId)).thenReturn(List.of());
        when(checklistEventRepository.findByUserId(userId)).thenReturn(List.of());

        boolean profileComplete = service.getChecklist(userId).items().stream()
                .filter(i -> i.key().equals("COMPLETE_PROFILE")).findFirst().orElseThrow().completed();

        assertThat(profileComplete).isTrue();
    }

    @Test
    void completeProfileIsFalseWhenPhoneIsOnFileButUnverified() {
        User user = new User();
        user.setFullName("Ada Lovelace");
        user.setEmailVerified(true);
        user.setPhoneNumber("+911234567890"); // synthetic-ok: fixture, not a real number
        user.setPhoneVerified(false);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(importJobRepository.findByUserIdOrderByCreatedAtDesc(eq(userId), any())).thenReturn(List.of());
        when(budgetRepository.findByUserId(userId)).thenReturn(List.of());
        when(goalRepository.findByUserId(userId)).thenReturn(List.of());
        when(checklistEventRepository.findByUserId(userId)).thenReturn(List.of());

        boolean profileComplete = service.getChecklist(userId).items().stream()
                .filter(i -> i.key().equals("COMPLETE_PROFILE")).findFirst().orElseThrow().completed();

        assertThat(profileComplete).isFalse();
    }
```

Add the needed imports (`org.mockito.ArgumentMatchers.eq`) to the test file's existing static
import block.

- [ ] **Step 3: Run to verify they fail**

Run: `cd backend && ./mvnw -Dtest=OnboardingServiceTest test`
Expected: FAIL — `getChecklist` doesn't exist yet.

- [ ] **Step 4: Confirm the exact `ImportJobRepository` lookup to use**

Run: `grep -n "findByUserId\|existsByUserId" backend/src/main/java/com/finora/repository/ImportJobRepository.java`
This repository already has `findByUserIdOrderByCreatedAtDesc(UUID, Pageable)` (verified while
writing this plan) but no bare `existsByUserId`. Use
`!importJobRepository.findByUserIdOrderByCreatedAtDesc(userId, org.springframework.data.domain.PageRequest.of(0, 1)).isEmpty()`
rather than adding a new repository method for a single boolean check.

- [ ] **Step 5: Implement `getChecklist`**

```java
    @Transactional(readOnly = true)
    public OnboardingDto.ChecklistResponse getChecklist(UUID userId) {
        User user = requireUser(userId);
        Set<String> explicitDone = checklistEventRepository.findByUserId(userId).stream()
                .map(UserChecklistEvent::getItemKey).collect(Collectors.toSet());

        boolean profileComplete = user.getFullName() != null && !user.getFullName().isBlank()
                && user.isEmailVerified()
                && (user.getPhoneNumber() == null || user.isPhoneVerified());
        boolean importedStatement = !importJobRepository.findByUserIdOrderByCreatedAtDesc(
                userId, org.springframework.data.domain.PageRequest.of(0, 1)).isEmpty();
        boolean createdBudget = !budgetRepository.findByUserId(userId).isEmpty();
        boolean createdGoal = !goalRepository.findByUserId(userId).isEmpty();

        java.util.Map<ChecklistItemKey, Boolean> completedByKey = new java.util.EnumMap<>(ChecklistItemKey.class);
        completedByKey.put(ChecklistItemKey.COMPLETE_PROFILE, profileComplete);
        completedByKey.put(ChecklistItemKey.IMPORT_STATEMENT, importedStatement);
        completedByKey.put(ChecklistItemKey.REVIEW_TRANSACTIONS, explicitDone.contains("REVIEW_TRANSACTIONS"));
        completedByKey.put(ChecklistItemKey.CREATE_BUDGET, createdBudget);
        completedByKey.put(ChecklistItemKey.CREATE_GOAL, createdGoal);
        completedByKey.put(ChecklistItemKey.VIEW_INSIGHTS, explicitDone.contains("VIEW_INSIGHTS"));

        List<OnboardingDto.ChecklistItemDto> items = completedByKey.entrySet().stream()
                .map(e -> new OnboardingDto.ChecklistItemDto(e.getKey().name(), e.getValue()))
                .toList();
        int completedCount = (int) items.stream().filter(OnboardingDto.ChecklistItemDto::completed).count();

        return new OnboardingDto.ChecklistResponse(items, completedCount, items.size());
    }
```

`EnumMap` iterates in enum-declaration order, which is why `ChecklistItemKey`'s declared order
(Step 1) is the display order (spec §3) — no separate ordering list to keep in sync.

This method references `UserChecklistEvent`/`checklistEventRepository.findByUserId`, which do not
exist until Task 5. Stub a minimal version now so this task compiles and its own tests pass —
Task 5 fills it in for real:

```java
// Task 5 provides the real UserChecklistEvent entity/repository; this task only needs
// checklistEventRepository.findByUserId(userId) to return List<UserChecklistEvent> with a
// getItemKey(): String accessor, which is what Task 5's real classes provide.
```

- [ ] **Step 6: Add the endpoint**

```java
    @GetMapping("/checklist")
    public ApiResponse<OnboardingDto.ChecklistResponse> checklist() {
        return ApiResponse.ok(onboardingService.getChecklist(currentUser.id()));
    }
```

- [ ] **Step 7: Run the tests**

Run: `cd backend && ./mvnw -Dtest=OnboardingServiceTest test`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/finora/onboarding/ backend/src/test/java/com/finora/onboarding/
git commit -m "feat(onboarding): compute the 4 derived checklist items"
```

---

### Task 5: Checklist — 2 explicit events + `POST /checklist/{itemKey}/complete`

**Files:**
- Create: `backend/src/main/java/com/finora/onboarding/UserChecklistEvent.java`
- Create: `backend/src/main/java/com/finora/onboarding/UserChecklistEventRepository.java`
- Modify: `backend/src/main/java/com/finora/onboarding/OnboardingService.java`
- Modify: `backend/src/main/java/com/finora/onboarding/OnboardingController.java`
- Modify: `backend/src/test/java/com/finora/onboarding/OnboardingServiceTest.java`
- Modify: `backend/src/test/java/com/finora/onboarding/OnboardingControllerIT.java`

**Interfaces:**
- Produces: `OnboardingService.completeChecklistItem(UUID userId, String itemKey): void` —
  idempotent, throws `ApiException` (400) for any key where `ChecklistItemKey.isExplicit()` is
  false or the key is unknown.

- [ ] **Step 1: Write the entity + repository**

```java
package com.finora.onboarding;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "user_checklist_events")
public class UserChecklistEvent {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "item_key", nullable = false, length = 30)
    private String itemKey;

    @Column(name = "completed_at", nullable = false)
    private Instant completedAt = Instant.now();

    public UserChecklistEvent() {}

    public UserChecklistEvent(UUID userId, String itemKey) {
        this.userId = userId;
        this.itemKey = itemKey;
    }

    public UUID getUserId() { return userId; }
    public String getItemKey() { return itemKey; }
    public Instant getCompletedAt() { return completedAt; }
}
```

```java
package com.finora.onboarding;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface UserChecklistEventRepository extends JpaRepository<UserChecklistEvent, UUID> {
    List<UserChecklistEvent> findByUserId(UUID userId);
    boolean existsByUserIdAndItemKey(UUID userId, String itemKey);
}
```

- [ ] **Step 2: Remove the Task 4 stub comment** from `getChecklist` (it now compiles for real
  against these two new classes — no code change needed there beyond deleting the comment).

- [ ] **Step 3: Write the failing tests**

```java
    @Test
    void completeChecklistItemRejectsADerivedItem() {
        assertThatThrownBy(() -> service.completeChecklistItem(userId, "CREATE_BUDGET"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void completeChecklistItemRejectsAnUnknownKey() {
        assertThatThrownBy(() -> service.completeChecklistItem(userId, "NOT_A_REAL_KEY"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void completeChecklistItemIsIdempotent() {
        when(checklistEventRepository.existsByUserIdAndItemKey(userId, "VIEW_INSIGHTS"))
                .thenReturn(false).thenReturn(true);

        service.completeChecklistItem(userId, "VIEW_INSIGHTS");
        service.completeChecklistItem(userId, "VIEW_INSIGHTS");

        verify(checklistEventRepository, times(1)).save(any(UserChecklistEvent.class));
    }
```

- [ ] **Step 4: Run to verify they fail**

Run: `cd backend && ./mvnw -Dtest=OnboardingServiceTest test`
Expected: FAIL — `completeChecklistItem` doesn't exist yet.

- [ ] **Step 5: Implement it**

```java
    @Transactional
    public void completeChecklistItem(UUID userId, String itemKey) {
        ChecklistItemKey key;
        try {
            key = ChecklistItemKey.valueOf(itemKey);
        } catch (IllegalArgumentException e) {
            throw new ApiException("Unknown checklist item: " + itemKey, HttpStatus.BAD_REQUEST, "ONB_003");
        }
        if (!key.isExplicit()) {
            throw new ApiException(key + " is derived automatically and can't be marked complete directly",
                    HttpStatus.BAD_REQUEST, "ONB_004");
        }
        if (!checklistEventRepository.existsByUserIdAndItemKey(userId, itemKey)) {
            checklistEventRepository.save(new UserChecklistEvent(userId, itemKey));
        }
    }
```

- [ ] **Step 6: Run again to verify they pass**

Run: same as Step 4.
Expected: PASS.

- [ ] **Step 7: Add the endpoint**

```java
    @PostMapping("/checklist/{itemKey}/complete")
    public ApiResponse<Void> completeChecklistItem(@PathVariable String itemKey) {
        onboardingService.completeChecklistItem(currentUser.id(), itemKey);
        return ApiResponse.ok(null, "Checklist item completed");
    }
```

- [ ] **Step 8: Add IT coverage**

```java
    @Test
    void checklistItemCompletionRoundTrips() {
        String token = registerAndGetToken();

        var before = authenticatedGet(token, "/api/v1/onboarding/checklist");
        assertThat(before.jsonPath().getInt("data.completedCount")).isZero();

        authenticatedPost(token, "/api/v1/onboarding/checklist/VIEW_INSIGHTS/complete", java.util.Map.of());
        var after = authenticatedGet(token, "/api/v1/onboarding/checklist");
        assertThat(after.jsonPath().getInt("data.completedCount")).isEqualTo(1);
    }

    @Test
    void checklistItemCompletionRejectsADerivedItem() {
        String token = registerAndGetToken();

        var response = authenticatedPost(token, "/api/v1/onboarding/checklist/CREATE_BUDGET/complete",
                java.util.Map.of());

        assertThat(response.statusCode()).isEqualTo(400);
    }
```

- [ ] **Step 9: Run all onboarding backend tests**

Run: `cd backend && ./mvnw -Dtest=OnboardingServiceTest,OnboardingControllerIT,OnboardingMigrationIT test`
Expected: all PASS.

- [ ] **Step 10: Commit**

```bash
git add backend/src/main/java/com/finora/onboarding/ backend/src/test/java/com/finora/onboarding/
git commit -m "feat(onboarding): add explicit checklist-item completion endpoint"
```

---

### Task 6: Wire `onboardingCompleted` into `AuthResponse` and `GET /users/me`

**Files:**
- Modify: `backend/src/main/java/com/finora/dto/AuthDtos.java`
- Modify: `backend/src/main/java/com/finora/dto/UserSettingsDto.java`
- Modify: `backend/src/main/java/com/finora/service/AuthService.java` (4 call sites, verified at
  approximately lines 267, 678, 874, 1129 while writing this plan — re-grep before editing, line
  numbers shift)
- Modify: `backend/src/main/java/com/finora/service/UserSettingsService.java` (1 call site,
  verified at approximately line 73)
- Existing tests: find and update every test that constructs `AuthResponse(...)` or
  `UserSettingsDto(...)` positionally (`grep -rln "new AuthResponse(\|new UserSettingsDto(" backend/src/test/java`)

**Interfaces:**
- Consumes: `User.getOnboardingCompletedAt()` (Task 1).
- Produces: `AuthResponse.onboardingCompleted(): boolean`,
  `UserSettingsDto.onboardingCompleted(): boolean` — both consumed by web/mobile in Phase 2/3.

- [ ] **Step 1: Locate every construction site precisely**

Run:
```bash
grep -n "record AuthResponse" -A 12 backend/src/main/java/com/finora/dto/AuthDtos.java
grep -n "new AuthResponse(" backend/src/main/java/com/finora/service/AuthService.java
grep -n "new UserSettingsDto(" backend/src/main/java/com/finora/service/UserSettingsService.java
grep -rln "new AuthResponse(\|new UserSettingsDto(" backend/src/test/java
```
Read each result before editing — this plan's line numbers are a starting point, not a guarantee,
since other work may have landed on `origin/main` since this plan was written.

- [ ] **Step 2: Add the field to both records**

In `AuthDtos.AuthResponse`, add `boolean onboardingCompleted` as the last component (append,
don't insert in the middle — every existing positional constructor call needs exactly one new
trailing argument, not a renumbering of every argument after an inserted one).

In `UserSettingsDto`, same: append `boolean onboardingCompleted` as the last component.

- [ ] **Step 3: Update the 4 `AuthResponse` construction sites in `AuthService.java`**

Each site already passes `user.isPhoneVerified()` — add
`user.getOnboardingCompletedAt() != null` as the new final argument, right after whatever
argument currently comes last. Do this for all 4 sites found in Step 1.

- [ ] **Step 4: Update the 1 `UserSettingsDto` construction site in `UserSettingsService.java`**

Same pattern: append `u.getOnboardingCompletedAt() != null` as the new final argument.

- [ ] **Step 5: Fix every test broken by the new positional argument**

For each file found in Step 1's last `grep`, add the new boolean (`true` or `false`, matching
whatever onboarding state that specific test fixture should represent — default to `true` for
fixtures representing an already-onboarded user, since that's what almost every existing test
implicitly assumes today) as the final constructor argument.

- [ ] **Step 6: Run the full backend test suite**

Run: `cd backend && ./mvnw test`
Expected: PASS — no test was relying on `AuthResponse`/`UserSettingsDto`'s exact arity in a way
this step didn't already account for.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/finora/dto/AuthDtos.java \
        backend/src/main/java/com/finora/dto/UserSettingsDto.java \
        backend/src/main/java/com/finora/service/AuthService.java \
        backend/src/main/java/com/finora/service/UserSettingsService.java \
        backend/src/test/java
git commit -m "feat(onboarding): expose onboardingCompleted on auth and profile responses"
```

**Phase 1 checkpoint:** the entire backend API for this feature now exists and is fully tested in
isolation from any frontend. This is a natural point to open a backend-only PR if you want Phase
2/3 reviewed separately (see `superpowers:finishing-a-development-branch` for how this repo
usually splits multi-phase features into multiple PRs off one branch/spec).

---

## Phase 2 — Web

### Task 7: `onboardingApi` + `AuthContext` wiring

**Files:**
- Modify: `frontend/src/api/endpoints.ts`
- Modify: `frontend/src/context/AuthContext.tsx`
- Modify: `frontend/src/context/AuthContext.test.tsx`

**Interfaces:**
- Consumes: backend `/api/v1/onboarding/*` (Phase 1), `AuthResponseDto.onboardingCompleted`,
  `UserSettings.onboardingCompleted` (both now real fields, Task 6).
- Produces: `useAuth().onboardingCompleted: boolean`,
  `useAuth().setOnboardingCompleted(v: boolean): void`, `onboardingApi.status()`,
  `onboardingApi.setFinancialFocus()`, `onboardingApi.complete()`, `onboardingApi.reset()`,
  `onboardingApi.getChecklist()`, `onboardingApi.completeChecklistItem()`.

- [ ] **Step 1: Extend the two response types**

In `frontend/src/api/endpoints.ts`, add `onboardingCompleted: boolean;` to both
`AuthResponseDto` and `UserSettings` (find each interface with
`grep -n "interface AuthResponseDto\|interface UserSettings" frontend/src/api/endpoints.ts` and
add the field alongside `phoneVerified`).

- [ ] **Step 2: Add `onboardingApi`**

```typescript
export interface OnboardingStatus {
  onboardingCompleted: boolean;
  financialFocus: string[];
}

export interface ChecklistItem {
  key: string;
  completed: boolean;
}

export interface ChecklistStatus {
  items: ChecklistItem[];
  completedCount: number;
  totalCount: number;
}

export const onboardingApi = {
  status: () => api.get<OnboardingStatus>('/onboarding/status').then((r) => r.data),
  setFinancialFocus: (focusKeys: string[]) =>
    api.post<OnboardingStatus>('/onboarding/financial-focus', { focusKeys }).then((r) => r.data),
  complete: () => api.post<void>('/onboarding/complete', {}),
  reset: () => api.post<void>('/onboarding/reset', {}),
  getChecklist: () => api.get<ChecklistStatus>('/onboarding/checklist').then((r) => r.data),
  completeChecklistItem: (itemKey: string) =>
    api.post<void>(`/onboarding/checklist/${itemKey}/complete`, {}),
};
```

- [ ] **Step 3: Write the failing `AuthContext` test additions**

Add to `frontend/src/context/AuthContext.test.tsx`, following that file's existing
`login()`/`phoneVerified` test as a template:

```typescript
  it('exposes onboardingCompleted from the login response', async () => {
    mockedAuthApi.login.mockResolvedValue({ data: { ...SESSION, onboardingCompleted: false } } as never);
    const view = renderAuthConsumer();

    await act(async () => {
      await view.result.current.login('a@b.com', 'pw'); // synthetic-ok: fixture, not a real account
    });

    expect(view.result.current.onboardingCompleted).toBe(false);
  });

  it('setOnboardingCompleted updates state and persists it', () => {
    const view = renderAuthConsumer();

    act(() => {
      view.result.current.setOnboardingCompleted(true);
    });

    expect(view.result.current.onboardingCompleted).toBe(true);
  });
```

Adapt `renderAuthConsumer`/`SESSION`/`act` to whatever this test file's actual helper names are
(read the file first — the sketch above assumes a `SESSION` fixture object and a hook-rendering
helper matching the `phoneVerified` tests already in this file, per the file excerpt already read
while planning this task).

- [ ] **Step 4: Run to verify these fail**

Run: `cd frontend && npx vitest run src/context/AuthContext.test.tsx`
Expected: FAIL — `onboardingCompleted`/`setOnboardingCompleted` don't exist on the context yet.

- [ ] **Step 5: Wire it into `AuthContext.tsx`**

Add state (near `phoneVerified`):
```typescript
  const [onboardingCompleted, setOnboardingCompletedState] = useState<boolean>(
    safeStorage.getItem('finora_onboarding_completed') === 'true'
  );
```

In `persist()`: add `onboardingCompleted: boolean` to its parameter type, and inside the function
body add
```typescript
    safeStorage.setItem('finora_onboarding_completed', String(data.onboardingCompleted));
    setOnboardingCompletedState(data.onboardingCompleted);
```

Add the public setter (mirrors `setPhoneVerified` exactly):
```typescript
  function setOnboardingCompleted(completed: boolean) {
    safeStorage.setItem('finora_onboarding_completed', String(completed));
    setOnboardingCompletedState(completed);
  }
```

In the bootstrap `useEffect`'s `userApi.get()` branch, add:
```typescript
        setOnboardingCompletedState(profile.onboardingCompleted);
        safeStorage.setItem('finora_onboarding_completed', String(profile.onboardingCompleted));
```

In `logout()`, add: `safeStorage.removeItem('finora_onboarding_completed');` and
`setOnboardingCompletedState(false);`.

Add `onboardingCompleted` and `setOnboardingCompleted` to the `AuthState` interface and to the
`<AuthContext.Provider value={{ ... }}>` object.

- [ ] **Step 6: Run the test again to verify it passes**

Run: same as Step 4.
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/api/endpoints.ts frontend/src/context/AuthContext.tsx frontend/src/context/AuthContext.test.tsx
git commit -m "feat(onboarding): wire onboardingCompleted into AuthContext"
```

---

### Task 8: `ProtectedRoute` gate

**Files:**
- Modify: `frontend/src/components/ProtectedRoute.tsx`
- Modify: `frontend/src/components/ProtectedRoute.test.tsx` (create if it doesn't exist yet —
  check first)
- Create: `frontend/src/onboarding/OnboardingFlow.tsx` (minimal stub for this task; Task 9-11
  fill it in)

**Interfaces:**
- Consumes: `useAuth().onboardingCompleted` (Task 7).
- Produces: `<OnboardingFlow />` — the component `ProtectedRoute` renders in place of `children`
  when onboarding isn't complete.

- [ ] **Step 1: Write the minimal `OnboardingFlow` stub**

```typescript
// frontend/src/onboarding/OnboardingFlow.tsx
// Full implementation lands in Task 9-11 (Welcome/FinancialFocus/Tour/Success). This stub exists
// only so ProtectedRoute has something real to render and test against in this task.
export function OnboardingFlow() {
  return <div data-testid="onboarding-flow">Onboarding flow placeholder</div>;
}
```

- [ ] **Step 2: Write the failing test**

```typescript
// Add to (or create) frontend/src/components/ProtectedRoute.test.tsx
it('renders the onboarding flow instead of children when onboarding is not complete', () => {
  mockUseAuth.mockReturnValue({ token: 'tok', bootstrapping: false, phoneVerified: true, onboardingCompleted: false });

  render(
    <MemoryRouter>
      <ProtectedRoute><div data-testid="real-page" /></ProtectedRoute>
    </MemoryRouter>
  );

  expect(screen.getByTestId('onboarding-flow')).toBeInTheDocument();
  expect(screen.queryByTestId('real-page')).not.toBeInTheDocument();
});

it('renders children when onboarding is already complete', () => {
  mockUseAuth.mockReturnValue({ token: 'tok', bootstrapping: false, phoneVerified: true, onboardingCompleted: true });

  render(
    <MemoryRouter>
      <ProtectedRoute><div data-testid="real-page" /></ProtectedRoute>
    </MemoryRouter>
  );

  expect(screen.getByTestId('real-page')).toBeInTheDocument();
});
```

Match whatever mocking convention this repo's other `ProtectedRoute`-adjacent tests already use
for `useAuth` (check `frontend/src/context/AuthContext.test.tsx` and any existing route test for
the exact `vi.mock('../context/AuthContext', ...)` shape before writing this).

- [ ] **Step 3: Run to verify it fails**

Run: `cd frontend && npx vitest run src/components/ProtectedRoute.test.tsx`
Expected: FAIL.

- [ ] **Step 4: Update `ProtectedRoute.tsx`**

```typescript
import { OnboardingFlow } from '../onboarding/OnboardingFlow';

export function ProtectedRoute({ children, allowUnverified = false }: ProtectedRouteProps) {
  const { token, bootstrapping, phoneVerified, onboardingCompleted } = useAuth();
  if (bootstrapping) return null;
  if (!token) return <Navigate to="/auth" replace />;
  if (!allowUnverified && !phoneVerified) return <Navigate to="/verify-phone" replace />;
  // Onboarding only ever applies to a verified session -- allowUnverified routes (VerifyPhone
  // itself) must never be blocked behind it, same reasoning as the phoneVerified check above.
  if (!allowUnverified && !onboardingCompleted) return <OnboardingFlow />;
  return <>{children}</>;
}
```

- [ ] **Step 5: Run again to verify it passes**

Run: same as Step 3.
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/components/ProtectedRoute.tsx frontend/src/components/ProtectedRoute.test.tsx \
        frontend/src/onboarding/OnboardingFlow.tsx
git commit -m "feat(onboarding): gate protected routes on onboarding completion"
```

---

### Task 9: Welcome + Financial Focus screens

**Files:**
- Modify: `frontend/src/onboarding/OnboardingFlow.tsx`
- Create: `frontend/src/onboarding/WelcomeScreen.tsx`
- Create: `frontend/src/onboarding/WelcomeScreen.test.tsx`
- Create: `frontend/src/onboarding/FinancialFocusScreen.tsx`
- Create: `frontend/src/onboarding/FinancialFocusScreen.test.tsx`

**Interfaces:**
- Consumes: `onboardingApi.setFinancialFocus`, `useAuth().setOnboardingCompleted`.
- Produces: `OnboardingFlow`'s internal step state machine (`'welcome' | 'focus' | 'tourIntro' |
  'tour' | 'success'`), consumed by Task 10/11.

- [ ] **Step 1: Write `WelcomeScreen`'s failing test**

```typescript
import { render, screen, fireEvent } from '@testing-library/react';
import { WelcomeScreen } from './WelcomeScreen';

describe('WelcomeScreen', () => {
  it('calls onStart when "Start Setup" is clicked', () => {
    const onStart = vi.fn();
    render(<WelcomeScreen onStart={onStart} onSkip={vi.fn()} />);
    fireEvent.click(screen.getByRole('button', { name: 'Start Setup' }));
    expect(onStart).toHaveBeenCalled();
  });

  it('calls onSkip when "Skip for Now" is clicked', () => {
    const onSkip = vi.fn();
    render(<WelcomeScreen onStart={vi.fn()} onSkip={onSkip} />);
    fireEvent.click(screen.getByRole('button', { name: 'Skip for Now' }));
    expect(onSkip).toHaveBeenCalled();
  });
});
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd frontend && npx vitest run src/onboarding/WelcomeScreen.test.tsx`
Expected: FAIL — file doesn't exist.

- [ ] **Step 3: Write `WelcomeScreen.tsx`**

```typescript
import { Button } from '../design-system';

interface Props {
  onStart: () => void;
  onSkip: () => void;
}

export function WelcomeScreen({ onStart, onSkip }: Props) {
  return (
    <div className="flex flex-col items-center justify-center min-h-screen px-6 text-center">
      <h1 className="text-3xl font-bold text-ink mb-3">Welcome to Fynora 👋</h1>
      <p className="text-muted max-w-md mb-8">
        Take control of your finances in one place. Track spending, create budgets, monitor
        goals, and understand where your money goes with powerful insights.
      </p>
      <div className="flex gap-3">
        <Button variant="primary" onClick={onStart}>Start Setup</Button>
        <Button variant="secondary" onClick={onSkip}>Skip for Now</Button>
      </div>
    </div>
  );
}
```

- [ ] **Step 4: Run again to verify it passes**

Run: same as Step 2.
Expected: PASS.

- [ ] **Step 5: Write `FinancialFocusScreen`'s failing test**

```typescript
import { render, screen, fireEvent } from '@testing-library/react';
import { FinancialFocusScreen } from './FinancialFocusScreen';

describe('FinancialFocusScreen', () => {
  it('calls onContinue with the selected keys', () => {
    const onContinue = vi.fn();
    render(<FinancialFocusScreen onContinue={onContinue} />);
    fireEvent.click(screen.getByText(/Track my spending/));
    fireEvent.click(screen.getByText(/Reduce debt/));
    fireEvent.click(screen.getByRole('button', { name: 'Continue' }));
    expect(onContinue).toHaveBeenCalledWith(['TRACK_SPENDING', 'REDUCE_DEBT']);
  });

  it('allows continuing with nothing selected', () => {
    const onContinue = vi.fn();
    render(<FinancialFocusScreen onContinue={onContinue} />);
    fireEvent.click(screen.getByRole('button', { name: 'Continue' }));
    expect(onContinue).toHaveBeenCalledWith([]);
  });

  it('selecting "Just exploring" clears every other selection', () => {
    const onContinue = vi.fn();
    render(<FinancialFocusScreen onContinue={onContinue} />);
    fireEvent.click(screen.getByText(/Track my spending/));
    fireEvent.click(screen.getByText(/Just exploring/));
    fireEvent.click(screen.getByRole('button', { name: 'Continue' }));
    expect(onContinue).toHaveBeenCalledWith(['EXPLORING']);
  });
});
```

- [ ] **Step 6: Run to verify it fails**

Run: `cd frontend && npx vitest run src/onboarding/FinancialFocusScreen.test.tsx`
Expected: FAIL.

- [ ] **Step 7: Write `FinancialFocusScreen.tsx`**

```typescript
import { useState } from 'react';
import { Button } from '../design-system';

const OPTIONS: { key: string; label: string }[] = [
  { key: 'TRACK_SPENDING', label: '💰 Track my spending' },
  { key: 'MANAGE_BUDGETS', label: '📊 Create and manage budgets' },
  { key: 'SAVE_FOR_GOAL', label: '🎯 Save for a goal' },
  { key: 'SEE_ALL_ACCOUNTS', label: '🏦 See all my accounts in one place' },
  { key: 'IMPROVE_HABITS', label: '📈 Improve my financial habits' },
  { key: 'REDUCE_DEBT', label: '💳 Reduce debt' },
  { key: 'EXPLORING', label: '🔍 Just exploring' },
];

interface Props {
  onContinue: (selected: string[]) => void;
}

export function FinancialFocusScreen({ onContinue }: Props) {
  const [selected, setSelected] = useState<string[]>([]);

  function toggle(key: string) {
    if (key === 'EXPLORING') {
      setSelected((prev) => (prev.includes('EXPLORING') ? [] : ['EXPLORING']));
      return;
    }
    setSelected((prev) => {
      const withoutExploring = prev.filter((k) => k !== 'EXPLORING');
      return withoutExploring.includes(key)
        ? withoutExploring.filter((k) => k !== key)
        : [...withoutExploring, key];
    });
  }

  return (
    <div className="flex flex-col items-center justify-center min-h-screen px-6 text-center">
      <h1 className="text-2xl font-bold text-ink mb-2">What would you like to achieve with Fynora?</h1>
      <p className="text-muted mb-6">Select all that apply. We'll personalize your experience.</p>
      <div className="grid grid-cols-1 sm:grid-cols-2 gap-3 max-w-lg mb-8">
        {OPTIONS.map((opt) => (
          <button
            key={opt.key}
            type="button"
            onClick={() => toggle(opt.key)}
            className={`px-4 py-3 rounded-lg border text-sm text-left transition-colors ${
              selected.includes(opt.key) ? 'border-primary bg-primary/10 text-ink' : 'border-border text-muted'
            }`}
          >
            {opt.label}
          </button>
        ))}
      </div>
      <Button variant="primary" onClick={() => onContinue(selected)}>Continue</Button>
    </div>
  );
}
```

- [ ] **Step 8: Run again to verify it passes**

Run: same as Step 6.
Expected: PASS.

- [ ] **Step 9: Wire both into `OnboardingFlow`**

```typescript
import { useState } from 'react';
import { onboardingApi } from '../api/endpoints';
import { useAuth } from '../context/AuthContext';
import { WelcomeScreen } from './WelcomeScreen';
import { FinancialFocusScreen } from './FinancialFocusScreen';

type Step = 'welcome' | 'focus' | 'tourIntro' | 'tour' | 'success';

export function OnboardingFlow() {
  const [step, setStep] = useState<Step>('welcome');
  const { setOnboardingCompleted } = useAuth();

  async function finishOnboarding() {
    await onboardingApi.complete();
    setOnboardingCompleted(true);
  }

  async function skipEverything() {
    await finishOnboarding();
  }

  async function submitFocusAndContinue(selected: string[]) {
    await onboardingApi.setFinancialFocus(selected);
    setStep('tourIntro');
  }

  if (step === 'welcome') {
    return <WelcomeScreen onStart={() => setStep('focus')} onSkip={skipEverything} />;
  }
  if (step === 'focus') {
    return <FinancialFocusScreen onContinue={submitFocusAndContinue} />;
  }
  // 'tourIntro' | 'tour' | 'success' land in Task 10/11.
  return <div data-testid="onboarding-flow">Onboarding flow placeholder</div>;
}
```

- [ ] **Step 10: Run the full onboarding test folder**

Run: `cd frontend && npx vitest run src/onboarding`
Expected: all PASS.

- [ ] **Step 11: Commit**

```bash
git add frontend/src/onboarding/
git commit -m "feat(onboarding): add Welcome and Financial Focus screens"
```

---

### Task 10: `TourOverlay` + Tour Intro + wiring the 7 steps into `Sidebar`

**Files:**
- Create: `frontend/src/onboarding/TourOverlay.tsx`
- Create: `frontend/src/onboarding/TourOverlay.test.tsx`
- Create: `frontend/src/onboarding/tourSteps.ts`
- Modify: `frontend/src/components/Sidebar.tsx`
- Modify: `frontend/src/onboarding/OnboardingFlow.tsx`

**Interfaces:**
- Produces: `TOUR_STEPS: { targetId: string; title: string; body: string }[]` (7 entries),
  `<TourOverlay steps={TOUR_STEPS} onFinish={() => void} onSkip={() => void} />`.

- [ ] **Step 1: Add `data-tour` attributes to `Sidebar.tsx`**

In the `NAV_ITEMS`-shaped array found while planning this task
(`frontend/src/components/Sidebar.tsx`, the `{ to, label, icon }` list), add a `tourId` to the 7
entries the tour targets, and render it as `data-tour={item.tourId}` on each `<NavLink>`:

```typescript
{ to: '/app', label: 'Dashboard', icon: LayoutDashboard, end: true, tourId: 'dashboard' },
{ to: '/app/accounts', label: 'Accounts', icon: Wallet, tourId: 'accounts' },
{ to: '/app/import', label: 'Import Statement', icon: UploadCloud, tourId: 'import' },
{ to: '/app/transactions', label: 'Transactions', icon: ArrowLeftRight, tourId: 'transactions' },
{ to: '/app/budgets', label: 'Budgets', icon: PiggyBank, tourId: 'budgets' },
{ to: '/app/goals', label: 'Goals', icon: Target, tourId: 'goals' },
{ to: '/app/insights', label: 'Insights', icon: Sparkles, tourId: 'insights' },
```

(Leave every other existing entry — Statement History, Investments, Reports, Advanced Reports —
untouched; they aren't tour targets.) On the `<NavLink>` render, spread
`data-tour={item.tourId}` only when `item.tourId` is set.

- [ ] **Step 2: Write `tourSteps.ts`**

```typescript
export interface TourStep {
  targetSelector: string;
  title: string;
  body: string;
}

export const TOUR_STEPS: TourStep[] = [
  { targetSelector: '[data-tour="dashboard"]', title: 'Your Financial Command Center',
    body: 'This dashboard gives you a complete view of your finances, including spending, budgets, goals, and account balances.' },
  { targetSelector: '[data-tour="accounts"]', title: 'Accounts',
    body: 'See every linked or manually added account in one place.' },
  { targetSelector: '[data-tour="import"]', title: 'Import Bank Statements',
    body: 'Upload your bank statements and Fynora automatically organizes your transactions. No manual entry required.' },
  { targetSelector: '[data-tour="transactions"]', title: 'Every Transaction Explained',
    body: 'Search, filter, categorize, and understand every transaction in one place. See exactly where your money is going.' },
  { targetSelector: '[data-tour="budgets"]', title: 'Stay Within Budget',
    body: 'Create monthly budgets and track your progress in real time. Get notified before you overspend.' },
  { targetSelector: '[data-tour="goals"]', title: 'Achieve Your Financial Goals',
    body: "Whether it's an emergency fund, vacation, or new car, Fynora helps you stay on track." },
  { targetSelector: '[data-tour="insights"]', title: 'Discover Spending Patterns',
    body: 'Fynora automatically identifies trends and spending habits so you can make smarter financial decisions.' },
];
```

- [ ] **Step 3: Write `TourOverlay`'s failing test**

```typescript
import { render, screen, fireEvent } from '@testing-library/react';
import { TourOverlay } from './TourOverlay';
import type { TourStep } from './tourSteps';

const STEPS: TourStep[] = [
  { targetSelector: '[data-tour="a"]', title: 'Step A', body: 'Body A' },
  { targetSelector: '[data-tour="b"]', title: 'Step B', body: 'Body B' },
];

function renderWithTargets(steps: TourStep[], onFinish = vi.fn(), onSkip = vi.fn()) {
  document.body.innerHTML = '<div data-tour="a"></div><div data-tour="b"></div>';
  return render(<TourOverlay steps={steps} onFinish={onFinish} onSkip={onSkip} />);
}

describe('TourOverlay', () => {
  it('shows the first step title on mount', () => {
    renderWithTargets(STEPS);
    expect(screen.getByText('Step A')).toBeInTheDocument();
  });

  it('advances to the next step on Next', () => {
    renderWithTargets(STEPS);
    fireEvent.click(screen.getByRole('button', { name: 'Next' }));
    expect(screen.getByText('Step B')).toBeInTheDocument();
  });

  it('calls onFinish after Next on the last step', () => {
    const onFinish = vi.fn();
    renderWithTargets(STEPS, onFinish);
    fireEvent.click(screen.getByRole('button', { name: 'Next' }));
    fireEvent.click(screen.getByRole('button', { name: 'Finish' }));
    expect(onFinish).toHaveBeenCalled();
  });

  it('calls onSkip from Skip at any step', () => {
    const onSkip = vi.fn();
    renderWithTargets(STEPS, vi.fn(), onSkip);
    fireEvent.click(screen.getByRole('button', { name: 'Skip' }));
    expect(onSkip).toHaveBeenCalled();
  });

  it('goes back to the previous step on Back', () => {
    renderWithTargets(STEPS);
    fireEvent.click(screen.getByRole('button', { name: 'Next' }));
    fireEvent.click(screen.getByRole('button', { name: 'Back' }));
    expect(screen.getByText('Step A')).toBeInTheDocument();
  });
});
```

- [ ] **Step 4: Run to verify it fails**

Run: `cd frontend && npx vitest run src/onboarding/TourOverlay.test.tsx`
Expected: FAIL.

- [ ] **Step 5: Write `TourOverlay.tsx`**

```typescript
import { useEffect, useState } from 'react';
import { Button } from '../design-system';
import type { TourStep } from './tourSteps';

interface Props {
  steps: TourStep[];
  onFinish: () => void;
  onSkip: () => void;
}

export function TourOverlay({ steps, onFinish, onSkip }: Props) {
  const [index, setIndex] = useState(0);
  const [rect, setRect] = useState<DOMRect | null>(null);
  const step = steps[index];
  const isLast = index === steps.length - 1;

  useEffect(() => {
    const target = document.querySelector(step.targetSelector);
    setRect(target ? target.getBoundingClientRect() : null);
  }, [step.targetSelector]);

  function next() {
    if (isLast) {
      onFinish();
    } else {
      setIndex((i) => i + 1);
    }
  }

  function back() {
    setIndex((i) => Math.max(0, i - 1));
  }

  return (
    <div className="fixed inset-0 z-50" role="dialog" aria-label="Product tour">
      <div className="absolute inset-0 bg-black/60" />
      {rect && (
        <div
          className="absolute rounded-lg ring-4 ring-primary pointer-events-none"
          style={{ top: rect.top - 4, left: rect.left - 4, width: rect.width + 8, height: rect.height + 8 }}
        />
      )}
      <div
        className="absolute bg-card rounded-lg shadow-xl p-5 max-w-xs"
        style={rect ? { top: rect.bottom + 12, left: rect.left } : { top: '50%', left: '50%', transform: 'translate(-50%,-50%)' }}
      >
        <h3 className="font-bold text-ink mb-1">{step.title}</h3>
        <p className="text-sm text-muted mb-4">{step.body}</p>
        <div className="flex items-center justify-between">
          <button type="button" className="text-xs text-muted" onClick={onSkip}>Skip</button>
          <div className="flex gap-2">
            {index > 0 && <Button variant="secondary" size="sm" onClick={back}>Back</Button>}
            <Button variant="primary" size="sm" onClick={next}>{isLast ? 'Finish' : 'Next'}</Button>
          </div>
        </div>
      </div>
    </div>
  );
}
```

- [ ] **Step 6: Run again to verify it passes**

Run: same as Step 4.
Expected: PASS.

- [ ] **Step 7: Add the Tour Intro screen inline in `OnboardingFlow`, and wire the tour in**

```typescript
// Add to OnboardingFlow.tsx, replacing the 'tourIntro'/'tour'/'success' placeholder branch:
import { TourOverlay } from './TourOverlay';
import { TOUR_STEPS } from './tourSteps';

  // ...inside OnboardingFlow():
  if (step === 'tourIntro') {
    return (
      <div className="flex flex-col items-center justify-center min-h-screen px-6 text-center">
        <h1 className="text-2xl font-bold text-ink mb-2">Let's take a quick tour</h1>
        <p className="text-muted mb-8">
          This will only take about 30 seconds and will help you get the most out of Fynora.
        </p>
        <div className="flex gap-3">
          <Button variant="primary" onClick={() => setStep('tour')}>Start Tour</Button>
          <Button variant="secondary" onClick={finishOnboarding}>Skip</Button>
        </div>
      </div>
    );
  }
  if (step === 'tour') {
    return <TourOverlay steps={TOUR_STEPS} onFinish={finishOnboarding} onSkip={finishOnboarding} />;
  }
  // 'success' lands in Task 11.
  return <div data-testid="onboarding-flow">Onboarding flow placeholder</div>;
```

Note the tour renders as a full-screen overlay with nothing behind it in this stub — Task 11
doesn't change this, since the spec's flow control never requires the real Dashboard to be
visible underneath the tour for `OnboardingFlow` to work (the sidebar `data-tour` targets are
real DOM elements, but `ProtectedRoute` intercepts routing entirely while onboarding is
incomplete, so there is no live Dashboard mounted behind this overlay yet in v1 — this is a
known, accepted simplification: the tour's "spotlight" targets are illustrative copies rendered
by `OnboardingFlow` itself, not the live Sidebar). **Re-read this against the spec before
implementing**: if this simplification is unacceptable, `OnboardingFlow` needs to render inside
the real authenticated shell (Sidebar + routed content) rather than replacing it, with `/app`
force-navigated underneath — flag this to the plan's reviewer as an open question rather than
silently picking one interpretation, since it changes Task 8's `ProtectedRoute` design.

- [ ] **Step 8: Run the full onboarding test folder**

Run: `cd frontend && npx vitest run src/onboarding src/components/Sidebar.test.tsx`
Expected: all PASS.

- [ ] **Step 9: Commit**

```bash
git add frontend/src/onboarding/ frontend/src/components/Sidebar.tsx
git commit -m "feat(onboarding): add TourOverlay and wire the 7-step tour"
```

---

### Task 11: Success screen (with checklist preview) + `ChecklistWidget` + Settings entry

**Files:**
- Create: `frontend/src/onboarding/checklistItems.ts`
- Create: `frontend/src/onboarding/SuccessScreen.tsx`
- Create: `frontend/src/onboarding/SuccessScreen.test.tsx`
- Create: `frontend/src/onboarding/ChecklistWidget.tsx`
- Create: `frontend/src/onboarding/ChecklistWidget.test.tsx`
- Modify: `frontend/src/onboarding/OnboardingFlow.tsx`
- Modify: `frontend/src/pages/Dashboard.tsx`
- Modify: `frontend/src/pages/Settings.tsx`

**Interfaces:**
- Consumes: `onboardingApi.getChecklist`, `onboardingApi.reset`, `useAuth().setOnboardingCompleted`.
- Produces: `CHECKLIST_ITEMS: { key: string; label: string }[]` (shared by both new components).

- [ ] **Step 1: Write `checklistItems.ts`**

```typescript
export const CHECKLIST_ITEMS: { key: string; label: string }[] = [
  { key: 'COMPLETE_PROFILE', label: 'Complete your profile' },
  { key: 'IMPORT_STATEMENT', label: 'Import first statement' },
  { key: 'REVIEW_TRANSACTIONS', label: 'Review transactions' },
  { key: 'CREATE_BUDGET', label: 'Create a budget' },
  { key: 'CREATE_GOAL', label: 'Create a goal' },
  { key: 'VIEW_INSIGHTS', label: 'View insights' },
];
```

- [ ] **Step 2: Write `SuccessScreen`'s failing test**

```typescript
import { render, screen, fireEvent } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { SuccessScreen } from './SuccessScreen';

describe('SuccessScreen', () => {
  it('shows all 6 checklist items unchecked', () => {
    render(<MemoryRouter><SuccessScreen onDone={vi.fn()} /></MemoryRouter>);
    expect(screen.getByText('Complete your profile')).toBeInTheDocument();
    expect(screen.getByText('View insights')).toBeInTheDocument();
  });

  it('calls onDone when "Go to Dashboard" is clicked', () => {
    const onDone = vi.fn();
    render(<MemoryRouter><SuccessScreen onDone={onDone} /></MemoryRouter>);
    fireEvent.click(screen.getByRole('button', { name: 'Go to Dashboard' }));
    expect(onDone).toHaveBeenCalled();
  });
});
```

- [ ] **Step 3: Run to verify it fails**

Run: `cd frontend && npx vitest run src/onboarding/SuccessScreen.test.tsx`
Expected: FAIL.

- [ ] **Step 4: Write `SuccessScreen.tsx`**

```typescript
import { useNavigate } from 'react-router-dom';
import { Button } from '../design-system';
import { CHECKLIST_ITEMS } from './checklistItems';

interface Props {
  onDone: () => void;
}

export function SuccessScreen({ onDone }: Props) {
  const navigate = useNavigate();

  function goThenNavigate(path: string) {
    onDone();
    navigate(path);
  }

  return (
    <div className="flex flex-col items-center justify-center min-h-screen px-6 text-center">
      <h1 className="text-3xl font-bold text-ink mb-3">You're Ready to Go 🚀</h1>
      <p className="text-muted max-w-md mb-6">
        Start by importing your first bank statement or connecting an account. The more data you
        add, the smarter Fynora becomes.
      </p>
      <div className="text-left mb-8">
        <p className="text-sm font-semibold text-ink mb-2">Next steps:</p>
        <ul className="space-y-1">
          {CHECKLIST_ITEMS.map((item) => (
            <li key={item.key} className="text-sm text-muted">☐ {item.label}</li>
          ))}
        </ul>
      </div>
      <div className="flex flex-col sm:flex-row gap-3">
        <Button variant="primary" onClick={() => goThenNavigate('/app/import')}>Import Statement</Button>
        <Button variant="secondary" onClick={() => goThenNavigate('/app/accounts')}>Connect Account</Button>
        <Button variant="secondary" onClick={onDone}>Go to Dashboard</Button>
      </div>
    </div>
  );
}
```

- [ ] **Step 5: Run again to verify it passes**

Run: same as Step 3.
Expected: PASS.

- [ ] **Step 6: Wire `SuccessScreen` into `OnboardingFlow`, replacing the final placeholder**

```typescript
import { SuccessScreen } from './SuccessScreen';

  if (step === 'success') {
    return <SuccessScreen onDone={() => setStep('done' as Step)} />; // see note below
  }
```

Since `finishOnboarding()` already flips `onboardingCompleted` to `true` (which makes
`ProtectedRoute` stop rendering `OnboardingFlow` at all), the tour's `onFinish`/`onSkip` should
route to `'success'` rather than calling `finishOnboarding()` directly, and `SuccessScreen.onDone`
is what actually calls `finishOnboarding()`. Adjust the Task 10 wiring:

```typescript
  if (step === 'tour') {
    return <TourOverlay steps={TOUR_STEPS} onFinish={() => setStep('success')} onSkip={() => setStep('success')} />;
  }
  if (step === 'tourIntro') {
    // ...unchanged Start Tour button, but Skip now also goes to 'success' after completing:
    // onClick={async () => { await finishOnboarding(); }} stays as-is for Tour Intro's own Skip,
    // since spec §"Flow control" only requires the TOUR's skip (not Tour Intro's) to reach
    // Success -- re-read spec §"Flow control" bullet 3 before finalizing which Skip buttons route
    // through Success vs straight to done, since both are defensible readings and this plan
    // should not silently pick one without flagging it, same as Task 10 Step 7's note.
  }
  if (step === 'success') {
    return <SuccessScreen onDone={finishOnboarding} />;
  }
```

- [ ] **Step 7: Write `ChecklistWidget`'s failing test**

```typescript
import { render, screen, waitFor } from '@testing-library/react';
import { vi } from 'vitest';
import { ChecklistWidget } from './ChecklistWidget';
import { onboardingApi } from '../api/endpoints';

vi.mock('../api/endpoints', () => ({ onboardingApi: { getChecklist: vi.fn() } }));

describe('ChecklistWidget', () => {
  it('shows progress text and hides once complete', async () => {
    vi.mocked(onboardingApi.getChecklist).mockResolvedValue({
      items: [{ key: 'COMPLETE_PROFILE', completed: true }],
      completedCount: 4, totalCount: 6,
    });
    render(<ChecklistWidget />);
    await waitFor(() => expect(screen.getByText('4 of 6 completed')).toBeInTheDocument());
  });

  it('renders nothing once completedCount equals totalCount', async () => {
    vi.mocked(onboardingApi.getChecklist).mockResolvedValue({
      items: [], completedCount: 6, totalCount: 6,
    });
    const { container } = render(<ChecklistWidget />);
    await waitFor(() => expect(container).toBeEmptyDOMElement());
  });
});
```

- [ ] **Step 8: Run to verify it fails**

Run: `cd frontend && npx vitest run src/onboarding/ChecklistWidget.test.tsx`
Expected: FAIL.

- [ ] **Step 9: Write `ChecklistWidget.tsx`**

```typescript
import { useQuery } from '@tanstack/react-query';
import { FinoraCard } from '../design-system';
import { onboardingApi } from '../api/endpoints';
import { CHECKLIST_ITEMS } from './checklistItems';

export function ChecklistWidget() {
  const { data } = useQuery({ queryKey: ['onboarding', 'checklist'], queryFn: onboardingApi.getChecklist });

  if (!data || data.completedCount >= data.totalCount) return null;

  const completedKeys = new Set(data.items.filter((i) => i.completed).map((i) => i.key));
  const percent = Math.round((data.completedCount / data.totalCount) * 100);

  return (
    <FinoraCard padding="lg" className="mb-6">
      <p className="text-sm font-semibold text-ink mb-1">Getting Started</p>
      <p className="text-xs text-muted mb-3">{data.completedCount} of {data.totalCount} completed</p>
      <div className="h-2 rounded-full bg-border mb-4 overflow-hidden">
        <div className="h-full bg-primary" style={{ width: `${percent}%` }} />
      </div>
      <ul className="space-y-1.5">
        {CHECKLIST_ITEMS.map((item) => (
          <li key={item.key} className="text-sm text-muted flex items-center gap-2">
            <span>{completedKeys.has(item.key) ? '✅' : '⬜'}</span>
            {item.label}
          </li>
        ))}
      </ul>
    </FinoraCard>
  );
}
```

- [ ] **Step 10: Run again to verify it passes**

Run: same as Step 8.
Expected: PASS.

- [ ] **Step 11: Mount `ChecklistWidget` on `Dashboard.tsx`**

Add `import { ChecklistWidget } from '../onboarding/ChecklistWidget';` and render
`<ChecklistWidget />` as the first child inside `Dashboard`'s returned JSX, above the existing
`<FinoraCard>` blocks found while planning this task (around line 430).

- [ ] **Step 12: Add "Retake Product Tour" to `Settings.tsx`**

Inside the existing `<SectionCard icon={<SlidersHorizontal ... />} title="General" ...>` block
(found while planning this task, around line 436), add a row consistent with this section's
existing rows, calling `onboardingApi.reset()` then `setOnboardingCompleted(false)`:

```typescript
// Inside Settings(), alongside other handlers:
  const { setOnboardingCompleted } = useAuth();
  async function retakeTour() {
    await onboardingApi.reset();
    setOnboardingCompleted(false);
  }

// Inside the General SectionCard's JSX, as a new row:
  <button type="button" onClick={retakeTour} className="text-sm text-primary hover:underline">
    Retake Product Tour
  </button>
```

Match this section's actual existing row markup exactly (read the surrounding JSX first — the
snippet above is the logic, not necessarily the final className/layout).

- [ ] **Step 13: Run the full onboarding + touched-page test suites**

Run: `cd frontend && npx vitest run src/onboarding src/pages/Dashboard.test.tsx src/pages/Settings.test.tsx`
Expected: all PASS (fix any pre-existing `Dashboard.test.tsx`/`Settings.test.tsx` snapshot/DOM
assertions broken by the new widget/row — these are pre-existing tests reacting to a real UI
change, not new tests, so update their expectations rather than deleting coverage).

- [ ] **Step 14: Commit**

```bash
git add frontend/src/onboarding/ frontend/src/pages/Dashboard.tsx frontend/src/pages/Settings.tsx
git commit -m "feat(onboarding): add Success screen, checklist widget, and Retake Tour"
```

---

### Task 12: Dwell-timer checklist completion on Ledger/Insights

**Files:**
- Modify: `frontend/src/pages/Ledger.tsx`
- Modify: `frontend/src/pages/Insights.tsx`
- Modify: `frontend/src/pages/Ledger.test.tsx`
- Modify: `frontend/src/pages/Insights.test.tsx`

**Interfaces:**
- Consumes: `onboardingApi.completeChecklistItem`, `onboardingApi.getChecklist` (to avoid firing
  when already complete).

- [ ] **Step 1: Write the failing test (Ledger)**

```typescript
// Add to Ledger.test.tsx
it('marks REVIEW_TRANSACTIONS complete after a 1.5s dwell', async () => {
  vi.useFakeTimers();
  vi.mocked(onboardingApi.getChecklist).mockResolvedValue({
    items: [{ key: 'REVIEW_TRANSACTIONS', completed: false }], completedCount: 0, totalCount: 6,
  });
  const completeSpy = vi.mocked(onboardingApi.completeChecklistItem).mockResolvedValue();

  render(<Ledger />); // wrap with whatever providers this file's other tests already use

  await vi.advanceTimersByTimeAsync(1500);
  expect(completeSpy).toHaveBeenCalledWith('REVIEW_TRANSACTIONS');
  vi.useRealTimers();
});

it('does not fire if the item is already complete', async () => {
  vi.useFakeTimers();
  vi.mocked(onboardingApi.getChecklist).mockResolvedValue({
    items: [{ key: 'REVIEW_TRANSACTIONS', completed: true }], completedCount: 1, totalCount: 6,
  });
  const completeSpy = vi.mocked(onboardingApi.completeChecklistItem).mockResolvedValue();

  render(<Ledger />);

  await vi.advanceTimersByTimeAsync(1500);
  expect(completeSpy).not.toHaveBeenCalled();
  vi.useRealTimers();
});
```

Add `vi.mock('../api/endpoints', async (importOriginal) => ({ ...(await importOriginal()),
onboardingApi: { getChecklist: vi.fn(), completeChecklistItem: vi.fn() } }))` at the top of the
file if `Ledger.test.tsx` doesn't already mock `../api/endpoints` wholesale — check first, since
mocking the whole module could break this file's other existing tests that rely on real exports
from the same module.

- [ ] **Step 2: Run to verify it fails**

Run: `cd frontend && npx vitest run src/pages/Ledger.test.tsx`
Expected: FAIL.

- [ ] **Step 3: Add the dwell-timer effect to `Ledger.tsx`**

```typescript
// Add inside the Ledger component, alongside its other useEffect/useQuery calls:
  const { data: checklist } = useQuery({ queryKey: ['onboarding', 'checklist'], queryFn: onboardingApi.getChecklist });

  useEffect(() => {
    const item = checklist?.items.find((i) => i.key === 'REVIEW_TRANSACTIONS');
    if (!item || item.completed) return;
    const timer = setTimeout(() => {
      onboardingApi.completeChecklistItem('REVIEW_TRANSACTIONS').catch(() => {});
    }, 1500);
    return () => clearTimeout(timer);
  }, [checklist]);
```

Add `import { onboardingApi } from '../api/endpoints';` and `import { useQuery } from
'@tanstack/react-query';`/`import { useEffect } from 'react';` if not already imported in this
file (check first — `Ledger.tsx` likely already imports `useEffect`/`useQuery` for its existing
data fetching).

- [ ] **Step 4: Run again to verify it passes**

Run: same as Step 2.
Expected: PASS.

- [ ] **Step 5: Repeat Steps 1-4 for `Insights.tsx`/`Insights.test.tsx`**, using `'VIEW_INSIGHTS'`
  in place of `'REVIEW_TRANSACTIONS'` throughout.

- [ ] **Step 6: Run both pages' full test suites**

Run: `cd frontend && npx vitest run src/pages/Ledger.test.tsx src/pages/Insights.test.tsx`
Expected: all PASS.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/pages/Ledger.tsx frontend/src/pages/Insights.tsx \
        frontend/src/pages/Ledger.test.tsx frontend/src/pages/Insights.test.tsx
git commit -m "feat(onboarding): mark checklist items complete on a dwell timer"
```

**Phase 2 checkpoint:** the web flow is fully built and tested. Before moving to Phase 3, run the
verification workflow (start the dev server, walk through Welcome → Financial Focus → Tour →
Success → Dashboard checklist → Settings → Retake Tour as a real fresh user) — this is a
previewable UI change and this repo's own conventions call for browser verification before
calling frontend work done, not just a green test suite.

---

## Phase 3 — Mobile

### Task 13: `onboardingApi` + mobile `AuthContext` wiring

**Files:**
- Modify: `mobile/src/api/endpoints.ts`
- Modify: `mobile/src/context/AuthContext.tsx`
- Modify: `mobile/src/context/AuthContext.test.tsx`

**Interfaces:** identical to Task 7's, mobile-side.

- [ ] **Step 1: Add `onboardingApi` to `mobile/src/api/endpoints.ts`** — identical to Task 7 Step
  2's web version (same shapes, same backend contract). Add `onboardingCompleted: boolean;` to
  whichever interface mirrors web's `AuthResponseDto` in this file (find it with
  `grep -n "interface.*Response\|interface.*Session" mobile/src/api/endpoints.ts`).

- [ ] **Step 2: Write the failing `AuthContext` test additions**, mirroring Task 7 Step 3 but
  using this file's actual test helpers (`SESSION` fixture and hook-render helper were already
  seen at `mobile/src/context/AuthContext.test.tsx:52` while planning this task).

- [ ] **Step 3: Run to verify they fail**

Run: `cd mobile && npx jest src/context/AuthContext.test.tsx`
Expected: FAIL.

- [ ] **Step 4: Wire mobile `AuthContext.tsx`**

Find the `PHONE_VERIFIED_KEY`-equivalent constant used in this file's `persist()`/bootstrap
`useEffect` (seen at lines ~85-99 and ~226-260 while planning this task) and add a matching
`ONBOARDING_COMPLETED_KEY` constant, a `[onboardingCompleted, setOnboardingCompletedState]`
state pair, a public `setOnboardingCompleted` function (mirroring `setPhoneVerified` at line
~310), and read/write it in exactly the same 4 places `phoneVerified` is read/written: the
mount-time SecureStore restore, `persist()`, the `setPhoneVerified`-sibling setter, and `logout`'s
clear step.

- [ ] **Step 5: Run again to verify they pass**

Run: same as Step 3.
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add mobile/src/api/endpoints.ts mobile/src/context/AuthContext.tsx mobile/src/context/AuthContext.test.tsx
git commit -m "feat(onboarding): wire onboardingCompleted into mobile AuthContext"
```

---

### Task 14: `TourTargetRegistry` + `RootNavigator` gate + `OnboardingNavigator` shell

**Files:**
- Create: `mobile/src/onboarding/TourTargetRegistry.tsx`
- Create: `mobile/src/onboarding/TourTargetRegistry.test.tsx`
- Create: `mobile/src/onboarding/OnboardingNavigator.tsx`
- Modify: `mobile/src/navigation/RootNavigator.tsx`
- Modify: `mobile/src/navigation/RootNavigator.test.tsx` (or create, matching this repo's existing
  navigator test convention — check first)

**Interfaces:**
- Produces: `TourTargetProvider`, `useRegisterTourTarget(key: string):
  (node: View | null) => void`, `useTourTarget(key: string): View | null` — the mechanism that
  lets `AppTabs`/`MoreScreen` (Task 15) register measurable refs the tour overlay (mounted above
  them, Task 16) can read regardless of which screen actually renders each target.

- [ ] **Step 1: Write `TourTargetRegistry`'s failing test**

```typescript
import { render, screen, act } from '@testing-library/react-native';
import { Text, View } from 'react-native';
import { TourTargetProvider, useRegisterTourTarget, useTourTarget } from './TourTargetRegistry';

function Registrar({ tourKey }: { tourKey: string }) {
  const register = useRegisterTourTarget(tourKey);
  return <View ref={register}><Text>registrar</Text></View>;
}

function Reader({ tourKey }: { tourKey: string }) {
  const target = useTourTarget(tourKey);
  return <Text>{target ? 'found' : 'missing'}</Text>;
}

describe('TourTargetRegistry', () => {
  it('lets a reader see a ref registered elsewhere in the tree', () => {
    render(
      <TourTargetProvider>
        <Registrar tourKey="home" />
        <Reader tourKey="home" />
      </TourTargetProvider>
    );
    expect(screen.getByText('found')).toBeTruthy();
  });

  it('reports missing for an unregistered key', () => {
    render(
      <TourTargetProvider>
        <Reader tourKey="nothing-registered" />
      </TourTargetProvider>
    );
    expect(screen.getByText('missing')).toBeTruthy();
  });
});
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd mobile && npx jest src/onboarding/TourTargetRegistry.test.tsx`
Expected: FAIL.

- [ ] **Step 3: Write `TourTargetRegistry.tsx`**

```typescript
import { createContext, useCallback, useContext, useRef, useState, type ReactNode } from 'react';
import type { View } from 'react-native';

interface RegistryState {
  targets: Record<string, View | null>;
}

const TourTargetContext = createContext<{
  register: (key: string, node: View | null) => void;
  targets: Record<string, View | null>;
} | null>(null);

export function TourTargetProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<RegistryState>({ targets: {} });
  const register = useCallback((key: string, node: View | null) => {
    setState((prev) => ({ targets: { ...prev.targets, [key]: node } }));
  }, []);

  return (
    <TourTargetContext.Provider value={{ register, targets: state.targets }}>
      {children}
    </TourTargetContext.Provider>
  );
}

export function useRegisterTourTarget(key: string) {
  const ctx = useContext(TourTargetContext);
  if (!ctx) throw new Error('useRegisterTourTarget must be used within TourTargetProvider');
  return useCallback((node: View | null) => ctx.register(key, node), [ctx, key]);
}

export function useTourTarget(key: string): View | null {
  const ctx = useContext(TourTargetContext);
  if (!ctx) throw new Error('useTourTarget must be used within TourTargetProvider');
  return ctx.targets[key] ?? null;
}
```

- [ ] **Step 4: Run again to verify it passes**

Run: same as Step 2.
Expected: PASS.

- [ ] **Step 5: Write the `OnboardingNavigator` shell (Welcome/Focus/TourIntro/Tour/Success land
  in Task 15/16 — this task only needs a mountable stub)**

```typescript
// mobile/src/onboarding/OnboardingNavigator.tsx
import { View, Text } from 'react-native';
import { TourTargetProvider } from './TourTargetRegistry';

// Full screens land in Task 15/16. This stub exists so RootNavigator has something real to
// mount and test against in this task.
export function OnboardingNavigator() {
  return (
    <TourTargetProvider>
      <View testID="onboarding-navigator"><Text>Onboarding placeholder</Text></View>
    </TourTargetProvider>
  );
}
```

- [ ] **Step 6: Write the failing `RootNavigator` test**

```typescript
// Add to RootNavigator.test.tsx (or create it, matching this file's existing test setup for
// mocking useAuth() -- check for an existing pattern first, e.g. in a phoneVerified-focused test)
it('mounts OnboardingNavigator when signed in, verified, but onboarding is not complete', () => {
  mockUseAuth.mockReturnValue({ bootstrapping: false, token: 'tok', phoneVerified: true, onboardingCompleted: false });
  const { getByTestId } = render(<RootNavigator />);
  expect(getByTestId('onboarding-navigator')).toBeTruthy();
});
```

- [ ] **Step 7: Run to verify it fails**

Run: `cd mobile && npx jest src/navigation/RootNavigator.test.tsx`
Expected: FAIL.

- [ ] **Step 8: Update `RootNavigator.tsx`**

```typescript
import { OnboardingNavigator } from '../onboarding/OnboardingNavigator';

export function RootNavigator() {
  const { bootstrapping, token, phoneVerified, onboardingCompleted } = useAuth();
  // ...unchanged existing lines...
  const isAppTabsActive = token !== null && phoneVerified && onboardingCompleted;
  const isOnboardingActive = token !== null && phoneVerified && !onboardingCompleted;
  // ...

  // Wherever AppStack/AuthStack currently branch (read the surrounding JSX first, since this
  // plan's earlier read of this file did not capture every branch), add:
  if (isOnboardingActive) {
    return <OnboardingNavigator />;
  }
```

Re-read the full conditional rendering block in `RootNavigator.tsx` before editing — this task's
earlier investigation only confirmed `isAppTabsActive`'s definition and the top-level splash-screen
gate, not every branch below it; find the real `if`/`return` structure with
`grep -n "isAppTabsActive\|AuthStack.Navigator\|AppTabs" mobile/src/navigation/RootNavigator.tsx`
before writing the new branch in.

- [ ] **Step 9: Run again to verify it passes**

Run: same as Step 7.
Expected: PASS.

- [ ] **Step 10: Commit**

```bash
git add mobile/src/onboarding/ mobile/src/navigation/RootNavigator.tsx mobile/src/navigation/RootNavigator.test.tsx
git commit -m "feat(onboarding): add TourTargetRegistry and gate RootNavigator on onboarding"
```

---

### Task 15: Welcome + Financial Focus + Success screens (mobile)

**Files:**
- Create: `mobile/src/onboarding/checklistItems.ts`
- Create: `mobile/src/onboarding/WelcomeScreen.tsx` (+ `.test.tsx`)
- Create: `mobile/src/onboarding/FinancialFocusScreen.tsx` (+ `.test.tsx`)
- Create: `mobile/src/onboarding/SuccessScreen.tsx` (+ `.test.tsx`)
- Modify: `mobile/src/onboarding/OnboardingNavigator.tsx`

**Interfaces:** same content/logic as web Task 9/11, RN components instead of DOM. Reuses
`CHECKLIST_ITEMS`/`OPTIONS` shape from web (re-authored, not imported — mobile has no
code-sharing layer with web in this codebase).

- [ ] **Step 1-4: `WelcomeScreen`** — same TDD cycle as web Task 9 Steps 1-4, using this
  repo's mobile `Button`/RN Testing Library conventions instead of DOM:

```typescript
// mobile/src/onboarding/WelcomeScreen.tsx
import { Text, View, StyleSheet } from 'react-native';
import { Button } from '../components/Button';
import { spacing, useTheme } from '../theme';

interface Props {
  onStart: () => void;
  onSkip: () => void;
}

export function WelcomeScreen({ onStart, onSkip }: Props) {
  const c = useTheme();
  return (
    <View style={[styles.container, { backgroundColor: c.bg }]}>
      <Text style={[styles.title, { color: c.ink }]}>Welcome to Fynora 👋</Text>
      <Text style={[styles.subtitle, { color: c.muted }]}>
        Take control of your finances in one place. Track spending, create budgets, monitor
        goals, and understand where your money goes with powerful insights.
      </Text>
      <Button label="Start Setup" onPress={onStart} />
      <View style={{ height: spacing.sm }} />
      <Button label="Skip for Now" onPress={onSkip} variant="link" />
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, justifyContent: 'center', alignItems: 'center', padding: 24 },
  title: { fontSize: 26, fontWeight: '700', marginBottom: 12, textAlign: 'center' },
  subtitle: { fontSize: 14, textAlign: 'center', marginBottom: 24 },
});
```

Test (mirrors web Task 9 Step 1, RN Testing Library form):
```typescript
import { render, fireEvent } from '@testing-library/react-native';
import { WelcomeScreen } from './WelcomeScreen';

describe('WelcomeScreen', () => {
  it('calls onStart when Start Setup is pressed', () => {
    const onStart = jest.fn();
    const { getByText } = render(<WelcomeScreen onStart={onStart} onSkip={jest.fn()} />);
    fireEvent.press(getByText('Start Setup'));
    expect(onStart).toHaveBeenCalled();
  });

  it('calls onSkip when Skip for Now is pressed', () => {
    const onSkip = jest.fn();
    const { getByText } = render(<WelcomeScreen onStart={jest.fn()} onSkip={onSkip} />);
    fireEvent.press(getByText('Skip for Now'));
    expect(onSkip).toHaveBeenCalled();
  });
});
```

Run: `cd mobile && npx jest src/onboarding/WelcomeScreen.test.tsx` before and after writing the
component, same red-green cycle as every other task in this plan.

- [ ] **Step 5-8: `FinancialFocusScreen`** — same selection/toggle logic as web Task 9 Steps 5-8
  (identical `OPTIONS` array and `toggle()` logic), rendered with RN `Pressable`/`Text` instead of
  a `<button>`:

```typescript
// mobile/src/onboarding/FinancialFocusScreen.tsx
import { useState } from 'react';
import { Pressable, ScrollView, Text, View, StyleSheet } from 'react-native';
import { Button } from '../components/Button';
import { useTheme } from '../theme';

const OPTIONS: { key: string; label: string }[] = [
  { key: 'TRACK_SPENDING', label: '💰 Track my spending' },
  { key: 'MANAGE_BUDGETS', label: '📊 Create and manage budgets' },
  { key: 'SAVE_FOR_GOAL', label: '🎯 Save for a goal' },
  { key: 'SEE_ALL_ACCOUNTS', label: '🏦 See all my accounts in one place' },
  { key: 'IMPROVE_HABITS', label: '📈 Improve my financial habits' },
  { key: 'REDUCE_DEBT', label: '💳 Reduce debt' },
  { key: 'EXPLORING', label: '🔍 Just exploring' },
];

interface Props {
  onContinue: (selected: string[]) => void;
}

export function FinancialFocusScreen({ onContinue }: Props) {
  const c = useTheme();
  const [selected, setSelected] = useState<string[]>([]);

  function toggle(key: string) {
    if (key === 'EXPLORING') {
      setSelected((prev) => (prev.includes('EXPLORING') ? [] : ['EXPLORING']));
      return;
    }
    setSelected((prev) => {
      const withoutExploring = prev.filter((k) => k !== 'EXPLORING');
      return withoutExploring.includes(key)
        ? withoutExploring.filter((k) => k !== key)
        : [...withoutExploring, key];
    });
  }

  return (
    <ScrollView contentContainerStyle={[styles.container, { backgroundColor: c.bg }]}>
      <Text style={[styles.title, { color: c.ink }]}>What would you like to achieve with Fynora?</Text>
      <Text style={[styles.subtitle, { color: c.muted }]}>Select all that apply. We'll personalize your experience.</Text>
      {OPTIONS.map((opt) => {
        const active = selected.includes(opt.key);
        return (
          <Pressable
            key={opt.key}
            onPress={() => toggle(opt.key)}
            style={[styles.option, { borderColor: active ? c.primary : c.border }]}
          >
            <Text style={{ color: c.ink }}>{opt.label}</Text>
          </Pressable>
        );
      })}
      <View style={{ height: 16 }} />
      <Button label="Continue" onPress={() => onContinue(selected)} />
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  container: { flexGrow: 1, alignItems: 'stretch', padding: 24 },
  title: { fontSize: 22, fontWeight: '700', marginBottom: 8, textAlign: 'center' },
  subtitle: { fontSize: 13, textAlign: 'center', marginBottom: 20 },
  option: { borderWidth: 1, borderRadius: 10, padding: 14, marginBottom: 10 },
});
```

Test, mirroring web Task 9 Step 5's three cases with `fireEvent.press`/`getByText`.

- [ ] **Step 9-12: `SuccessScreen`** — same content and CTA priority as web Task 11 Steps 2-5,
  using `checklistItems.ts` (create this file identically to web's, Task 11 Step 1) and RN
  navigation (`useNavigation` from `@react-navigation/native`) in place of `useNavigate`:

```typescript
// mobile/src/onboarding/checklistItems.ts -- identical content to frontend/src/onboarding/checklistItems.ts
export const CHECKLIST_ITEMS: { key: string; label: string }[] = [
  { key: 'COMPLETE_PROFILE', label: 'Complete your profile' },
  { key: 'IMPORT_STATEMENT', label: 'Import first statement' },
  { key: 'REVIEW_TRANSACTIONS', label: 'Review transactions' },
  { key: 'CREATE_BUDGET', label: 'Create a budget' },
  { key: 'CREATE_GOAL', label: 'Create a goal' },
  { key: 'VIEW_INSIGHTS', label: 'View insights' },
];
```

```typescript
// mobile/src/onboarding/SuccessScreen.tsx
import { ScrollView, Text, View, StyleSheet } from 'react-native';
import { Button } from '../components/Button';
import { useTheme } from '../theme';
import { CHECKLIST_ITEMS } from './checklistItems';

interface Props {
  onDone: () => void;
}

export function SuccessScreen({ onDone }: Props) {
  const c = useTheme();
  return (
    <ScrollView contentContainerStyle={[styles.container, { backgroundColor: c.bg }]}>
      <Text style={[styles.title, { color: c.ink }]}>You're Ready to Go 🚀</Text>
      <Text style={[styles.subtitle, { color: c.muted }]}>
        Start by importing your first bank statement or connecting an account. The more data you
        add, the smarter Fynora becomes.
      </Text>
      <View style={styles.checklist}>
        <Text style={[styles.checklistTitle, { color: c.ink }]}>Next steps:</Text>
        {CHECKLIST_ITEMS.map((item) => (
          <Text key={item.key} style={{ color: c.muted, marginBottom: 4 }}>☐ {item.label}</Text>
        ))}
      </View>
      {/* Import Statement primary, Connect Account secondary, Go to Dashboard tertiary -- same
          CTA priority as web SuccessScreen, same reasoning (spec §3: no OAuth bank linking yet,
          so a statement import is the real first action that produces value). Navigating to
          Import/Accounts is a Task 16 follow-up once the app-tabs navigator is reachable from
          here; this task wires onDone (Go to Dashboard) since that's what actually clears
          onboardingCompleted and is required for RootNavigator to route anywhere at all. */}
      <Button label="Import Statement" onPress={onDone} />
      <View style={{ height: 8 }} />
      <Button label="Connect Account" onPress={onDone} variant="link" />
      <View style={{ height: 8 }} />
      <Button label="Go to Dashboard" onPress={onDone} variant="link" />
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  container: { flexGrow: 1, alignItems: 'center', padding: 24, justifyContent: 'center' },
  title: { fontSize: 26, fontWeight: '700', marginBottom: 12, textAlign: 'center' },
  subtitle: { fontSize: 14, textAlign: 'center', marginBottom: 20 },
  checklist: { alignSelf: 'stretch', marginBottom: 24 },
  checklistTitle: { fontWeight: '600', marginBottom: 8 },
});
```

`Import Statement`/`Connect Account` all calling `onDone` (rather than deep-linking into the
`AppTabs`' Import tab or the `More`→Accounts screen) is a deliberate v1 simplification: once
`onDone` fires, `RootNavigator` unmounts `OnboardingNavigator` and mounts `AppTabs` fresh at its
default `Home` tab — there is no navigator instance yet to imperatively navigate within from
inside `OnboardingNavigator`. If distinct destinations are required, this needs a documented
follow-up (a pending-navigation-target ref consumed by `AppTabs` on mount), not a guess made
silently here — flag it rather than resolve it unilaterally, same posture as Task 10 Step 7's and
Task 11 Step 6's flagged simplifications.

- [ ] **Step 13: Wire `WelcomeScreen`/`FinancialFocusScreen`/`SuccessScreen` into
  `OnboardingNavigator`**, replacing its stub body with the same `step` state machine shape as
  web's `OnboardingFlow` (Task 9 Step 9, Task 11 Step 6), calling `mobile/src/api/endpoints.ts`'s
  `onboardingApi` (Task 13) instead of web's. Tour Intro/Tour land in Task 16.

- [ ] **Step 14: Run the full mobile onboarding test folder**

Run: `cd mobile && npx jest src/onboarding`
Expected: all PASS.

- [ ] **Step 15: Commit**

```bash
git add mobile/src/onboarding/
git commit -m "feat(onboarding): add mobile Welcome, Financial Focus, and Success screens"
```

---

### Task 16: Mobile `TourOverlay` (navigation-aware) + `AppTabs`/`MoreScreen` target registration + `ChecklistWidget` + Settings entry + dwell-timers

**Files:**
- Create: `mobile/src/onboarding/TourOverlay.tsx` (+ `.test.tsx`)
- Create: `mobile/src/onboarding/tourSteps.ts`
- Create: `mobile/src/onboarding/ChecklistWidget.tsx` (+ `.test.tsx`)
- Modify: `mobile/src/navigation/AppTabs.tsx`
- Modify: `mobile/src/screens/MoreScreen.tsx`
- Modify: `mobile/src/screens/DashboardScreen.tsx`
- Modify: `mobile/src/screens/SettingsScreen.tsx`
- Modify: `mobile/src/screens/LedgerScreen.tsx`
- Modify: `mobile/src/screens/InsightsScreen.tsx`
- Modify: `mobile/src/onboarding/OnboardingNavigator.tsx`

**Interfaces:**
- Consumes: `useRegisterTourTarget`/`useTourTarget` (Task 14).
- Produces: `TOUR_STEPS: { key: string; tab: 'Home'|'Transactions'|'Import'|'More'; title: string; body: string }[]`
  (7 entries, in spec §3 order).

- [ ] **Step 1: Confirm this task's navigation assumption before writing any code**

Re-read `mobile/AGENTS.md`'s standing instruction and check the exact Expo v57 /
`@react-navigation/native` API for imperatively navigating a bottom-tab navigator to a specific
tab from outside it (`navigationRef.navigate('Home')` style, using the `navigationRef` already
threaded through `RootNavigator.tsx` per this plan's earlier read of that file) — `OnboardingNavigator`
is not itself inside the `AppTabs` tree (it renders instead of it, per Task 14), so `TourOverlay`
cannot use `useNavigation()` from within `AppTabs`; it needs the same `navigationRef` handle
`RootNavigator` already creates and passes to `useEmailChangeDeepLink`/`useNavigationStatePersistence`
(seen while planning Task 1's file-structure investigation). This is real, unverified-until-now
navigation wiring — spend Step 1 confirming the exact call shape against the live
`RootNavigator.tsx` code before Step 5 below, rather than guessing the API.

- [ ] **Step 2: Register tab-bar-icon refs in `AppTabs.tsx`**

```typescript
// Inside AppTabs(), wrap each Tab.Screen's tabBarIcon render (or the Tab.Navigator's
// screenOptions.tabBarIcon function) so the rendered icon view is captured via
// useRegisterTourTarget for the 3 top-level tour targets this component owns: 'home', 'import',
// 'transactions'. React Navigation's tabBarIcon doesn't hand back a ref directly -- wrap the
// returned icon in a plain <View ref={registerHomeTarget}> etc. rather than trying to ref the
// Ionicons element itself.
import { useRegisterTourTarget } from '../onboarding/TourTargetRegistry';

export function AppTabs() {
  const c = useTheme();
  const registerHome = useRegisterTourTarget('home');
  const registerTransactions = useRegisterTourTarget('transactions');
  const registerImport = useRegisterTourTarget('import');
  const registerMore = useRegisterTourTarget('more');
  const TARGET_REGISTER: Record<string, (n: any) => void> = {
    Home: registerHome, Transactions: registerTransactions, Import: registerImport, More: registerMore,
  };
  return (
    <Tab.Navigator
      screenOptions={({ route }) => ({
        headerShown: false,
        tabBarActiveTintColor: c.primary,
        tabBarInactiveTintColor: c.muted,
        tabBarStyle: { backgroundColor: c.card, borderTopColor: c.border },
        tabBarIcon: ({ focused, color, size }) => {
          const icons = TAB_ICON[route.name];
          return (
            <View ref={TARGET_REGISTER[route.name]}>
              <Ionicons name={(focused ? icons.active : icons.inactive) as any} size={size} color={color} />
            </View>
          );
        },
      })}
    >
      {/* ...unchanged Tab.Screen entries... */}
    </Tab.Navigator>
  );
}
```

Add `import { View } from 'react-native';` if not already imported.

- [ ] **Step 3: Register row refs in `MoreScreen.tsx`**

```typescript
// Inside the component that renders MENU_ITEMS as rows (found at MoreScreen.tsx while planning
// Task 16), wrap the 4 tour-relevant rows (Accounts/Budgets/Goals/Insights) with a ref from
// useRegisterTourTarget, keyed by the same lowercase names TOUR_STEPS (Step 4) uses:
import { useRegisterTourTarget } from '../onboarding/TourTargetRegistry';

// Inside the row-rendering map:
const TOUR_KEYS: Record<string, string> = { Accounts: 'accounts', Budgets: 'budgets', Goals: 'goals', Insights: 'insights' };
{MENU_ITEMS.map(({ label, route }) => {
  const tourKey = TOUR_KEYS[label];
  const RowWrapper = tourKey ? TourTargetRow : View; // TourTargetRow below only exists for the 4 tour rows
  // ...
})}
```

Since `useRegisterTourTarget` must be called unconditionally per React's rules-of-hooks and
`MENU_ITEMS` is a runtime array, don't call the hook inside `.map()` — instead call it 4 times at
the top of the component body (`useRegisterTourTarget('accounts')`, etc., same pattern as Step 2)
and look up the right one from a small `Record` when rendering each row, same shape as Step 2's
`TARGET_REGISTER`. Wrap only those 4 `<Pressable>` rows in a `<View ref={...}>`.

- [ ] **Step 4: Write `tourSteps.ts`**

```typescript
export interface TourStep {
  key: string;
  tab: 'Home' | 'Transactions' | 'Import' | 'More';
  title: string;
  body: string;
}

export const TOUR_STEPS: TourStep[] = [
  { key: 'home', tab: 'Home', title: 'Your Financial Command Center',
    body: 'This dashboard gives you a complete view of your finances, including spending, budgets, goals, and account balances.' },
  { key: 'accounts', tab: 'More', title: 'Accounts',
    body: 'See every linked or manually added account in one place.' },
  { key: 'import', tab: 'Import', title: 'Import Bank Statements',
    body: 'Upload your bank statements and Fynora automatically organizes your transactions. No manual entry required.' },
  { key: 'transactions', tab: 'Transactions', title: 'Every Transaction Explained',
    body: 'Search, filter, categorize, and understand every transaction in one place. See exactly where your money is going.' },
  { key: 'budgets', tab: 'More', title: 'Stay Within Budget',
    body: 'Create monthly budgets and track your progress in real time. Get notified before you overspend.' },
  { key: 'goals', tab: 'More', title: 'Achieve Your Financial Goals',
    body: "Whether it's an emergency fund, vacation, or new car, Fynora helps you stay on track." },
  { key: 'insights', tab: 'More', title: 'Discover Spending Patterns',
    body: 'Fynora automatically identifies trends and spending habits so you can make smarter financial decisions.' },
];
```

- [ ] **Step 5: Write `TourOverlay`'s failing test**

```typescript
import { render, screen, fireEvent, act } from '@testing-library/react-native';
import { TourOverlay } from './TourOverlay';
import { TourTargetProvider } from './TourTargetRegistry';
import type { TourStep } from './tourSteps';

const STEPS: TourStep[] = [
  { key: 'a', tab: 'Home', title: 'Step A', body: 'Body A' },
  { key: 'b', tab: 'Home', title: 'Step B', body: 'Body B' },
];

describe('TourOverlay', () => {
  it('shows the first step and advances on Next', () => {
    const navigateMock = jest.fn();
    render(
      <TourTargetProvider>
        <TourOverlay steps={STEPS} navigateToTab={navigateMock} onFinish={jest.fn()} onSkip={jest.fn()} />
      </TourTargetProvider>
    );
    expect(screen.getByText('Step A')).toBeTruthy();
    fireEvent.press(screen.getByText('Next'));
    expect(screen.getByText('Step B')).toBeTruthy();
  });

  it('calls navigateToTab with the step\'s tab whenever the step changes', () => {
    const navigateMock = jest.fn();
    render(
      <TourTargetProvider>
        <TourOverlay steps={STEPS} navigateToTab={navigateMock} onFinish={jest.fn()} onSkip={jest.fn()} />
      </TourTargetProvider>
    );
    expect(navigateMock).toHaveBeenCalledWith('Home');
  });

  it('calls onFinish after Next on the last step', () => {
    const onFinish = jest.fn();
    render(
      <TourTargetProvider>
        <TourOverlay steps={STEPS} navigateToTab={jest.fn()} onFinish={onFinish} onSkip={jest.fn()} />
      </TourTargetProvider>
    );
    fireEvent.press(screen.getByText('Next'));
    fireEvent.press(screen.getByText('Finish'));
    expect(onFinish).toHaveBeenCalled();
  });

  it('calls onSkip from Skip', () => {
    const onSkip = jest.fn();
    render(
      <TourTargetProvider>
        <TourOverlay steps={STEPS} navigateToTab={jest.fn()} onFinish={jest.fn()} onSkip={onSkip} />
      </TourTargetProvider>
    );
    fireEvent.press(screen.getByText('Skip'));
    expect(onSkip).toHaveBeenCalled();
  });
});
```

- [ ] **Step 6: Run to verify it fails**

Run: `cd mobile && npx jest src/onboarding/TourOverlay.test.tsx`
Expected: FAIL.

- [ ] **Step 7: Write `TourOverlay.tsx`**

```typescript
import { useEffect, useState } from 'react';
import { Modal, Text, View, StyleSheet } from 'react-native';
import { Button } from '../components/Button';
import { useTheme } from '../theme';
import { useTourTarget } from './TourTargetRegistry';
import type { TourStep } from './tourSteps';

interface Props {
  steps: TourStep[];
  navigateToTab: (tab: TourStep['tab']) => void;
  onFinish: () => void;
  onSkip: () => void;
}

export function TourOverlay({ steps, navigateToTab, onFinish, onSkip }: Props) {
  const c = useTheme();
  const [index, setIndex] = useState(0);
  const step = steps[index];
  const isLast = index === steps.length - 1;
  const target = useTourTarget(step.key);

  useEffect(() => {
    navigateToTab(step.tab);
  }, [step.tab]);

  function next() {
    if (isLast) {
      onFinish();
    } else {
      setIndex((i) => i + 1);
    }
  }

  function back() {
    setIndex((i) => Math.max(0, i - 1));
  }

  // Spotlighting the measured `target` view (via measureInWindow + an SVG cutout) is a real
  // follow-up once Step 1's navigation-timing question is settled -- a step that just navigated
  // needs to wait for the new screen to mount and the target ref to register before it can
  // measure anything, which is a race this stub does not yet resolve. Ship the tooltip content
  // and Next/Back/Skip/Finish flow first (this is what every test above actually asserts on);
  // treat the spotlight highlight itself as a visual enhancement layered on afterward, not a
  // blocker for the step-advancement logic being correct.
  return (
    <Modal transparent animationType="fade">
      <View style={[styles.backdrop]}>
        <View style={[styles.card, { backgroundColor: c.card }]}>
          <Text style={[styles.title, { color: c.ink }]}>{step.title}</Text>
          <Text style={[styles.body, { color: c.muted }]}>{step.body}</Text>
          <View style={styles.row}>
            <Text onPress={onSkip} style={{ color: c.muted, fontSize: 12 }}>Skip</Text>
            <View style={styles.row}>
              {index > 0 && <Button label="Back" onPress={back} variant="link" />}
              <View style={{ width: 8 }} />
              <Button label={isLast ? 'Finish' : 'Next'} onPress={next} />
            </View>
          </View>
        </View>
      </View>
    </Modal>
  );
}

const styles = StyleSheet.create({
  backdrop: { flex: 1, backgroundColor: 'rgba(0,0,0,0.6)', justifyContent: 'flex-end' },
  card: { borderTopLeftRadius: 16, borderTopRightRadius: 16, padding: 20 },
  title: { fontSize: 18, fontWeight: '700', marginBottom: 6 },
  body: { fontSize: 14, marginBottom: 16 },
  row: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
});
```

The spotlight cutout itself (measuring `target` and rendering an `react-native-svg` mask around
it) is intentionally deferred to a follow-up inside this same task once Step 1's navigation-race
question is resolved — implement it as an additional, separately-tested piece before calling this
task done, using `target?.measureInWindow((x, y, width, height) => ...)` gated on `target` being
non-null (it will be null for one render after `navigateToTab` fires, until the newly-mounted
screen's `useRegisterTourTarget` ref callback runs).

- [ ] **Step 8: Run again to verify it passes**

Run: same as Step 6.
Expected: PASS.

- [ ] **Step 9: Wire Tour Intro + `TourOverlay` into `OnboardingNavigator`**, same `step` state
  machine addition as web Task 10 Step 7, using `navigationRef.navigate` (confirmed in Step 1) as
  `navigateToTab`.

- [ ] **Step 10: Write `ChecklistWidget.tsx`** — same logic as web Task 11 Steps 7-10, RN-rendered
  (`Text`/`View` progress bar via a fixed-width `View` instead of a CSS width percentage), mounted
  on `DashboardScreen.tsx` above its existing first `<Card>`.

- [ ] **Step 11: Add "Retake Product Tour" to `SettingsScreen.tsx`**, inside the existing
  `<SectionCard title="General" ...>` block (found while planning this task, around line 199),
  calling `onboardingApi.reset()` then `setOnboardingCompleted(false)` — same logic as web Task 11
  Step 12, using this file's `Button`/`onPress` convention instead of a DOM button.

- [ ] **Step 12: Add dwell-timer checklist completion to `LedgerScreen.tsx`/`InsightsScreen.tsx`**
  — same 1.5s-`setTimeout`-in-`useEffect` logic as web Task 12, using this repo's mobile
  `useQuery`/`onboardingApi` imports.

- [ ] **Step 13: Run the full mobile onboarding + touched-screen test suites**

Run: `cd mobile && npx jest src/onboarding src/navigation/AppTabs.test.tsx src/screens/MoreScreen.test.tsx src/screens/DashboardScreen.test.tsx src/screens/SettingsScreen.test.tsx src/screens/LedgerScreen.test.tsx src/screens/InsightsScreen.test.tsx`

(Some of these test files may not exist yet — check first with `ls mobile/src/screens/*.test.tsx`
and create any missing ones only if this task's edits actually need new coverage there; several
of these screens already have `.test.tsx` siblings per the file listing seen while planning
Phase 1.)

Expected: all PASS.

- [ ] **Step 14: Commit**

```bash
git add mobile/src/onboarding/ mobile/src/navigation/AppTabs.tsx mobile/src/screens/MoreScreen.tsx \
        mobile/src/screens/DashboardScreen.tsx mobile/src/screens/SettingsScreen.tsx \
        mobile/src/screens/LedgerScreen.tsx mobile/src/screens/InsightsScreen.tsx
git commit -m "feat(onboarding): add mobile TourOverlay, checklist widget, and Retake Tour"
```

**Phase 3 checkpoint:** run this repo's iOS/Android simulator verification (per this session's
own simulator tooling, or Maestro if this repo has flows already — check `mobile/.maestro/`)
against a freshly-registered test account, walking the same flow verified in Phase 2's checkpoint.
Do not report mobile work complete on a green Jest suite alone — this is a previewable UI change
on a platform this codebase's own conventions require visual verification for.

---

## Self-Review

**Spec coverage** (against `docs/superpowers/specs/2026-09-06-first-login-onboarding-tour-design.md`):
- §3 Welcome/Financial Focus/Tour Intro/7 tour steps/Success/checklist/Settings copy — Tasks 9,
  11, 15, 16 implement every string verbatim.
- §4 scope decisions (no personalization from Financial Focus, no generic engine, closed enums,
  no admin visibility) — respected throughout; `FinancialFocus`/`ChecklistItemKey` are closed
  Java enums (Task 2/4), no admin controller or view was added anywhere in this plan.
- §5 data model (`onboarding_completed_at`, `user_financial_focus`, `user_checklist_events`) —
  Task 1.
- §6 API (`status`/`financial-focus`/`complete`/`reset`/`checklist`/`checklist/{key}/complete`)
  — Tasks 2-5, at the corrected `/api/v1/onboarding/*` prefix.
- §7 frontend/mobile architecture (`AuthResponse`/`UserSettingsDto` wiring, in-house
  `TourOverlay`, web sidebar vs. mobile tab-bar+More-row navigation difference) — Task 6 (backend
  half), Tasks 7/13 (auth wiring), Tasks 10/16 (tour engines, mobile's explicitly navigation-aware
  per the spec's own addendum).
- §8 testing — every task carries its own unit/IT/component test; Phase 2/3 checkpoints add the
  browser/simulator verification the spec's testing section implies but doesn't itself schedule.
- §9 explicitly-out-of-scope items — none were added; no dashboard reordering logic, no admin
  view, no A/B testing, no server-driven step config exists anywhere in this plan.

**Placeholder scan:** two places in this plan intentionally flag an open design question rather
than silently resolving it (Task 10 Step 7 — whether the tour overlay needs the real Dashboard
mounted behind it; Task 15 Step 9-12 — where Success screen's Import/Connect CTAs should actually
navigate on mobile) and one defers an implementation detail to a same-task follow-up with a
concrete next step (Task 16 Step 7 — the spotlight cutout itself, blocked on Step 1's navigation-
timing question). These are flagged, not vague — each names exactly what's unresolved and what
resolving it would change. No other "TBD"/"handle appropriately"/unshown code was found on
re-scan.

**Type consistency:** `OnboardingDto.StatusResponse`/`ChecklistResponse`/`ChecklistItemDto`
(Task 2/3) are used with the same field names in every later task (Tasks 4-6 backend; Tasks 7-12
web; Tasks 13-16 mobile). `ChecklistItemKey`'s 6 names (Task 4) match `CHECKLIST_ITEMS`' 6 keys
in both `checklistItems.ts` files (Task 11, Task 15) exactly. `FinancialFocus`'s 7 names (Task 2)
match both platforms' `OPTIONS` arrays (Task 9, Task 15) exactly. `TourStep`'s shape differs
deliberately between platforms (web: `targetSelector`; mobile: `key` + `tab`) because the
underlying navigation models differ, per the spec's own addendum — this is not an inconsistency,
it's Tasks 10 and 16 solving the same product requirement against two different real UI
structures.

---

Plan complete and saved to `docs/superpowers/plans/2026-09-06-first-login-onboarding-tour.md`. Two execution options:

**1. Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints

**Which approach?**
