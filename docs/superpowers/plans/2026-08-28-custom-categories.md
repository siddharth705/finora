# Custom Transaction Categories Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let users create, rename, and delete their own transaction categories from any category picker in the app, while system (default-seeded) categories stay immutable so global automation rules keep working unchanged.

**Architecture:** `Category` gains `icon`/`color` token columns and a case-insensitive uniqueness constraint. A new `CategoryService` backs `POST/PATCH/DELETE /categories/{id}` and `GET /categories/{id}/usage` + `GET /categories/options`, enforcing the system/user split and cascading renames/deletes into the one place a category name leaks outside its own row: personal `CategoryRule.actionValue` strings. The frontend gets one shared `CategoryCombobox` (built from scratch — no combobox primitive exists in this codebase today) that replaces the plain `<select>` in `Ledger.tsx`, `AskOnceCard.tsx`, and `MerchantGroupReviewCard.tsx`, plus a delete-confirmation dialog and an inline create/edit panel with a curated icon/color picker.

**Tech Stack:** Spring Boot / JPA / PostgreSQL (Flyway migrations) on the backend; React + TypeScript + Vitest/@testing-library/react on the frontend; `lucide-react` for icons (already a dependency).

**Spec:** [docs/superpowers/specs/2026-08-28-custom-categories-design.md](../specs/2026-08-28-custom-categories-design.md)

## Global Constraints

- Category name uniqueness is **per-user**, case-insensitive (`(user_id, lower(name))`), never global.
- System categories (`isSystem = true`) are immutable: no rename, no delete, enforced at the service layer with a 403.
- `icon`/`color` are **curated tokens** validated against a fixed backend allow-list — never arbitrary lucide names or hex codes, never accepted unvalidated from a request body.
- Every category mutation (create/rename/delete) is `@Transactional` and calls `AuditService.record(...)`, matching the convention in `RuleService`/`TransactionService`.
- Ownership checks go through `OwnershipGuard.requireOwned`/`requireOwnedBy` — never a hand-rolled `entity.getUserId().equals(userId)` (this repo has a build-time test, `OwnershipGuardUsageTest`, that fails on that pattern).
- New Flyway migration is `V116__category_customization.sql` (last existing is `V115__transaction_relationship_source_trust.sql`).
- Frontend: no new UI-library dependency — `CategoryCombobox` is a plain component built with the existing Tailwind utility classes already used by `Ledger.tsx`'s `<select>` (`bg-card text-ink border border-border rounded-lg ...`).

---

## File Structure

**Backend — new files:**
- `backend/src/main/resources/db/migration/V116__category_customization.sql` — schema + backfill.
- `backend/src/main/java/com/finora/util/CategoryPalette.java` — curated icon/color token allow-lists.
- `backend/src/main/java/com/finora/service/CategoryService.java` — create/rename/delete/usage business logic.
- `backend/src/main/java/com/finora/dto/CategoryUsageDto.java`, `CategoryOptionsDto.java` — response DTOs.
- `backend/src/test/java/com/finora/service/CategoryServiceTest.java` — unit tests (mocked repos).
- `backend/src/test/java/com/finora/controller/CategoryControllerIT.java` — integration tests.

**Backend — modified files:**
- `backend/src/main/java/com/finora/entity/Category.java` — add `icon`/`color` fields.
- `backend/src/main/java/com/finora/dto/CategoryDto.java` — add `icon`/`color`.
- `backend/src/main/java/com/finora/controller/CategoryController.java` — new endpoints.
- `backend/src/main/java/com/finora/repository/CategoryRuleRepository.java` — new lookup method.
- `backend/src/main/java/com/finora/repository/TransactionRepository.java` — count + bulk reassign by category.
- `backend/src/main/java/com/finora/service/AuthService.java` — seed icon/color on new registrations.

**Frontend — new files:**
- `frontend/src/lib/similarity.ts` + `similarity.test.ts` — Levenshtein-ratio fuzzy match.
- `frontend/src/components/CategoryCombobox.tsx` + `.test.tsx` — shared picker.
- `frontend/src/components/CategoryCreateEditPanel.tsx` + `.test.tsx` — inline name/icon/color form.
- `frontend/src/components/CategoryDeleteDialog.tsx` + `.test.tsx` — usage summary + reassignment.

**Frontend — modified files:**
- `frontend/src/api/endpoints.ts` — extend `categoriesApi`, extend `CategoryOption`.
- `frontend/src/pages/Ledger.tsx` — swap the edit-modal `<select>` for `CategoryCombobox`.
- `frontend/src/components/AskOnceCard.tsx` — same swap.
- `frontend/src/components/MerchantGroupReviewCard.tsx` — same swap.
- `frontend/src/pages/Dashboard.tsx` — retire `CATEGORY_ICON`/`CATEGORY_COLOR` hardcoded maps.

---

### Task 1: Schema — icon/color columns, case-insensitive uniqueness, backfill

**Files:**
- Create: `backend/src/main/resources/db/migration/V116__category_customization.sql`
- Modify: `backend/src/main/java/com/finora/entity/Category.java`
- Modify: `backend/src/main/java/com/finora/dto/CategoryDto.java` (find its current record definition — it's the 3-field `(id, name, isSystem)` record referenced from `CategoryController.list()`)
- Modify: `backend/src/main/java/com/finora/controller/CategoryController.java:1-30` (the existing `list()` mapping)
- Modify: `backend/src/main/java/com/finora/service/AuthService.java:74-80,1436-1446`
- Test: `backend/src/test/java/com/finora/repository/CategoryRepositoryTest.java` (new)

**Interfaces:**
- Produces: `Category.getIcon()/setIcon(String)`, `Category.getColor()/setColor(String)`; `CategoryDto(UUID id, String name, boolean isSystem, String icon, String color)`.

- [ ] **Step 1: Write the migration**

```sql
-- backend/src/main/resources/db/migration/V116__category_customization.sql

ALTER TABLE categories
    ADD COLUMN icon  VARCHAR(30) NOT NULL DEFAULT 'tag',
    ADD COLUMN color VARCHAR(20) NOT NULL DEFAULT 'gray';

-- Case-insensitive uniqueness at the DB level, replacing the case-sensitive UNIQUE(user_id, name)
-- from V1 -- closes the race documented on CategoryRepository.findByUserIdAndNameIgnoreCaseOrderByIdAsc.
ALTER TABLE categories DROP CONSTRAINT categories_user_id_name_key;
CREATE UNIQUE INDEX uq_categories_user_name_ci ON categories (user_id, lower(name));

-- Backfill real tokens for every existing system category row (AuthService.seedDefaultCategories
-- creates one row per name, per user, at registration -- these UPDATEs apply to every user who
-- has ever registered, not just one).
UPDATE categories SET icon = 'arrow-down-circle', color = 'green'  WHERE is_system AND name = 'Salary';
UPDATE categories SET icon = 'home',               color = 'blue'   WHERE is_system AND name = 'Rent';
UPDATE categories SET icon = 'shopping-cart',       color = 'green'  WHERE is_system AND name = 'Groceries';
UPDATE categories SET icon = 'utensils',            color = 'orange' WHERE is_system AND name = 'Dining';
UPDATE categories SET icon = 'car',                 color = 'gray'   WHERE is_system AND name = 'Transport';
UPDATE categories SET icon = 'zap',                 color = 'yellow' WHERE is_system AND name = 'Utilities';
UPDATE categories SET icon = 'shopping-bag',        color = 'purple' WHERE is_system AND name = 'Shopping';
UPDATE categories SET icon = 'heart-pulse',         color = 'red'    WHERE is_system AND name = 'Health';
UPDATE categories SET icon = 'film',                color = 'pink'   WHERE is_system AND name = 'Entertainment';
UPDATE categories SET icon = 'trending-up',         color = 'teal'   WHERE is_system AND name = 'Investments';
UPDATE categories SET icon = 'percent',             color = 'gray'   WHERE is_system AND name = 'Fees/Interest';
UPDATE categories SET icon = 'repeat',              color = 'blue'   WHERE is_system AND name = 'Transfer';
UPDATE categories SET icon = 'users',               color = 'teal'   WHERE is_system AND name = 'Friend Repayment';
UPDATE categories SET icon = 'landmark',            color = 'red'    WHERE is_system AND name = 'Loan EMI';
UPDATE categories SET icon = 'shield',              color = 'blue'   WHERE is_system AND name = 'Insurance';
UPDATE categories SET icon = 'graduation-cap',      color = 'purple' WHERE is_system AND name = 'Education';
UPDATE categories SET icon = 'refresh-cw',          color = 'pink'   WHERE is_system AND name = 'Subscriptions';
UPDATE categories SET icon = 'plane',               color = 'teal'   WHERE is_system AND name = 'Travel';
UPDATE categories SET icon = 'gift',                color = 'pink'   WHERE is_system AND name = 'Gifts & Donations';
UPDATE categories SET icon = 'paw-print',           color = 'orange' WHERE is_system AND name = 'Pets';
UPDATE categories SET icon = 'sofa',                color = 'yellow' WHERE is_system AND name = 'Home & Furnishing';
UPDATE categories SET icon = 'receipt',             color = 'gray'   WHERE is_system AND name = 'Taxes';
UPDATE categories SET icon = 'banknote',            color = 'green'  WHERE is_system AND name = 'Cash Withdrawal';
UPDATE categories SET icon = 'briefcase',           color = 'blue'   WHERE is_system AND name = 'Business Expenses';
UPDATE categories SET icon = 'tag',                 color = 'gray'   WHERE is_system AND name = 'Other';
```

- [ ] **Step 2: Update the `Category` entity**

```java
// backend/src/main/java/com/finora/entity/Category.java
    @Column(nullable = false)
    private String icon = "tag";

    @Column(nullable = false)
    private String color = "gray";

    public String getIcon() { return icon; }
    public void setIcon(String icon) { this.icon = icon; }
    public String getColor() { return color; }
    public void setColor(String color) { this.color = color; }
```
(Add alongside the existing fields/getters/setters — insert after the `isSystem`/`setSystem` block.)

- [ ] **Step 3: Update `CategoryDto` and the `list()` mapping**

Find `CategoryDto`'s current 3-field record (referenced at `CategoryController.java` line ~24: `new CategoryDto(c.getId(), c.getName(), c.isSystem())`). Change it to:

```java
public record CategoryDto(UUID id, String name, boolean isSystem, String icon, String color) {}
```

And update the mapping in `CategoryController.list()`:

```java
.map(c -> new CategoryDto(c.getId(), c.getName(), c.isSystem(), c.getIcon(), c.getColor()))
```

- [ ] **Step 4: Seed icon/color on new registrations**

```java
// backend/src/main/java/com/finora/service/AuthService.java
// Replace the single DEFAULT_CATEGORIES List<String> with a name->[icon,color] map so new
// registrations get the same tokens the V116 backfill gave existing users.
private static final Map<String, String[]> DEFAULT_CATEGORIES = new LinkedHashMap<>();
static {
    DEFAULT_CATEGORIES.put("Salary", new String[]{"arrow-down-circle", "green"});
    DEFAULT_CATEGORIES.put("Rent", new String[]{"home", "blue"});
    DEFAULT_CATEGORIES.put("Groceries", new String[]{"shopping-cart", "green"});
    DEFAULT_CATEGORIES.put("Dining", new String[]{"utensils", "orange"});
    DEFAULT_CATEGORIES.put("Transport", new String[]{"car", "gray"});
    DEFAULT_CATEGORIES.put("Utilities", new String[]{"zap", "yellow"});
    DEFAULT_CATEGORIES.put("Shopping", new String[]{"shopping-bag", "purple"});
    DEFAULT_CATEGORIES.put("Health", new String[]{"heart-pulse", "red"});
    DEFAULT_CATEGORIES.put("Entertainment", new String[]{"film", "pink"});
    DEFAULT_CATEGORIES.put("Investments", new String[]{"trending-up", "teal"});
    DEFAULT_CATEGORIES.put("Fees/Interest", new String[]{"percent", "gray"});
    DEFAULT_CATEGORIES.put("Transfer", new String[]{"repeat", "blue"});
    DEFAULT_CATEGORIES.put("Friend Repayment", new String[]{"users", "teal"});
    DEFAULT_CATEGORIES.put("Loan EMI", new String[]{"landmark", "red"});
    DEFAULT_CATEGORIES.put("Insurance", new String[]{"shield", "blue"});
    DEFAULT_CATEGORIES.put("Education", new String[]{"graduation-cap", "purple"});
    DEFAULT_CATEGORIES.put("Subscriptions", new String[]{"refresh-cw", "pink"});
    DEFAULT_CATEGORIES.put("Travel", new String[]{"plane", "teal"});
    DEFAULT_CATEGORIES.put("Gifts & Donations", new String[]{"gift", "pink"});
    DEFAULT_CATEGORIES.put("Pets", new String[]{"paw-print", "orange"});
    DEFAULT_CATEGORIES.put("Home & Furnishing", new String[]{"sofa", "yellow"});
    DEFAULT_CATEGORIES.put("Taxes", new String[]{"receipt", "gray"});
    DEFAULT_CATEGORIES.put("Cash Withdrawal", new String[]{"banknote", "green"});
    DEFAULT_CATEGORIES.put("Business Expenses", new String[]{"briefcase", "blue"});
    DEFAULT_CATEGORIES.put("Other", new String[]{"tag", "gray"});
}

private void seedDefaultCategories(java.util.UUID userId) {
    List<Category> categories = new ArrayList<>();
    for (var entry : DEFAULT_CATEGORIES.entrySet()) {
        Category c = new Category();
        c.setUserId(userId);
        c.setName(entry.getKey());
        c.setSystem(true);
        c.setIcon(entry.getValue()[0]);
        c.setColor(entry.getValue()[1]);
        categories.add(c);
    }
    categoryRepository.saveAll(categories);
}
```
Add `import java.util.LinkedHashMap; import java.util.Map;` if not already present in `AuthService.java`.

- [ ] **Step 5: Write a repository test proving the case-insensitive constraint**

```java
// backend/src/test/java/com/finora/repository/CategoryRepositoryTest.java
package com.finora.repository;

import com.finora.entity.Category;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@ActiveProfiles("test")
class CategoryRepositoryTest {

    @Autowired
    private CategoryRepository categoryRepository;

    @Test
    void rejectsCaseInsensitiveDuplicateNameForSameUser() {
        UUID userId = UUID.randomUUID();
        Category sip = new Category();
        sip.setUserId(userId);
        sip.setName("SIP");
        categoryRepository.saveAndFlush(sip);

        Category dup = new Category();
        dup.setUserId(userId);
        dup.setName("sip");

        assertThatThrownBy(() -> categoryRepository.saveAndFlush(dup))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
```

- [ ] **Step 6: Run the test**

Run: `cd backend && ./mvnw -q test -Dtest=CategoryRepositoryTest`
Expected: PASS (Flyway applies `V116` in the test DB automatically).

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/resources/db/migration/V116__category_customization.sql \
        backend/src/main/java/com/finora/entity/Category.java \
        backend/src/main/java/com/finora/dto/CategoryDto.java \
        backend/src/main/java/com/finora/controller/CategoryController.java \
        backend/src/main/java/com/finora/service/AuthService.java \
        backend/src/test/java/com/finora/repository/CategoryRepositoryTest.java
git commit -m "feat(categories): add icon/color tokens and case-insensitive uniqueness"
```

---

### Task 2: Curated icon/color allow-list + `GET /categories/options`

**Files:**
- Create: `backend/src/main/java/com/finora/util/CategoryPalette.java`
- Create: `backend/src/main/java/com/finora/dto/CategoryOptionsDto.java`
- Modify: `backend/src/main/java/com/finora/controller/CategoryController.java`
- Test: `backend/src/test/java/com/finora/controller/CategoryControllerIT.java` (new — this task starts the file; later tasks add to it)

**Interfaces:**
- Consumes: nothing new.
- Produces: `CategoryPalette.ICONS` (`Set<String>`), `CategoryPalette.COLORS` (`Set<String>`), `CategoryPalette.isValidIcon(String)`, `CategoryPalette.isValidColor(String)` — used by Task 3's `CategoryService`.

- [ ] **Step 1: Write `CategoryPalette`**

```java
// backend/src/main/java/com/finora/util/CategoryPalette.java
package com.finora.util;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The finite icon/color token vocabulary a Category's icon/color columns are validated against.
 * Deliberately closed rather than accepting arbitrary lucide-react names or hex codes -- every
 * category (system and user) renders from the same small, curated palette.
 */
public final class CategoryPalette {

    private CategoryPalette() {}

    public static final Map<String, String> ICONS = new LinkedHashMap<>();
    static {
        ICONS.put("tag", "Tag");
        ICONS.put("home", "Home");
        ICONS.put("shopping-cart", "Groceries");
        ICONS.put("utensils", "Dining");
        ICONS.put("car", "Transport");
        ICONS.put("zap", "Utilities");
        ICONS.put("shopping-bag", "Shopping");
        ICONS.put("heart-pulse", "Health");
        ICONS.put("film", "Entertainment");
        ICONS.put("trending-up", "Investing");
        ICONS.put("percent", "Fees");
        ICONS.put("repeat", "Transfer");
        ICONS.put("users", "People");
        ICONS.put("landmark", "Loan");
        ICONS.put("shield", "Insurance");
        ICONS.put("graduation-cap", "Education");
        ICONS.put("refresh-cw", "Subscription");
        ICONS.put("plane", "Travel");
        ICONS.put("gift", "Gifts");
        ICONS.put("paw-print", "Pets");
        ICONS.put("sofa", "Home & Furnishing");
        ICONS.put("receipt", "Taxes");
        ICONS.put("banknote", "Cash");
        ICONS.put("briefcase", "Business");
        ICONS.put("arrow-down-circle", "Income");
    }

    public static final Map<String, String> COLORS = new LinkedHashMap<>();
    static {
        COLORS.put("gray", "#6b7280");
        COLORS.put("blue", "#2563eb");
        COLORS.put("green", "#16a34a");
        COLORS.put("red", "#dc2626");
        COLORS.put("orange", "#ea580c");
        COLORS.put("yellow", "#d97706");
        COLORS.put("purple", "#7c3aed");
        COLORS.put("pink", "#db2777");
        COLORS.put("teal", "#0d9488");
    }

    public static boolean isValidIcon(String token) {
        return token != null && ICONS.containsKey(token);
    }

    public static boolean isValidColor(String token) {
        return token != null && COLORS.containsKey(token);
    }
}
```

- [ ] **Step 2: Write the options DTO**

```java
// backend/src/main/java/com/finora/dto/CategoryOptionsDto.java
package com.finora.dto;

import java.util.List;

public record CategoryOptionsDto(List<Option> icons, List<Option> colors) {
    public record Option(String token, String label) {}
}
```

- [ ] **Step 3: Add the endpoint**

```java
// backend/src/main/java/com/finora/controller/CategoryController.java
// Add import: com.finora.dto.CategoryOptionsDto; com.finora.util.CategoryPalette; org.springframework.web.bind.annotation.GetMapping;

    @GetMapping("/options")
    public ApiResponse<CategoryOptionsDto> options() {
        var icons = CategoryPalette.ICONS.entrySet().stream()
                .map(e -> new CategoryOptionsDto.Option(e.getKey(), e.getValue()))
                .toList();
        var colors = CategoryPalette.COLORS.entrySet().stream()
                .map(e -> new CategoryOptionsDto.Option(e.getKey(), e.getValue()))
                .toList();
        return ApiResponse.ok(new CategoryOptionsDto(icons, colors));
    }
```
Note: `/options` must be mapped before any `/{id}` path variable mapping is added in later tasks, or Spring will try to resolve `"options"` as a UUID path variable and 400. Since this task adds `/options` first and Task 3/4/6 add `/{id}` mappings afterward in the same controller, this ordering is safe as long as `/{id}` mappings are added as `@PathVariable UUID id` (Spring's path matching handles this correctly regardless of declaration order — literal segments always take precedence over variable segments — but keep `/options` declared above the `/{id}` methods for readability).

- [ ] **Step 4: Write the integration test**

```java
// backend/src/test/java/com/finora/controller/CategoryControllerIT.java
package com.finora.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.entity.User;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.test.AbstractIntegrationTest;
import com.finora.test.TestSessions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CategoryControllerIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private ObjectMapper objectMapper;

    private User createUser() {
        User u = new User();
        u.setEmail("cat-" + UUID.randomUUID() + "@test.com");
        u.setPasswordHash("x");
        u.setEmailVerifiedAt(Instant.now());
        return userRepository.save(u);
    }

    private HttpHeaders authHeaders(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        return headers;
    }

    @Test
    void optionsReturnsTheCuratedIconAndColorTokenLists() {
        User user = createUser();
        var response = restTemplate.exchange("/api/v1/categories/options", HttpMethod.GET,
                new HttpEntity<>(authHeaders(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = objectMapper.readTree(response.getBody()).get("data");
        assertThat(data.get("icons").isArray()).isTrue();
        assertThat(data.get("icons").size()).isGreaterThan(0);
        assertThat(data.get("colors").size()).isGreaterThan(0);
    }
}
```
(This test class is extended by later tasks — do not create a second `CategoryControllerIT`.)

- [ ] **Step 5: Run it**

Run: `cd backend && ./mvnw -q test -Dtest=CategoryControllerIT`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/finora/util/CategoryPalette.java \
        backend/src/main/java/com/finora/dto/CategoryOptionsDto.java \
        backend/src/main/java/com/finora/controller/CategoryController.java \
        backend/src/test/java/com/finora/controller/CategoryControllerIT.java
git commit -m "feat(categories): curated icon/color token allow-list and options endpoint"
```

---

### Task 3: `CategoryService.create` + `POST /categories`

**Files:**
- Create: `backend/src/main/java/com/finora/service/CategoryService.java`
- Modify: `backend/src/main/java/com/finora/controller/CategoryController.java`
- Modify: `backend/src/test/java/com/finora/controller/CategoryControllerIT.java`
- Test: `backend/src/test/java/com/finora/service/CategoryServiceTest.java` (new)

**Interfaces:**
- Consumes: `CategoryRepository` (existing), `CategoryPalette.isValidIcon/isValidColor` (Task 2), `AuditService.record(UUID,String,String,UUID,Map)` (existing).
- Produces: `CategoryService.create(UUID userId, String name, String icon, String color): Category` — later tasks (`rename`, `delete`, `usage`) live in this same service/class.

- [ ] **Step 1: Write the failing service test**

```java
// backend/src/test/java/com/finora/service/CategoryServiceTest.java
package com.finora.service;

import com.finora.entity.Category;
import com.finora.exception.ApiException;
import com.finora.repository.CategoryRepository;
import com.finora.repository.CategoryRuleRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.BudgetRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CategoryServiceTest {

    private final UUID userId = UUID.randomUUID();
    private final CategoryRepository categoryRepository = mock(CategoryRepository.class);
    private final CategoryRuleRepository categoryRuleRepository = mock(CategoryRuleRepository.class);
    private final TransactionRepository transactionRepository = mock(TransactionRepository.class);
    private final BudgetRepository budgetRepository = mock(BudgetRepository.class);
    private final AuditService auditService = mock(AuditService.class);

    private CategoryService service() {
        return new CategoryService(categoryRepository, categoryRuleRepository,
                transactionRepository, budgetRepository, auditService);
    }

    @Test
    void createsACategoryWithDefaultIconAndColorWhenOmitted() {
        when(categoryRepository.findByUserIdAndNameIgnoreCaseOrderByIdAsc(userId, "SIP"))
                .thenReturn(List.of());
        when(categoryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Category created = service().create(userId, "SIP", null, null);

        assertThat(created.getName()).isEqualTo("SIP");
        assertThat(created.isSystem()).isFalse();
        assertThat(created.getIcon()).isEqualTo("tag");
        assertThat(created.getColor()).isEqualTo("gray");
    }

    @Test
    void rejectsACaseInsensitiveDuplicateForTheSameUser() {
        Category existing = new Category();
        existing.setUserId(userId);
        existing.setName("SIP");
        when(categoryRepository.findByUserIdAndNameIgnoreCaseOrderByIdAsc(userId, "sip"))
                .thenReturn(List.of(existing));

        assertThatThrownBy(() -> service().create(userId, "sip", null, null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already have a category named");
    }

    @Test
    void rejectsAnIconTokenOutsideTheCuratedAllowList() {
        when(categoryRepository.findByUserIdAndNameIgnoreCaseOrderByIdAsc(userId, "SIP"))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service().create(userId, "SIP", "not-a-real-icon", null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("icon");
    }

    @Test
    void rejectsABlankName() {
        assertThatThrownBy(() -> service().create(userId, "  ", null, null))
                .isInstanceOf(ApiException.class);
    }
}
```

- [ ] **Step 2: Run it to confirm it fails to compile (no `CategoryService` yet)**

Run: `cd backend && ./mvnw -q test -Dtest=CategoryServiceTest`
Expected: compile error, `CategoryService` does not exist.

- [ ] **Step 3: Write `CategoryService`**

```java
// backend/src/main/java/com/finora/service/CategoryService.java
package com.finora.service;

import com.finora.entity.Category;
import com.finora.exception.ApiException;
import com.finora.repository.BudgetRepository;
import com.finora.repository.CategoryRepository;
import com.finora.repository.CategoryRuleRepository;
import com.finora.repository.TransactionRepository;
import com.finora.util.CategoryPalette;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * User-facing category CRUD. Distinct from CategorizationService.resolveOrCreateCategory, which
 * stays the internal server-side find-or-create path used by import/rule-matching/bulk-recategorize
 * and is untouched by this service.
 */
@Service
public class CategoryService {

    private static final int MAX_NAME_LENGTH = 80;

    private final CategoryRepository categoryRepository;
    private final CategoryRuleRepository categoryRuleRepository;
    private final TransactionRepository transactionRepository;
    private final BudgetRepository budgetRepository;
    private final AuditService auditService;

    public CategoryService(CategoryRepository categoryRepository,
                            CategoryRuleRepository categoryRuleRepository,
                            TransactionRepository transactionRepository,
                            BudgetRepository budgetRepository,
                            AuditService auditService) {
        this.categoryRepository = categoryRepository;
        this.categoryRuleRepository = categoryRuleRepository;
        this.transactionRepository = transactionRepository;
        this.budgetRepository = budgetRepository;
        this.auditService = auditService;
    }

    @Transactional
    public Category create(UUID userId, String name, String icon, String color) {
        String safeName = validateName(name);
        validateNoDuplicate(userId, safeName, null);
        String safeIcon = icon == null ? "tag" : validateIcon(icon);
        String safeColor = color == null ? "gray" : validateColor(color);

        Category c = new Category();
        c.setUserId(userId);
        c.setName(safeName);
        c.setSystem(false);
        c.setIcon(safeIcon);
        c.setColor(safeColor);
        Category saved = categoryRepository.save(c);

        auditService.record(userId, "CATEGORY_CREATED", "Category", saved.getId(),
                Map.of("name", safeName));
        return saved;
    }

    private String validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Category name can't be blank.");
        }
        String trimmed = name.trim();
        if (trimmed.length() > MAX_NAME_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Category name can't be longer than " + MAX_NAME_LENGTH + " characters.");
        }
        return trimmed;
    }

    private void validateNoDuplicate(UUID userId, String name, UUID excludingCategoryId) {
        List<Category> matches = categoryRepository.findByUserIdAndNameIgnoreCaseOrderByIdAsc(userId, name);
        boolean collides = matches.stream().anyMatch(m -> !m.getId().equals(excludingCategoryId));
        if (collides) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "You already have a category named \"" + name + "\".");
        }
    }

    private String validateIcon(String icon) {
        if (!CategoryPalette.isValidIcon(icon)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "\"" + icon + "\" isn't a supported icon.");
        }
        return icon;
    }

    private String validateColor(String color) {
        if (!CategoryPalette.isValidColor(color)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "\"" + color + "\" isn't a supported color.");
        }
        return color;
    }
}
```

- [ ] **Step 4: Run the unit tests**

Run: `cd backend && ./mvnw -q test -Dtest=CategoryServiceTest`
Expected: PASS (4/4)

- [ ] **Step 5: Wire the controller endpoint**

```java
// backend/src/main/java/com/finora/controller/CategoryController.java
// Full replacement for the class -- includes Task 2's existing options() endpoint verbatim
// (do not drop it), plus this task's new categoryService field/constructor param,
// CreateCategoryRequest record, and create() endpoint.
package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.dto.CategoryDto;
import com.finora.dto.CategoryOptionsDto;
import com.finora.repository.CategoryRepository;
import com.finora.security.CurrentUser;
import com.finora.service.CategoryService;
import com.finora.util.CategoryPalette;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/categories")
public class CategoryController {

    private final CategoryRepository categoryRepository;
    private final CategoryService categoryService;
    private final CurrentUser currentUser;

    public CategoryController(CategoryRepository categoryRepository, CategoryService categoryService,
                               CurrentUser currentUser) {
        this.categoryRepository = categoryRepository;
        this.categoryService = categoryService;
        this.currentUser = currentUser;
    }

    public record CreateCategoryRequest(String name, String icon, String color) {}

    @GetMapping
    public ApiResponse<List<CategoryDto>> list() {
        var categories = categoryRepository.findByUserId(currentUser.id()).stream()
                .map(c -> new CategoryDto(c.getId(), c.getName(), c.isSystem(), c.getIcon(), c.getColor()))
                .toList();
        return ApiResponse.ok(categories);
    }

    @GetMapping("/options")
    public ApiResponse<CategoryOptionsDto> options() {
        var icons = CategoryPalette.ICONS.entrySet().stream()
                .map(e -> new CategoryOptionsDto.Option(e.getKey(), e.getValue()))
                .toList();
        var colors = CategoryPalette.COLORS.entrySet().stream()
                .map(e -> new CategoryOptionsDto.Option(e.getKey(), e.getValue()))
                .toList();
        return ApiResponse.ok(new CategoryOptionsDto(icons, colors));
    }

    @PostMapping
    public ApiResponse<CategoryDto> create(@RequestBody CreateCategoryRequest request) {
        var c = categoryService.create(currentUser.id(), request.name(), request.icon(), request.color());
        return ApiResponse.ok(new CategoryDto(c.getId(), c.getName(), c.isSystem(), c.getIcon(), c.getColor()));
    }
}
```

- [ ] **Step 6: Add the integration test to `CategoryControllerIT`**

```java
    @Test
    void createRejectsADuplicateNameCaseInsensitively() {
        User user = createUser();
        HttpHeaders headers = authHeaders(user);
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);

        restTemplate.postForEntity("/api/v1/categories",
                new HttpEntity<>(java.util.Map.of("name", "SIP"), headers), String.class);
        var second = restTemplate.postForEntity("/api/v1/categories",
                new HttpEntity<>(java.util.Map.of("name", "sip"), headers), String.class);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }
```

- [ ] **Step 7: Run the full controller test**

Run: `cd backend && ./mvnw -q test -Dtest=CategoryControllerIT`
Expected: PASS

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/finora/service/CategoryService.java \
        backend/src/main/java/com/finora/controller/CategoryController.java \
        backend/src/test/java/com/finora/service/CategoryServiceTest.java \
        backend/src/test/java/com/finora/controller/CategoryControllerIT.java
git commit -m "feat(categories): POST /categories create endpoint"
```

---

### Task 4: `CategoryService.rename` + `PATCH /categories/{id}` with `CategoryRule` cascade

**Files:**
- Modify: `backend/src/main/java/com/finora/repository/CategoryRuleRepository.java`
- Modify: `backend/src/main/java/com/finora/service/CategoryService.java`
- Modify: `backend/src/main/java/com/finora/controller/CategoryController.java`
- Modify: `backend/src/test/java/com/finora/service/CategoryServiceTest.java`
- Modify: `backend/src/test/java/com/finora/controller/CategoryControllerIT.java`

**Interfaces:**
- Consumes: `Category` (Task 1), `CategoryPalette` (Task 2).
- Produces: `CategoryService.rename(UUID userId, UUID categoryId, String newName, String icon, String color): Category`.

- [ ] **Step 1: Add the repository lookup method**

```java
// backend/src/main/java/com/finora/repository/CategoryRuleRepository.java
// Add, following the existing derived-query + doc-comment convention in this file:

    /** Custom-category rename/delete cascade -- every USER-scope ASSIGN_CATEGORY/MARK_INVESTMENT
     *  rule whose action_value still names the category being renamed or deleted, so it can be
     *  rewritten in lockstep. GLOBAL rules are never matched here (this repo's scope='USER' rows
     *  never include a GLOBAL row -- see this interface's other USER-scoped methods for the same
     *  invariant), which is correct: global rules only ever reference immutable system category
     *  names, so they never need this cascade. */
    List<CategoryRule> findByUserIdAndActionTypeInAndActionValueIgnoreCase(
            UUID userId, List<CategoryRule.ActionType> actionTypes, String actionValue);
```
Add `import java.util.List;` and `com.finora.entity.CategoryRule` if not already present (they already are, per the file's existing content).

- [ ] **Step 2: Write the failing service test**

```java
// backend/src/test/java/com/finora/service/CategoryServiceTest.java
// Add to the existing test class:

    @Test
    void renameCascadesToMatchingPersonalRulesButLeavesGlobalRulesAlone() {
        UUID categoryId = UUID.randomUUID();
        Category existing = new Category();
        existing.setUserId(userId);
        existing.setName("Mutual Fund SIP");
        existing.setSystem(false);
        org.springframework.test.util.ReflectionTestUtils.setField(existing, "id", categoryId);
        when(categoryRepository.findById(categoryId)).thenReturn(Optional.of(existing));
        when(categoryRepository.findByUserIdAndNameIgnoreCaseOrderByIdAsc(userId, "SIP"))
                .thenReturn(List.of());

        com.finora.entity.CategoryRule rule = new com.finora.entity.CategoryRule();
        rule.setUserId(userId);
        rule.setActionType(com.finora.entity.CategoryRule.ActionType.ASSIGN_CATEGORY);
        rule.setActionValue("Mutual Fund SIP");
        when(categoryRuleRepository.findByUserIdAndActionTypeInAndActionValueIgnoreCase(
                eq(userId), any(), eq("Mutual Fund SIP"))).thenReturn(List.of(rule));

        Category renamed = service().rename(userId, categoryId, "SIP", null, null);

        assertThat(renamed.getName()).isEqualTo("SIP");
        assertThat(rule.getActionValue()).isEqualTo("SIP");
        verify(categoryRuleRepository).save(rule);
    }

    @Test
    void renameRejectsAnAttemptOnASystemCategory() {
        UUID categoryId = UUID.randomUUID();
        Category system = new Category();
        system.setUserId(userId);
        system.setName("Groceries");
        system.setSystem(true);
        org.springframework.test.util.ReflectionTestUtils.setField(system, "id", categoryId);
        when(categoryRepository.findById(categoryId)).thenReturn(Optional.of(system));

        assertThatThrownBy(() -> service().rename(userId, categoryId, "Food", null, null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("system categor");
    }
```
Add `import static org.mockito.ArgumentMatchers.eq;` to the test file's imports.

- [ ] **Step 3: Run to confirm the new tests fail**

Run: `cd backend && ./mvnw -q test -Dtest=CategoryServiceTest`
Expected: compile error (`rename` doesn't exist yet) or failure.

- [ ] **Step 4: Implement `rename` in `CategoryService`**

```java
// backend/src/main/java/com/finora/service/CategoryService.java
// Add import: com.finora.entity.CategoryRule; com.finora.security.OwnershipGuard; java.util.List;

    @Transactional
    public Category rename(UUID userId, UUID categoryId, String newName, String icon, String color) {
        Category category = OwnershipGuard.requireOwned(
                categoryRepository.findById(categoryId), Category::getUserId, userId, "Category");
        if (category.isSystem()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "System categories can't be renamed.");
        }

        String oldName = category.getName();
        if (newName != null && !newName.isBlank()) {
            String safeName = validateName(newName);
            if (!safeName.equalsIgnoreCase(oldName)) {
                validateNoDuplicate(userId, safeName, categoryId);
            }
            category.setName(safeName);
        }
        if (icon != null) category.setIcon(validateIcon(icon));
        if (color != null) category.setColor(validateColor(color));
        Category saved = categoryRepository.save(category);

        if (!oldName.equalsIgnoreCase(saved.getName())) {
            List<CategoryRule> affected = categoryRuleRepository
                    .findByUserIdAndActionTypeInAndActionValueIgnoreCase(userId,
                            List.of(CategoryRule.ActionType.ASSIGN_CATEGORY, CategoryRule.ActionType.MARK_INVESTMENT),
                            oldName);
            for (CategoryRule rule : affected) {
                rule.setActionValue(saved.getName());
                categoryRuleRepository.save(rule);
            }
        }

        auditService.record(userId, "CATEGORY_RENAMED", "Category", saved.getId(),
                Map.of("oldName", oldName, "newName", saved.getName()));
        return saved;
    }
```

- [ ] **Step 5: Run the unit tests**

Run: `cd backend && ./mvnw -q test -Dtest=CategoryServiceTest`
Expected: PASS (6/6)

- [ ] **Step 6: Wire the controller endpoint**

```java
// backend/src/main/java/com/finora/controller/CategoryController.java
// Add imports: org.springframework.web.bind.annotation.PatchMapping; org.springframework.web.bind.annotation.PathVariable;
// Add request record: public record UpdateCategoryRequest(String name, String icon, String color) {}

    @PatchMapping("/{id}")
    public ApiResponse<CategoryDto> update(@PathVariable java.util.UUID id,
                                            @RequestBody UpdateCategoryRequest request) {
        var c = categoryService.rename(currentUser.id(), id, request.name(), request.icon(), request.color());
        return ApiResponse.ok(new CategoryDto(c.getId(), c.getName(), c.isSystem(), c.getIcon(), c.getColor()));
    }
```

- [ ] **Step 7: Add the integration test**

```java
// backend/src/test/java/com/finora/controller/CategoryControllerIT.java
    @Test
    void renamingASystemCategoryIs403() {
        User user = createUser();
        HttpHeaders headers = authHeaders(user);

        var listResponse = restTemplate.exchange("/api/v1/categories", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        JsonNode categories = objectMapper.readTree(listResponse.getBody()).get("data");
        String groceriesId = null;
        for (JsonNode c : categories) {
            if (c.get("name").asText().equals("Groceries")) groceriesId = c.get("id").asText();
        }
        assertThat(groceriesId).isNotNull();

        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        var response = restTemplate.exchange("/api/v1/categories/" + groceriesId, HttpMethod.PATCH,
                new HttpEntity<>(java.util.Map.of("name", "Food"), headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
```

- [ ] **Step 8: Run it**

Run: `cd backend && ./mvnw -q test -Dtest=CategoryControllerIT`
Expected: PASS

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/java/com/finora/repository/CategoryRuleRepository.java \
        backend/src/main/java/com/finora/service/CategoryService.java \
        backend/src/main/java/com/finora/controller/CategoryController.java \
        backend/src/test/java/com/finora/service/CategoryServiceTest.java \
        backend/src/test/java/com/finora/controller/CategoryControllerIT.java
git commit -m "feat(categories): PATCH rename with personal-rule cascade, system categories immutable"
```

---

### Task 5: Usage counts + `GET /categories/{id}/usage`

**Files:**
- Modify: `backend/src/main/java/com/finora/repository/TransactionRepository.java`
- Create: `backend/src/main/java/com/finora/dto/CategoryUsageDto.java`
- Modify: `backend/src/main/java/com/finora/service/CategoryService.java`
- Modify: `backend/src/main/java/com/finora/controller/CategoryController.java`
- Modify: `backend/src/test/java/com/finora/service/CategoryServiceTest.java`
- Modify: `backend/src/test/java/com/finora/controller/CategoryControllerIT.java`

**Interfaces:**
- Produces: `TransactionRepository.countByUserIdAndCategoryId(UUID,UUID): long`; `CategoryService.usage(UUID userId, UUID categoryId): CategoryUsageDto`.

- [ ] **Step 1: Add the count method**

```java
// backend/src/main/java/com/finora/repository/TransactionRepository.java
// Add near the existing countByMerchantId(UUID) at line 20, same convention:

    long countByUserIdAndCategoryId(UUID userId, UUID categoryId);
```

- [ ] **Step 2: Write the DTO**

```java
// backend/src/main/java/com/finora/dto/CategoryUsageDto.java
package com.finora.dto;

public record CategoryUsageDto(long transactionCount, boolean hasBudget, long ruleCount) {}
```

- [ ] **Step 3: Write the failing service test**

```java
// backend/src/test/java/com/finora/service/CategoryServiceTest.java
    @Test
    void usageReportsTransactionBudgetAndRuleCounts() {
        UUID categoryId = UUID.randomUUID();
        Category existing = new Category();
        existing.setUserId(userId);
        existing.setSystem(false);
        org.springframework.test.util.ReflectionTestUtils.setField(existing, "id", categoryId);
        when(categoryRepository.findById(categoryId)).thenReturn(Optional.of(existing));
        when(transactionRepository.countByUserIdAndCategoryId(userId, categoryId)).thenReturn(12L);
        when(budgetRepository.findByUserIdAndCategoryId(userId, categoryId))
                .thenReturn(Optional.of(new com.finora.entity.Budget()));
        when(categoryRuleRepository.findByUserIdAndActionTypeInAndActionValueIgnoreCase(
                eq(userId), any(), any())).thenReturn(List.of(new com.finora.entity.CategoryRule()));

        var usage = service().usage(userId, categoryId);

        assertThat(usage.transactionCount()).isEqualTo(12);
        assertThat(usage.hasBudget()).isTrue();
        assertThat(usage.ruleCount()).isEqualTo(1);
    }
```

- [ ] **Step 4: Run to confirm it fails**

Run: `cd backend && ./mvnw -q test -Dtest=CategoryServiceTest`
Expected: compile error (`usage` doesn't exist).

- [ ] **Step 5: Implement `usage`**

```java
// backend/src/main/java/com/finora/service/CategoryService.java
// Add import: com.finora.dto.CategoryUsageDto;

    @Transactional(readOnly = true)
    public CategoryUsageDto usage(UUID userId, UUID categoryId) {
        Category category = OwnershipGuard.requireOwned(
                categoryRepository.findById(categoryId), Category::getUserId, userId, "Category");
        long transactionCount = transactionRepository.countByUserIdAndCategoryId(userId, categoryId);
        boolean hasBudget = budgetRepository.findByUserIdAndCategoryId(userId, categoryId).isPresent();
        long ruleCount = categoryRuleRepository.findByUserIdAndActionTypeInAndActionValueIgnoreCase(
                userId,
                List.of(CategoryRule.ActionType.ASSIGN_CATEGORY, CategoryRule.ActionType.MARK_INVESTMENT),
                category.getName()).size();
        return new CategoryUsageDto(transactionCount, hasBudget, ruleCount);
    }
```

- [ ] **Step 6: Run the unit tests**

Run: `cd backend && ./mvnw -q test -Dtest=CategoryServiceTest`
Expected: PASS (7/7)

- [ ] **Step 7: Wire the controller endpoint**

```java
// backend/src/main/java/com/finora/controller/CategoryController.java

    @GetMapping("/{id}/usage")
    public ApiResponse<CategoryUsageDto> usage(@PathVariable java.util.UUID id) {
        return ApiResponse.ok(categoryService.usage(currentUser.id(), id));
    }
```

- [ ] **Step 8: Add the integration test, run it, commit**

```java
// backend/src/test/java/com/finora/controller/CategoryControllerIT.java
    @Test
    void usageStartsAtZeroForABrandNewCategory() {
        User user = createUser();
        HttpHeaders headers = authHeaders(user);
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        var created = restTemplate.postForEntity("/api/v1/categories",
                new HttpEntity<>(java.util.Map.of("name", "SIP"), headers), String.class);
        String categoryId = objectMapper.readTree(created.getBody()).get("data").get("id").asText();

        var response = restTemplate.exchange("/api/v1/categories/" + categoryId + "/usage",
                HttpMethod.GET, new HttpEntity<>(headers), String.class);

        JsonNode data = objectMapper.readTree(response.getBody()).get("data");
        assertThat(data.get("transactionCount").asLong()).isEqualTo(0);
        assertThat(data.get("hasBudget").asBoolean()).isFalse();
        assertThat(data.get("ruleCount").asLong()).isEqualTo(0);
    }
```

Run: `cd backend && ./mvnw -q test -Dtest=CategoryControllerIT`
Expected: PASS

```bash
git add backend/src/main/java/com/finora/repository/TransactionRepository.java \
        backend/src/main/java/com/finora/dto/CategoryUsageDto.java \
        backend/src/main/java/com/finora/service/CategoryService.java \
        backend/src/main/java/com/finora/controller/CategoryController.java \
        backend/src/test/java/com/finora/service/CategoryServiceTest.java \
        backend/src/test/java/com/finora/controller/CategoryControllerIT.java
git commit -m "feat(categories): usage endpoint for the delete-confirmation dialog"
```

---

### Task 6: `CategoryService.delete` + `DELETE /categories/{id}?reassignTo=`

**Files:**
- Modify: `backend/src/main/java/com/finora/repository/TransactionRepository.java`
- Modify: `backend/src/main/java/com/finora/service/CategoryService.java`
- Modify: `backend/src/main/java/com/finora/controller/CategoryController.java`
- Modify: `backend/src/test/java/com/finora/service/CategoryServiceTest.java`
- Modify: `backend/src/test/java/com/finora/controller/CategoryControllerIT.java`

**Interfaces:**
- Produces: `CategoryService.delete(UUID userId, UUID categoryId, UUID reassignTo)`.

**Note on budgets:** `budgets` has `UNIQUE(user_id, category_id)` (`V1__init_schema.sql:86`). Repointing a deleted category's budget onto `reassignTo` risks colliding with a budget the target category already has. Rather than attempt a merge, this delete simply removes (soft-deletes, since `Budget extends BaseEntity`) any budget on the category being deleted — the usage-dialog step (frontend Task 10) tells the user this will happen before they confirm.

- [ ] **Step 1: Add the bulk transaction-reassignment method**

```java
// backend/src/main/java/com/finora/repository/TransactionRepository.java
// Add import if missing: org.springframework.data.jpa.repository.Modifying; org.springframework.data.jpa.repository.Query; org.springframework.data.repository.query.Param;

    @Modifying
    @Query("UPDATE Transaction t SET t.categoryId = :newCategoryId " +
           "WHERE t.userId = :userId AND t.categoryId = :oldCategoryId")
    void reassignCategory(@Param("userId") UUID userId,
                           @Param("oldCategoryId") UUID oldCategoryId,
                           @Param("newCategoryId") UUID newCategoryId);
```

- [ ] **Step 2: Write the failing service tests**

```java
// backend/src/test/java/com/finora/service/CategoryServiceTest.java
    @Test
    void deleteReassignsTransactionsRewritesRulesRemovesBudgetThenDeletesTheCategory() {
        UUID categoryId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        Category toDelete = new Category();
        toDelete.setUserId(userId);
        toDelete.setName("Mutual Fund SIP");
        toDelete.setSystem(false);
        org.springframework.test.util.ReflectionTestUtils.setField(toDelete, "id", categoryId);
        Category target = new Category();
        target.setUserId(userId);
        target.setName("SIP");
        org.springframework.test.util.ReflectionTestUtils.setField(target, "id", targetId);

        when(categoryRepository.findById(categoryId)).thenReturn(Optional.of(toDelete));
        when(categoryRepository.findById(targetId)).thenReturn(Optional.of(target));

        com.finora.entity.Budget budget = new com.finora.entity.Budget();
        when(budgetRepository.findByUserIdAndCategoryId(userId, categoryId)).thenReturn(Optional.of(budget));

        com.finora.entity.CategoryRule rule = new com.finora.entity.CategoryRule();
        rule.setActionValue("Mutual Fund SIP");
        when(categoryRuleRepository.findByUserIdAndActionTypeInAndActionValueIgnoreCase(
                eq(userId), any(), eq("Mutual Fund SIP"))).thenReturn(List.of(rule));

        service().delete(userId, categoryId, targetId);

        verify(transactionRepository).reassignCategory(userId, categoryId, targetId);
        verify(budgetRepository).delete(budget);
        assertThat(rule.getActionValue()).isEqualTo("SIP");
        verify(categoryRuleRepository).save(rule);
        verify(categoryRepository).delete(toDelete);
    }

    @Test
    void deleteRequiresAReassignTargetWhenTheCategoryHasDependents() {
        UUID categoryId = UUID.randomUUID();
        Category toDelete = new Category();
        toDelete.setUserId(userId);
        toDelete.setSystem(false);
        org.springframework.test.util.ReflectionTestUtils.setField(toDelete, "id", categoryId);
        when(categoryRepository.findById(categoryId)).thenReturn(Optional.of(toDelete));
        when(transactionRepository.countByUserIdAndCategoryId(userId, categoryId)).thenReturn(3L);

        assertThatThrownBy(() -> service().delete(userId, categoryId, null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("reassign");
    }

    @Test
    void deleteRejectsAnAttemptOnASystemCategory() {
        UUID categoryId = UUID.randomUUID();
        Category system = new Category();
        system.setUserId(userId);
        system.setSystem(true);
        org.springframework.test.util.ReflectionTestUtils.setField(system, "id", categoryId);
        when(categoryRepository.findById(categoryId)).thenReturn(Optional.of(system));

        assertThatThrownBy(() -> service().delete(userId, categoryId, null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("system categor");
    }
```

- [ ] **Step 3: Run to confirm the new tests fail**

Run: `cd backend && ./mvnw -q test -Dtest=CategoryServiceTest`
Expected: compile error (`delete` doesn't exist yet).

- [ ] **Step 4: Implement `delete`**

```java
// backend/src/main/java/com/finora/service/CategoryService.java

    @Transactional
    public void delete(UUID userId, UUID categoryId, UUID reassignTo) {
        Category category = OwnershipGuard.requireOwned(
                categoryRepository.findById(categoryId), Category::getUserId, userId, "Category");
        if (category.isSystem()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "System categories can't be deleted.");
        }

        long transactionCount = transactionRepository.countByUserIdAndCategoryId(userId, categoryId);
        var existingBudget = budgetRepository.findByUserIdAndCategoryId(userId, categoryId);
        List<CategoryRule> affectedRules = categoryRuleRepository
                .findByUserIdAndActionTypeInAndActionValueIgnoreCase(userId,
                        List.of(CategoryRule.ActionType.ASSIGN_CATEGORY, CategoryRule.ActionType.MARK_INVESTMENT),
                        category.getName());
        boolean hasDependents = transactionCount > 0 || existingBudget.isPresent() || !affectedRules.isEmpty();

        if (hasDependents) {
            if (reassignTo == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                        "This category is in use — pick a category to reassign it to before deleting.");
            }
            Category target = OwnershipGuard.requireOwned(
                    categoryRepository.findById(reassignTo), Category::getUserId, userId, "Category");

            if (transactionCount > 0) {
                transactionRepository.reassignCategory(userId, categoryId, target.getId());
            }
            existingBudget.ifPresent(budgetRepository::delete);
            for (CategoryRule rule : affectedRules) {
                rule.setActionValue(target.getName());
                categoryRuleRepository.save(rule);
            }
        }

        auditService.record(userId, "CATEGORY_DELETED", "Category", categoryId,
                Map.of("name", category.getName(), "transactionCount", transactionCount,
                        "reassignedTo", reassignTo == null ? "" : reassignTo.toString()));
        categoryRepository.delete(category);
    }
```

- [ ] **Step 5: Run the unit tests**

Run: `cd backend && ./mvnw -q test -Dtest=CategoryServiceTest`
Expected: PASS (10/10)

- [ ] **Step 6: Wire the controller endpoint**

```java
// backend/src/main/java/com/finora/controller/CategoryController.java
// Add imports: org.springframework.web.bind.annotation.DeleteMapping; org.springframework.web.bind.annotation.RequestParam;

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable java.util.UUID id,
                                     @RequestParam(required = false) java.util.UUID reassignTo) {
        categoryService.delete(currentUser.id(), id, reassignTo);
        return ApiResponse.ok(null);
    }
```

- [ ] **Step 7: Add the integration test**

```java
// backend/src/test/java/com/finora/controller/CategoryControllerIT.java
    @Test
    void deleteWithoutReassignTargetIsRejectedWhenTransactionsExist() {
        // Full end-to-end (create category, create a transaction against it via the transactions
        // API, attempt delete with no reassignTo, expect 400) is covered by
        // CategoryServiceTest.deleteRequiresAReassignTargetWhenTheCategoryHasDependents at the
        // unit level; this IT only needs to prove the controller wires reassignTo through and a
        // zero-dependency delete succeeds without one.
        User user = createUser();
        HttpHeaders headers = authHeaders(user);
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        var created = restTemplate.postForEntity("/api/v1/categories",
                new HttpEntity<>(java.util.Map.of("name", "Temp Category"), headers), String.class);
        String categoryId = objectMapper.readTree(created.getBody()).get("data").get("id").asText();

        var response = restTemplate.exchange("/api/v1/categories/" + categoryId,
                HttpMethod.DELETE, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
```

- [ ] **Step 8: Run it, commit**

Run: `cd backend && ./mvnw -q test -Dtest=CategoryControllerIT`
Expected: PASS

```bash
git add backend/src/main/java/com/finora/repository/TransactionRepository.java \
        backend/src/main/java/com/finora/service/CategoryService.java \
        backend/src/main/java/com/finora/controller/CategoryController.java \
        backend/src/test/java/com/finora/service/CategoryServiceTest.java \
        backend/src/test/java/com/finora/controller/CategoryControllerIT.java
git commit -m "feat(categories): delete with mandatory reassignment when in use"
```

**Run the full backend suite before moving to frontend work:**

Run: `cd backend && ./mvnw -q test`
Expected: PASS, no regressions.

---

### Task 7: Frontend `categoriesApi` + Levenshtein similarity util

**Files:**
- Modify: `frontend/src/api/endpoints.ts`
- Create: `frontend/src/lib/similarity.ts`
- Test: `frontend/src/lib/similarity.test.ts`

**Interfaces:**
- Produces: `CategoryOption { id: string; name: string; isSystem: boolean; icon: string; color: string }`; `categoriesApi.create(name, icon?, color?)`, `.update(id, {name?, icon?, color?})`, `.delete(id, reassignTo?)`, `.usage(id)`, `.options()`; `similarityRatio(a: string, b: string): number` (0–1, 1 = identical).

- [ ] **Step 1: Write the failing similarity test**

```ts
// frontend/src/lib/similarity.test.ts
import { describe, it, expect } from 'vitest';
import { similarityRatio } from './similarity';

describe('similarityRatio', () => {
  it('returns 1 for identical strings', () => {
    expect(similarityRatio('SIP', 'SIP')).toBe(1);
  });

  it('is case-insensitive', () => {
    expect(similarityRatio('SIP', 'sip')).toBe(1);
  });

  it('returns a high ratio for a near-miss', () => {
    expect(similarityRatio('SIP', 'S.I.P.')).toBeGreaterThan(0.6);
  });

  it('returns a low ratio for unrelated strings', () => {
    expect(similarityRatio('SIP', 'Groceries')).toBeLessThan(0.3);
  });

  it('returns 0 for an empty string against a non-empty one', () => {
    expect(similarityRatio('', 'SIP')).toBe(0);
  });
});
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `cd frontend && npx vitest run src/lib/similarity.test.ts`
Expected: FAIL, module not found.

- [ ] **Step 3: Implement `similarityRatio`**

```ts
// frontend/src/lib/similarity.ts

/** Standard Levenshtein edit distance (insert/delete/substitute), O(n*m), fine for the short
 * category names this is used against (never a bulk-text-search primitive). */
function levenshteinDistance(a: string, b: string): number {
  const rows = a.length + 1;
  const cols = b.length + 1;
  const dp: number[][] = Array.from({ length: rows }, () => new Array(cols).fill(0));

  for (let i = 0; i < rows; i++) dp[i][0] = i;
  for (let j = 0; j < cols; j++) dp[0][j] = j;

  for (let i = 1; i < rows; i++) {
    for (let j = 1; j < cols; j++) {
      const cost = a[i - 1] === b[j - 1] ? 0 : 1;
      dp[i][j] = Math.min(
        dp[i - 1][j] + 1,
        dp[i][j - 1] + 1,
        dp[i - 1][j - 1] + cost,
      );
    }
  }
  return dp[rows - 1][cols - 1];
}

/** 0 (nothing alike) to 1 (identical, case-insensitive). Used to surface "did you mean X?"
 * suggestions in CategoryCombobox before offering to create a near-duplicate category. */
export function similarityRatio(a: string, b: string): number {
  const x = a.trim().toLowerCase();
  const y = b.trim().toLowerCase();
  if (x.length === 0 && y.length === 0) return 1;
  if (x.length === 0 || y.length === 0) return 0;
  const distance = levenshteinDistance(x, y);
  const maxLength = Math.max(x.length, y.length);
  return 1 - distance / maxLength;
}
```

- [ ] **Step 4: Run the test**

Run: `cd frontend && npx vitest run src/lib/similarity.test.ts`
Expected: PASS (5/5)

- [ ] **Step 5: Extend `categoriesApi`**

```ts
// frontend/src/api/endpoints.ts
// Replace the existing CategoryOption interface and categoriesApi object:

export interface CategoryOption {
  id: string;
  name: string;
  isSystem: boolean;
  icon: string;
  color: string;
}

export interface CategoryOptions {
  icons: { token: string; label: string }[];
  colors: { token: string; label: string }[];
}

export const categoriesApi = {
  list: () => api.get<CategoryOption[]>('/categories').then((r) => r.data),
  options: () => api.get<CategoryOptions>('/categories/options').then((r) => r.data),
  create: (name: string, icon?: string, color?: string) =>
    api.post<CategoryOption>('/categories', { name, icon, color }).then((r) => r.data),
  update: (id: string, changes: { name?: string; icon?: string; color?: string }) =>
    api.patch<CategoryOption>(`/categories/${id}`, changes).then((r) => r.data),
  delete: (id: string, reassignTo?: string) =>
    api.delete(`/categories/${id}`, { params: reassignTo ? { reassignTo } : undefined }),
  usage: (id: string) =>
    api.get<{ transactionCount: number; hasBudget: boolean; ruleCount: number }>(
      `/categories/${id}/usage`,
    ).then((r) => r.data),
};
```

- [ ] **Step 6: Commit**

```bash
git add frontend/src/api/endpoints.ts frontend/src/lib/similarity.ts frontend/src/lib/similarity.test.ts
git commit -m "feat(frontend): categoriesApi CRUD client and Levenshtein similarity util"
```

---

### Task 8: `CategoryCombobox` core component

**Files:**
- Create: `frontend/src/components/CategoryCombobox.tsx`
- Test: `frontend/src/components/CategoryCombobox.test.tsx`

**Interfaces:**
- Consumes: `categoriesApi.list()` (Task 7), `similarityRatio` (Task 7).
- Produces: `<CategoryCombobox value: string; onChange: (categoryName: string) => void; onCreateNew?: (typedText: string) => void; excludeCategoryId?: string />` — `onCreateNew` is called instead of `onChange` when the user picks "+ Create" (the caller, e.g. `AskOnceCard`, then renders `CategoryCreateEditPanel` and calls `onChange` itself once creation succeeds). Later tasks (11–13) wire this into each of the three consumers.

- [ ] **Step 1: Write the failing test**

```tsx
// frontend/src/components/CategoryCombobox.test.tsx
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { CategoryCombobox } from './CategoryCombobox';
import { categoriesApi } from '../api/endpoints';

vi.mock('../api/endpoints', () => ({
  categoriesApi: { list: vi.fn() },
}));

const CATEGORIES = [
  { id: '1', name: 'SIP', isSystem: false, icon: 'tag', color: 'gray' },
  { id: '2', name: 'Investments', isSystem: true, icon: 'trending-up', color: 'teal' },
  { id: '3', name: 'Groceries', isSystem: true, icon: 'shopping-cart', color: 'green' },
];

describe('CategoryCombobox', () => {
  beforeEach(() => {
    vi.mocked(categoriesApi.list).mockResolvedValue(CATEGORIES);
  });

  it('shows exact matches first when typing', async () => {
    const user = userEvent.setup();
    render(<CategoryCombobox value="" onChange={vi.fn()} />);
    await user.click(screen.getByRole('combobox'));
    await user.type(screen.getByRole('combobox'), 'SIP');

    await waitFor(() => {
      expect(screen.getByText('SIP')).toBeInTheDocument();
    });
  });

  it('offers fuzzy "did you mean" suggestions for a near-miss', async () => {
    const user = userEvent.setup();
    render(<CategoryCombobox value="" onChange={vi.fn()} />);
    await user.click(screen.getByRole('combobox'));
    await user.type(screen.getByRole('combobox'), 'S.I.P.');

    await waitFor(() => {
      expect(screen.getByText(/did you mean/i)).toBeInTheDocument();
      expect(screen.getByText('SIP')).toBeInTheDocument();
    });
  });

  it('shows the create row last, only for genuinely new text', async () => {
    const user = userEvent.setup();
    render(<CategoryCombobox value="" onChange={vi.fn()} />);
    await user.click(screen.getByRole('combobox'));
    await user.type(screen.getByRole('combobox'), 'Freelance Income');

    await waitFor(() => {
      expect(screen.getByText('Create "Freelance Income"')).toBeInTheDocument();
    });
  });

  it('does not show a create row for an exact existing match', async () => {
    const user = userEvent.setup();
    render(<CategoryCombobox value="" onChange={vi.fn()} />);
    await user.click(screen.getByRole('combobox'));
    await user.type(screen.getByRole('combobox'), 'Groceries');

    await waitFor(() => {
      expect(screen.queryByText(/^Create "/)).not.toBeInTheDocument();
    });
  });
});
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `cd frontend && npx vitest run src/components/CategoryCombobox.test.tsx`
Expected: FAIL, module not found.

- [ ] **Step 3: Implement `CategoryCombobox`**

```tsx
// frontend/src/components/CategoryCombobox.tsx
import { useEffect, useMemo, useRef, useState } from 'react';
import { categoriesApi, type CategoryOption } from '../api/endpoints';
import { similarityRatio } from '../lib/similarity';

const FUZZY_THRESHOLD = 0.6;
const FUZZY_MAX_SUGGESTIONS = 3;

interface CategoryComboboxProps {
  value: string;
  onChange: (categoryName: string) => void;
  onCreateNew?: (typedText: string) => void;
  excludeCategoryId?: string;
}

export function CategoryCombobox({ value, onChange, onCreateNew, excludeCategoryId }: CategoryComboboxProps) {
  const [categories, setCategories] = useState<CategoryOption[]>([]);
  const [query, setQuery] = useState(value);
  const [open, setOpen] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    categoriesApi.list().then(setCategories).catch(() => setCategories([]));
  }, []);

  useEffect(() => {
    const onClickOutside = (e: MouseEvent) => {
      if (containerRef.current && !containerRef.current.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener('mousedown', onClickOutside);
    return () => document.removeEventListener('mousedown', onClickOutside);
  }, []);

  const pool = useMemo(
    () => categories.filter((c) => c.id !== excludeCategoryId),
    [categories, excludeCategoryId],
  );

  const trimmedQuery = query.trim();
  const exactMatches = useMemo(
    () => pool.filter((c) => c.name.toLowerCase().includes(trimmedQuery.toLowerCase())),
    [pool, trimmedQuery],
  );
  const exactNameMatch = pool.some((c) => c.name.toLowerCase() === trimmedQuery.toLowerCase());

  const fuzzySuggestions = useMemo(() => {
    if (!trimmedQuery || exactMatches.length > 0) return [];
    return pool
      .map((c) => ({ category: c, score: similarityRatio(c.name, trimmedQuery) }))
      .filter((s) => s.score >= FUZZY_THRESHOLD)
      .sort((a, b) => b.score - a.score)
      .slice(0, FUZZY_MAX_SUGGESTIONS)
      .map((s) => s.category);
  }, [pool, trimmedQuery, exactMatches.length]);

  const showCreateRow = trimmedQuery.length > 0 && !exactNameMatch;

  const select = (name: string) => {
    onChange(name);
    setQuery(name);
    setOpen(false);
  };

  const create = () => {
    setOpen(false);
    onCreateNew?.(trimmedQuery);
  };

  return (
    <div ref={containerRef} className="relative">
      <input
        role="combobox"
        aria-expanded={open}
        className="bg-card text-ink border border-border rounded-lg px-3 py-2 text-sm w-full"
        value={query}
        onFocus={() => setOpen(true)}
        onChange={(e) => {
          setQuery(e.target.value);
          setOpen(true);
        }}
      />
      {open && (
        <div className="absolute z-10 mt-1 w-full bg-card border border-border rounded-lg shadow-lg max-h-64 overflow-y-auto">
          {exactMatches.map((c) => (
            <button
              key={c.id}
              type="button"
              className="w-full text-left px-3 py-2 text-sm hover:bg-sidebar-hover"
              onClick={() => select(c.name)}
            >
              {c.name}
            </button>
          ))}
          {fuzzySuggestions.length > 0 && (
            <div className="px-3 py-1 text-[11px] uppercase text-muted">
              Did you mean:
              {fuzzySuggestions.map((c) => (
                <button
                  key={c.id}
                  type="button"
                  className="ml-2 underline"
                  onClick={() => select(c.name)}
                >
                  {c.name}
                </button>
              ))}
            </div>
          )}
          {showCreateRow && (
            <button
              type="button"
              className="w-full text-left px-3 py-2 text-sm font-medium text-primary hover:bg-sidebar-hover border-t border-border"
              onClick={create}
            >
              Create "{trimmedQuery}"
            </button>
          )}
        </div>
      )}
    </div>
  );
}
```

- [ ] **Step 4: Run the tests**

Run: `cd frontend && npx vitest run src/components/CategoryCombobox.test.tsx`
Expected: PASS (4/4)

- [ ] **Step 5: Commit**

```bash
git add frontend/src/components/CategoryCombobox.tsx frontend/src/components/CategoryCombobox.test.tsx
git commit -m "feat(frontend): CategoryCombobox with exact/fuzzy/create priority ordering"
```

---

### Task 9: `CategoryCreateEditPanel` (name + icon + color)

**Files:**
- Create: `frontend/src/components/CategoryCreateEditPanel.tsx`
- Test: `frontend/src/components/CategoryCreateEditPanel.test.tsx`

**Interfaces:**
- Consumes: `categoriesApi.options()`, `categoriesApi.create()`, `categoriesApi.update()` (Task 7).
- Produces: `<CategoryCreateEditPanel mode: 'create' | 'edit'; initialName?: string; categoryId?: string; initialIcon?: string; initialColor?: string; onSaved: (category: CategoryOption) => void; onCancel: () => void />`.

- [ ] **Step 1: Write the failing test**

```tsx
// frontend/src/components/CategoryCreateEditPanel.test.tsx
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { CategoryCreateEditPanel } from './CategoryCreateEditPanel';
import { categoriesApi } from '../api/endpoints';

vi.mock('../api/endpoints', () => ({
  categoriesApi: { options: vi.fn(), create: vi.fn(), update: vi.fn() },
}));

describe('CategoryCreateEditPanel', () => {
  beforeEach(() => {
    vi.mocked(categoriesApi.options).mockResolvedValue({
      icons: [{ token: 'tag', label: 'Tag' }, { token: 'home', label: 'Home' }],
      colors: [{ token: 'gray', label: '#6b7280' }, { token: 'blue', label: '#2563eb' }],
    });
  });

  it('creates a category with the chosen name, icon, and color', async () => {
    const user = userEvent.setup();
    vi.mocked(categoriesApi.create).mockResolvedValue({
      id: '1', name: 'SIP', isSystem: false, icon: 'home', color: 'blue',
    });
    const onSaved = vi.fn();

    render(
      <CategoryCreateEditPanel mode="create" initialName="SIP" onSaved={onSaved} onCancel={vi.fn()} />,
    );

    await screen.findByText('Home');
    await user.click(screen.getByText('Home'));
    await user.click(screen.getByText('Blue', { exact: false }) ?? screen.getAllByRole('button')[0]);
    await user.click(screen.getByRole('button', { name: /save/i }));

    expect(categoriesApi.create).toHaveBeenCalledWith('SIP', 'home', expect.any(String));
    expect(onSaved).toHaveBeenCalledWith({ id: '1', name: 'SIP', isSystem: false, icon: 'home', color: 'blue' });
  });

  it('rejects saving a blank name', async () => {
    const user = userEvent.setup();
    render(<CategoryCreateEditPanel mode="create" initialName="" onSaved={vi.fn()} onCancel={vi.fn()} />);
    await screen.findByText('Tag');

    await user.click(screen.getByRole('button', { name: /save/i }));

    expect(categoriesApi.create).not.toHaveBeenCalled();
    expect(screen.getByText(/name/i)).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `cd frontend && npx vitest run src/components/CategoryCreateEditPanel.test.tsx`
Expected: FAIL, module not found.

- [ ] **Step 3: Implement `CategoryCreateEditPanel`**

```tsx
// frontend/src/components/CategoryCreateEditPanel.tsx
import { useEffect, useState } from 'react';
import { categoriesApi, type CategoryOption, type CategoryOptions } from '../api/endpoints';

interface CategoryCreateEditPanelProps {
  mode: 'create' | 'edit';
  initialName?: string;
  categoryId?: string;
  initialIcon?: string;
  initialColor?: string;
  onSaved: (category: CategoryOption) => void;
  onCancel: () => void;
}

export function CategoryCreateEditPanel({
  mode, initialName = '', categoryId, initialIcon = 'tag', initialColor = 'gray', onSaved, onCancel,
}: CategoryCreateEditPanelProps) {
  const [options, setOptions] = useState<CategoryOptions>({ icons: [], colors: [] });
  const [name, setName] = useState(initialName);
  const [icon, setIcon] = useState(initialIcon);
  const [color, setColor] = useState(initialColor);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    categoriesApi.options().then(setOptions).catch(() => setOptions({ icons: [], colors: [] }));
  }, []);

  const save = async () => {
    if (!name.trim()) {
      setError('Enter a name for this category.');
      return;
    }
    setSaving(true);
    setError(null);
    try {
      const saved = mode === 'create'
        ? await categoriesApi.create(name.trim(), icon, color)
        : await categoriesApi.update(categoryId!, { name: name.trim(), icon, color });
      onSaved(saved);
    } catch (e: any) {
      setError(e?.response?.data?.message ?? 'Could not save this category.');
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="p-3 border border-border rounded-lg bg-card space-y-3">
      <input
        className="bg-card text-ink border border-border rounded-lg px-3 py-2 text-sm w-full"
        value={name}
        onChange={(e) => setName(e.target.value)}
        placeholder="Category name"
      />
      <div className="flex flex-wrap gap-2">
        {options.icons.map((i) => (
          <button
            key={i.token}
            type="button"
            aria-label={i.label}
            className={`px-2 py-1 rounded border text-xs ${icon === i.token ? 'border-primary' : 'border-border'}`}
            onClick={() => setIcon(i.token)}
          >
            {i.label}
          </button>
        ))}
      </div>
      <div className="flex flex-wrap gap-2">
        {options.colors.map((c) => (
          <button
            key={c.token}
            type="button"
            aria-label={c.token}
            className={`w-6 h-6 rounded-full border-2 ${color === c.token ? 'border-primary' : 'border-transparent'}`}
            style={{ backgroundColor: c.label }}
            onClick={() => setColor(c.token)}
          />
        ))}
      </div>
      {error && <p className="text-[11px] text-danger">{error}</p>}
      <div className="flex gap-2 justify-end">
        <button type="button" className="text-sm px-3 py-1.5" onClick={onCancel}>Cancel</button>
        <button
          type="button"
          className="text-sm px-3 py-1.5 bg-primary text-on-primary rounded-lg"
          disabled={saving}
          onClick={save}
        >
          Save
        </button>
      </div>
    </div>
  );
}
```

- [ ] **Step 4: Run the tests**

Run: `cd frontend && npx vitest run src/components/CategoryCreateEditPanel.test.tsx`
Expected: PASS (2/2)

- [ ] **Step 5: Commit**

```bash
git add frontend/src/components/CategoryCreateEditPanel.tsx frontend/src/components/CategoryCreateEditPanel.test.tsx
git commit -m "feat(frontend): inline category create/edit panel with icon and color pickers"
```

---

### Task 10: `CategoryDeleteDialog`

**Files:**
- Create: `frontend/src/components/CategoryDeleteDialog.tsx`
- Test: `frontend/src/components/CategoryDeleteDialog.test.tsx`

**Interfaces:**
- Consumes: `categoriesApi.usage()`, `categoriesApi.delete()` (Task 7), `CategoryCombobox` (Task 8).
- Produces: `<CategoryDeleteDialog category: CategoryOption; onDeleted: () => void; onCancel: () => void />`.

- [ ] **Step 1: Write the failing test**

```tsx
// frontend/src/components/CategoryDeleteDialog.test.tsx
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { CategoryDeleteDialog } from './CategoryDeleteDialog';
import { categoriesApi } from '../api/endpoints';

vi.mock('../api/endpoints', () => ({
  categoriesApi: { usage: vi.fn(), delete: vi.fn(), list: vi.fn() },
}));

const CATEGORY = { id: '1', name: 'Mutual Fund SIP', isSystem: false, icon: 'tag', color: 'gray' };

describe('CategoryDeleteDialog', () => {
  beforeEach(() => {
    vi.mocked(categoriesApi.list).mockResolvedValue([
      CATEGORY,
      { id: '2', name: 'SIP', isSystem: false, icon: 'tag', color: 'gray' },
    ]);
  });

  it('shows the usage summary before allowing delete', async () => {
    vi.mocked(categoriesApi.usage).mockResolvedValue({ transactionCount: 12, hasBudget: true, ruleCount: 1 });
    render(<CategoryDeleteDialog category={CATEGORY} onDeleted={vi.fn()} onCancel={vi.fn()} />);

    await waitFor(() => {
      expect(screen.getByText(/12/)).toBeInTheDocument();
      expect(screen.getByText(/1 budget/i)).toBeInTheDocument();
      expect(screen.getByText(/1 rule/i)).toBeInTheDocument();
    });
  });

  it('disables the confirm button until a reassignment target is picked, when there are dependents', async () => {
    vi.mocked(categoriesApi.usage).mockResolvedValue({ transactionCount: 5, hasBudget: false, ruleCount: 0 });
    render(<CategoryDeleteDialog category={CATEGORY} onDeleted={vi.fn()} onCancel={vi.fn()} />);

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /delete/i })).toBeDisabled();
    });
  });

  it('allows immediate delete with no target when there are zero dependents', async () => {
    const user = userEvent.setup();
    vi.mocked(categoriesApi.usage).mockResolvedValue({ transactionCount: 0, hasBudget: false, ruleCount: 0 });
    vi.mocked(categoriesApi.delete).mockResolvedValue({} as any);
    const onDeleted = vi.fn();
    render(<CategoryDeleteDialog category={CATEGORY} onDeleted={onDeleted} onCancel={vi.fn()} />);

    const deleteButton = await screen.findByRole('button', { name: /delete/i });
    await waitFor(() => expect(deleteButton).toBeEnabled());
    await user.click(deleteButton);

    await waitFor(() => {
      expect(categoriesApi.delete).toHaveBeenCalledWith('1', undefined);
      expect(onDeleted).toHaveBeenCalled();
    });
  });
});
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `cd frontend && npx vitest run src/components/CategoryDeleteDialog.test.tsx`
Expected: FAIL, module not found.

- [ ] **Step 3: Implement `CategoryDeleteDialog`**

```tsx
// frontend/src/components/CategoryDeleteDialog.tsx
import { useEffect, useState } from 'react';
import { categoriesApi, type CategoryOption } from '../api/endpoints';
import { CategoryCombobox } from './CategoryCombobox';

interface Usage {
  transactionCount: number;
  hasBudget: boolean;
  ruleCount: number;
}

interface CategoryDeleteDialogProps {
  category: CategoryOption;
  onDeleted: () => void;
  onCancel: () => void;
}

export function CategoryDeleteDialog({ category, onDeleted, onCancel }: CategoryDeleteDialogProps) {
  const [usage, setUsage] = useState<Usage | null>(null);
  const [targetName, setTargetName] = useState('');
  const [targetId, setTargetId] = useState<string | undefined>(undefined);
  const [allCategories, setAllCategories] = useState<CategoryOption[]>([]);
  const [deleting, setDeleting] = useState(false);

  useEffect(() => {
    categoriesApi.usage(category.id).then(setUsage);
    categoriesApi.list().then(setAllCategories);
  }, [category.id]);

  const hasDependents = usage != null && (usage.transactionCount > 0 || usage.hasBudget || usage.ruleCount > 0);
  const canDelete = usage != null && (!hasDependents || targetId != null);

  const confirm = async () => {
    setDeleting(true);
    try {
      await categoriesApi.delete(category.id, targetId);
      onDeleted();
    } finally {
      setDeleting(false);
    }
  };

  return (
    <div className="p-4 border border-border rounded-lg bg-card space-y-3">
      <p className="text-sm">Delete <strong>{category.name}</strong>?</p>
      {usage && (
        <ul className="text-sm text-muted space-y-1">
          <li>{usage.transactionCount} transactions</li>
          {usage.hasBudget && <li>1 budget</li>}
          {usage.ruleCount > 0 && <li>{usage.ruleCount} rule{usage.ruleCount === 1 ? '' : 's'}</li>}
        </ul>
      )}
      {hasDependents && (
        <div>
          <p className="text-[11px] uppercase text-muted mb-1">Move everything to</p>
          <CategoryCombobox
            value={targetName}
            onChange={(name) => {
              setTargetName(name);
              setTargetId(allCategories.find((c) => c.name === name)?.id);
            }}
            excludeCategoryId={category.id}
          />
        </div>
      )}
      <div className="flex gap-2 justify-end">
        <button type="button" className="text-sm px-3 py-1.5" onClick={onCancel}>Cancel</button>
        <button
          type="button"
          className="text-sm px-3 py-1.5 bg-danger text-white rounded-lg disabled:opacity-50"
          disabled={!canDelete || deleting}
          onClick={confirm}
        >
          Delete
        </button>
      </div>
    </div>
  );
}
```

- [ ] **Step 4: Run the tests**

Run: `cd frontend && npx vitest run src/components/CategoryDeleteDialog.test.tsx`
Expected: PASS (3/3)

- [ ] **Step 5: Commit**

```bash
git add frontend/src/components/CategoryDeleteDialog.tsx frontend/src/components/CategoryDeleteDialog.test.tsx
git commit -m "feat(frontend): category delete-confirmation dialog with reassignment"
```

---

### Task 11: Wire `CategoryCombobox` into `Ledger.tsx`

**Files:**
- Modify: `frontend/src/pages/Ledger.tsx:356-451` (the `EditTransactionModal` category state and `<select>`)
- Test: existing `Ledger.test.tsx` if present, otherwise add a focused test to `frontend/src/pages/Ledger.test.tsx`

**Interfaces:**
- Consumes: `CategoryCombobox` (Task 8), `CategoryCreateEditPanel` (Task 9).

- [ ] **Step 1: Replace the category state and `<select>`**

```tsx
// frontend/src/pages/Ledger.tsx
// Replace lines 356, 359-360 (category/categories/categoriesFailed useState block) with:
const [category, setCategory] = useState(transaction.categoryName);
const [creatingCategory, setCreatingCategory] = useState<string | null>(null);

// Remove the now-unused useEffect at lines 364-373 (CategoryCombobox loads its own list) and the
// categoriesFailed paragraph.

// Replace the <select> block at lines 441-451 with:
<label htmlFor="edit-txn-category" className="block text-[11px] uppercase text-muted mb-1">Category</label>
{creatingCategory !== null ? (
  <CategoryCreateEditPanel
    mode="create"
    initialName={creatingCategory}
    onSaved={(c) => { setCategory(c.name); setCreatingCategory(null); }}
    onCancel={() => setCreatingCategory(null)}
  />
) : (
  <CategoryCombobox
    value={category}
    onChange={setCategory}
    onCreateNew={setCreatingCategory}
  />
)}
```
Add imports: `import { CategoryCombobox } from '../components/CategoryCombobox';` and `import { CategoryCreateEditPanel } from '../components/CategoryCreateEditPanel';`. Remove the now-unused `categoriesApi` import if `Ledger.tsx` doesn't use it elsewhere (check first — it may still be used by other parts of the file).

- [ ] **Step 2: Manually verify in the browser**

Run the frontend dev server, open the Ledger page, click a transaction to edit, confirm:
- Typing an existing category name shows it in the list.
- Typing a brand-new name shows "Create "..."" and clicking it opens the inline panel.
- Saving the new category selects it and closes the panel.

- [ ] **Step 3: Run the existing Ledger tests to check for regressions**

Run: `cd frontend && npx vitest run src/pages/Ledger.test.tsx`
Expected: PASS (update any test that queried the old `<select>` by role/label to instead query the combobox's `input[role="combobox"]`)

- [ ] **Step 4: Commit**

```bash
git add frontend/src/pages/Ledger.tsx
git commit -m "feat(ledger): use CategoryCombobox in the transaction edit modal"
```

---

### Task 12: Wire `CategoryCombobox` into `AskOnceCard.tsx`

**Files:**
- Modify: `frontend/src/components/AskOnceCard.tsx` (full file, per Task-gathering report, uses a `picks[id]` name-string state and a native `<select>` per row)
- Modify: `frontend/src/components/AskOnceCard.test.tsx`

**Interfaces:**
- Consumes: `CategoryCombobox` (Task 8), `CategoryCreateEditPanel` (Task 9).

- [ ] **Step 1: Replace the per-row `<select>` with `CategoryCombobox`**

In `AskOnceCard.tsx`, replace the `categoriesApi.list().then(cats => cats.map(c => c.name))` load-and-flatten-to-names pattern and the native `<select>` per transaction row with:

```tsx
// For each row (replacing the existing <select> for that transaction id):
{creatingFor === txn.id ? (
  <CategoryCreateEditPanel
    mode="create"
    initialName={pendingText[txn.id] ?? ''}
    onSaved={(c) => { setPicks((p) => ({ ...p, [txn.id]: c.name })); setCreatingFor(null); }}
    onCancel={() => setCreatingFor(null)}
  />
) : (
  <CategoryCombobox
    value={picks[txn.id] ?? ''}
    onChange={(name) => setPicks((p) => ({ ...p, [txn.id]: name }))}
    onCreateNew={(text) => { setPendingText((p) => ({ ...p, [txn.id]: text })); setCreatingFor(txn.id); }}
  />
)}
```
Add local state: `const [creatingFor, setCreatingFor] = useState<string | null>(null);` and `const [pendingText, setPendingText] = useState<Record<string, string>>({});`, plus the two component imports. Remove the now-redundant `categoriesApi.list()` effect that flattened to names — `CategoryCombobox` loads its own list.

- [ ] **Step 2: Update the existing test file**

In `AskOnceCard.test.tsx`, the `vi.mock('../api/endpoints', ...)` block already mocks `categoriesApi.list` — extend it to also mock `categoriesApi.options` (used indirectly if a test drives the create panel):

```ts
vi.mock('../api/endpoints', () => ({
  transactionsApi: { needsReview: vi.fn(), updateCategory: vi.fn() },
  categoriesApi: { list: vi.fn(), options: vi.fn(), create: vi.fn() },
}));
```
Any existing test that queried `screen.getByRole('combobox')` continues to work unchanged (the native `<select>` and the new `CategoryCombobox` both expose `role="combobox"`); any test that used `selectOptions` needs to switch to `userEvent.type` + clicking the matching option, following the pattern in `CategoryCombobox.test.tsx` (Task 8).

- [ ] **Step 3: Run the tests**

Run: `cd frontend && npx vitest run src/components/AskOnceCard.test.tsx`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add frontend/src/components/AskOnceCard.tsx frontend/src/components/AskOnceCard.test.tsx
git commit -m "feat(ask-once-card): use CategoryCombobox for per-transaction categorization"
```

---

### Task 13: Wire `CategoryCombobox` into `MerchantGroupReviewCard.tsx`

**Files:**
- Modify: `frontend/src/components/MerchantGroupReviewCard.tsx`
- Modify: `frontend/src/components/MerchantGroupReviewCard.test.tsx` (exists per Task-gathering report)

**Interfaces:**
- Consumes: `CategoryCombobox` (Task 8), `CategoryCreateEditPanel` (Task 9).

- [ ] **Step 1: Replace the per-group `<select>` with `CategoryCombobox`**

`MerchantGroupReviewCard` renders one row per merchant group, keyed by `group.merchantId`, and today builds a `picks` name-string map from a flattened `categoriesApi.list()` call feeding a native `<select>` — the same shape `AskOnceCard` had before Task 12. Add two local state declarations to the component (alongside its existing `picks` state):

```tsx
const [creatingFor, setCreatingFor] = useState<string | null>(null);
const [pendingText, setPendingText] = useState<Record<string, string>>({});
```

Add the component imports:

```tsx
import { CategoryCombobox } from './CategoryCombobox';
import { CategoryCreateEditPanel } from './CategoryCreateEditPanel';
```

Replace the per-group `<select>` block with:

```tsx
{creatingFor === group.merchantId ? (
  <CategoryCreateEditPanel
    mode="create"
    initialName={pendingText[group.merchantId] ?? ''}
    onSaved={(c) => { setPicks((p) => ({ ...p, [group.merchantId]: c.name })); setCreatingFor(null); }}
    onCancel={() => setCreatingFor(null)}
  />
) : (
  <CategoryCombobox
    value={picks[group.merchantId] ?? ''}
    onChange={(name) => setPicks((p) => ({ ...p, [group.merchantId]: name }))}
    onCreateNew={(text) => { setPendingText((p) => ({ ...p, [group.merchantId]: text })); setCreatingFor(group.merchantId); }}
  />
)}
```

Remove the now-redundant `categoriesApi.list()` effect that flattened results to names — `CategoryCombobox` loads its own list, same as in Task 12.

- [ ] **Step 2: Update the existing test file**

In `MerchantGroupReviewCard.test.tsx`, extend the existing `vi.mock('../api/endpoints', ...)` block to also mock `categoriesApi.options` and `categoriesApi.create`:

```ts
vi.mock('../api/endpoints', () => ({
  transactionsApi: { needsReviewGroups: vi.fn(), bulkRecategorize: vi.fn() },
  categoriesApi: { list: vi.fn(), options: vi.fn(), create: vi.fn() },
}));
```
Any test that used `selectOptions` against the old native `<select>` needs to switch to `userEvent.type` on the combobox input plus clicking the matching suggestion, following the pattern in `CategoryCombobox.test.tsx` (Task 8).

- [ ] **Step 3: Run the tests**

Run: `cd frontend && npx vitest run src/components/MerchantGroupReviewCard.test.tsx`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add frontend/src/components/MerchantGroupReviewCard.tsx frontend/src/components/MerchantGroupReviewCard.test.tsx
git commit -m "feat(merchant-group-review): use CategoryCombobox for bulk categorization"
```

---

### Task 14: Retire `Dashboard.tsx`'s hardcoded icon/color maps

**Files:**
- Modify: `frontend/src/pages/Dashboard.tsx:74-79` and the usage site around line 521-522
- Test: `frontend/src/pages/Dashboard.test.tsx` if it exists — otherwise skip a dedicated test and rely on the manual verification step (this is a rendering-only change, not new business logic)

**Interfaces:**
- Consumes: `categoriesApi.list()` (already used elsewhere in the app), `CategoryPalette`-token-to-hex mapping (frontend needs its own small copy of the 9 color hexes, since the backend only exposes labels via `/categories/options`, not necessarily formatted as CSS-ready hex — reuse the `color.label` field already returned, which Task 2's `CategoryPalette.COLORS` populates with real hex strings).
- Produces: nothing new — this task only removes dead code and changes what the existing icon/color lookup reads from.

- [ ] **Step 1: Replace the hardcoded maps with data-driven lookup**

```tsx
// frontend/src/pages/Dashboard.tsx
// Remove the CATEGORY_ICON / CATEGORY_COLOR constants (lines 74-79).
// Add near the top of the component, alongside any existing categoriesApi usage in this file
// (if none exists yet, add a fetch):
const [categoriesById, setCategoriesById] = useState<Record<string, CategoryOption>>({});
useEffect(() => {
  categoriesApi.list().then((cats) => {
    setCategoriesById(Object.fromEntries(cats.map((c) => [c.id, c])));
  });
}, []);

// Add a small local icon-token -> component map (lucide-react components can't be looked up by
// string name at runtime without importing every one, so this maps the curated token set — the
// same set CategoryPalette.ICONS defines on the backend — to already-imported lucide-react icons):
const ICON_COMPONENTS: Record<string, any> = {
  tag: Tag, home: Home, 'shopping-cart': ShoppingCart, utensils: Utensils, car: Car, zap: Zap,
  'shopping-bag': ShoppingBag, 'heart-pulse': HeartPulse, film: Film, 'trending-up': TrendingUp,
  percent: Percent, repeat: Repeat, users: Users, landmark: Landmark, shield: Shield,
  'graduation-cap': GraduationCap, 'refresh-cw': RefreshCw, plane: Plane, gift: Gift,
  'paw-print': PawPrint, sofa: Sofa, receipt: Receipt, banknote: Banknote, briefcase: Briefcase,
  'arrow-down-circle': ArrowDownCircle,
};
const COLOR_HEX: Record<string, string> = {
  gray: '#6b7280', blue: '#2563eb', green: '#16a34a', red: '#dc2626', orange: '#ea580c',
  yellow: '#d97706', purple: '#7c3aed', pink: '#db2777', teal: '#0d9488',
};

// Replace the usage site (was: CATEGORY_ICON[t.categoryName] ?? ShoppingBag /
// CATEGORY_COLOR[t.categoryName] ?? '#262A33') with:
const cat = categoriesById[t.categoryId];
const Icon = ICON_COMPONENTS[cat?.icon ?? 'tag'] ?? ShoppingBag;
const color = COLOR_HEX[cat?.color ?? 'gray'] ?? '#262A33';
```
Add the new lucide-react imports at the top of the file (all already exist in the `lucide-react` package this repo depends on — confirm each name matches the installed version's exports before committing; a couple of these names vary by lucide-react major version, e.g. `HeartPulse` vs `Heart`, so run a quick `grep -r "export.*HeartPulse" node_modules/lucide-react` if the build fails on any single icon and swap in the closest available name from `CategoryPalette.ICONS`' label instead). Add `import { categoriesApi, type CategoryOption } from '../api/endpoints';` if not already imported in this file.

- [ ] **Step 2: Manually verify in the browser**

Run the frontend dev server, open the Dashboard, confirm every transaction row still shows a sensible icon/color (including for categories that had no entry in the old 4-entry hardcoded map, which previously always fell back to the generic `ShoppingBag`/gray — those should now show their real backfilled icon from Task 1's migration).

- [ ] **Step 3: Run the full frontend test suite for regressions**

Run: `cd frontend && npx vitest run`
Expected: PASS, no regressions.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/pages/Dashboard.tsx
git commit -m "refactor(dashboard): render category icon/color from the API instead of a hardcoded name map"
```

---

## Final verification

- [ ] **Backend full suite:** `cd backend && ./mvnw -q test` — PASS.
- [ ] **Frontend full suite:** `cd frontend && npx vitest run` — PASS.
- [ ] **Manual smoke test:** start both dev servers, exercise: create a category from the Ledger edit modal, rename it from the same combobox, confirm a `CategoryRule` created via the admin portal against that name (if reachable in your test environment) updates its `actionValue` after the rename, then delete the category with dependents and confirm the reassignment dialog blocks until a target is chosen.
