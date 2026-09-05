# Held-Item Admin Email Alerts Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Email every admin holding the relevant permission the moment a statement lands in either
admin triage queue (parser-gap `HELD_FOR_REVIEW`, or trust-review `HELD_FOR_TRUST_REVIEW`) — a
pointer into the admin portal, not a channel for statement content.

**Architecture:** One new service (`HeldItemAdminAlertService`) resolves recipients via a new
permission-based `UserRepository` query and sends via `EmailProvider.send(EmailMessage)` directly
— the same pattern `AuthService` already uses for transactional email, deliberately bypassing
`NotificationService` (built for one end-user's own channel preferences, the wrong shape for an
always-on ops alert to a permission-holding group). Two trigger points, one in
`ImportJobWorker.recordFailure` and one in `HeldStatementService.openHold`, each call the new
service wrapped in the existing `AfterCommit.run(...)` utility so the email only fires once the
hold row is durably committed, without holding a pooled DB connection across the network call.

**Tech Stack:** Spring Boot, Spring Data JPA, Mockito (unit tests), `AbstractIntegrationTest` (real
Postgres, for the one repository-level test).

**Spec:** `docs/superpowers/specs/2026-09-05-held-item-admin-email-alerts-design.md`

## Global Constraints

- No new tables or columns — this rides entirely on the existing `permissions`/`role_permissions`/
  `user_roles`/`roles`/`users` graph and the existing `ImportJob`/`HeldStatement` rows (spec §5).
- Email content is metadata + a link only — never recovered text, transaction rows, account
  numbers, or any other customer content in the email body (spec §3, §4.5). Every user-supplied or
  parser-derived string placed in the HTML body must be HTML-escaped.
- One email per recipient per hold **occurrence** (not per job) — a reprocessed-then-held-again
  parser-gap job is a new event and sends a new round of emails (spec §4.4).
- A failed or missing email send must never propagate to the caller and must never affect the
  import pipeline's own success — log and continue (spec §4.2).
- No digest/batching, no SMS/push channel for this alert, no per-admin opt-out — all explicitly out
  of scope (spec §6).

---

### Task 1: Permission-based recipient resolution

**Files:**
- Modify: `backend/src/main/java/com/finora/repository/UserRepository.java` (add one method, end
  of file, after the existing `findByIdInAndStatus` method at the closing brace)
- Test: `backend/src/test/java/com/finora/repository/UserRepositoryIT.java` (add tests to the
  existing file)

**Interfaces:**
- Produces: `UserRepository.findByPermissionNameAndAccountScope(String permissionName, String
  accountScope)` → `List<User>` — every user in `accountScope` whose roles collectively grant the
  named permission, de-duplicated. Consumed by Task 2.

- [ ] **Step 1: Write the failing tests**

Add to `backend/src/test/java/com/finora/repository/UserRepositoryIT.java`, inside the
`UserRepositoryIT` class (after the existing test methods, before the closing brace). Add these
imports at the top of the file alongside the existing ones:

```java
import com.finora.entity.Role;
```

Add this helper method and these two tests:

```java
    /** Assigns a real seeded role (ADMIN/SUPER_ADMIN, both carrying real permissions via V16/
     *  V31/V135/V144) to a fresh admin-scope user — exercises the real permission graph rather
     *  than a hand-built fixture, so a change to which roles carry a permission is caught here. */
    private User saveAdminWithRole(RoleRepository roleRepository, String roleName) {
        User user = new User();
        user.setEmail("user-repository-it-perm-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("User Repository IT Permission Test User");
        user.setAccountScope(User.SCOPE_ADMIN);
        user.setStatus(User.STATUS_ACTIVE);
        user.setPhoneVerified(true);
        Role role = roleRepository.findByName(roleName).orElseThrow();
        user.getRoles().add(role);
        return userRepository.save(user);
    }

    @Test
    @Transactional
    void findByPermissionNameAndAccountScope_returnsAdminsWhoseRoleGrantsIt(
            @org.springframework.beans.factory.annotation.Autowired RoleRepository roleRepository) {
        User grantedByAdmin = saveAdminWithRole(roleRepository, "ADMIN");
        User grantedBySuperAdmin = saveAdminWithRole(roleRepository, "SUPER_ADMIN");

        List<User> recipients =
                userRepository.findByPermissionNameAndAccountScope("IMPORT_TRIAGE_MANAGE", User.SCOPE_ADMIN);

        assertThat(recipients).extracting(User::getId)
                .contains(grantedByAdmin.getId(), grantedBySuperAdmin.getId());
    }

    @Test
    @Transactional
    void findByPermissionNameAndAccountScope_excludesAUserWithNoRoleGrantingIt(
            @org.springframework.beans.factory.annotation.Autowired RoleRepository roleRepository) {
        // A real admin account, but with no role assigned at all -- the RBAC-empty case, distinct
        // from "assigned a role that doesn't happen to carry this permission" (there is no such
        // role in the seeded set today, so the empty-roles case is the one worth pinning).
        User unrelatedAdmin = new User();
        unrelatedAdmin.setEmail("user-repository-it-perm-none-" + UUID.randomUUID() + "@example.com");
        unrelatedAdmin.setPasswordHash("irrelevant-for-this-test");
        unrelatedAdmin.setFullName("User Repository IT No-Permission Test User");
        unrelatedAdmin.setAccountScope(User.SCOPE_ADMIN);
        unrelatedAdmin.setStatus(User.STATUS_ACTIVE);
        unrelatedAdmin.setPhoneVerified(true);
        userRepository.save(unrelatedAdmin);

        List<User> recipients =
                userRepository.findByPermissionNameAndAccountScope("IMPORT_TRIAGE_MANAGE", User.SCOPE_ADMIN);

        assertThat(recipients).extracting(User::getId).doesNotContain(unrelatedAdmin.getId());
    }

    @Test
    @Transactional
    void findByPermissionNameAndAccountScope_isScopedToTheGivenAccountScope(
            @org.springframework.beans.factory.annotation.Autowired RoleRepository roleRepository) {
        // A USER-scope account holding the same role name is not an admin-portal account and must
        // never be resolved as an alert recipient, however its roles happen to be configured.
        User userScopeAccount = new User();
        userScopeAccount.setEmail("user-repository-it-perm-userscope-" + UUID.randomUUID() + "@example.com");
        userScopeAccount.setPasswordHash("irrelevant-for-this-test");
        userScopeAccount.setFullName("User Repository IT User-Scope Test User");
        userScopeAccount.setAccountScope(User.SCOPE_USER);
        userScopeAccount.setStatus(User.STATUS_ACTIVE);
        userScopeAccount.setPhoneVerified(true);
        Role role = roleRepository.findByName("ADMIN").orElseThrow();
        userScopeAccount.getRoles().add(role);
        userRepository.save(userScopeAccount);

        List<User> recipients =
                userRepository.findByPermissionNameAndAccountScope("IMPORT_TRIAGE_MANAGE", User.SCOPE_ADMIN);

        assertThat(recipients).extracting(User::getId).doesNotContain(userScopeAccount.getId());
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd backend && ./mvnw -Dtest=UserRepositoryIT test`
Expected: compile error — `findByPermissionNameAndAccountScope` does not exist on `UserRepository`.

- [ ] **Step 3: Add the repository method**

In `backend/src/main/java/com/finora/repository/UserRepository.java`, add before the final closing
brace (after `findByIdInAndStatus`):

```java

    // --- HeldItemAdminAlertService ---

    /**
     * Every admin-scope user whose roles collectively grant the named permission — resolved live
     * from the RBAC graph {@code AuthorizationService} already reads on every authenticated
     * request, not a configured mailing list. {@code DISTINCT} because a user with two roles that
     * both carry the same permission (e.g. ADMIN and a future custom role) must be emailed once,
     * not twice.
     */
    @Query("""
           SELECT DISTINCT u FROM User u JOIN u.roles r JOIN r.permissions p
           WHERE p.name = :permissionName AND u.accountScope = :accountScope
           """)
    List<User> findByPermissionNameAndAccountScope(@Param("permissionName") String permissionName,
                                                    @Param("accountScope") String accountScope);
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd backend && ./mvnw -Dtest=UserRepositoryIT test`
Expected: PASS — all `UserRepositoryIT` tests green, including the three new ones.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/finora/repository/UserRepository.java backend/src/test/java/com/finora/repository/UserRepositoryIT.java
git commit -m "feat(repository): resolve admins by permission for held-item alerts"
```

---

### Task 2: `HeldItemAdminAlertService` — parser-gap alert

**Files:**
- Create: `backend/src/main/java/com/finora/service/HeldItemAdminAlertService.java`
- Test: `backend/src/test/java/com/finora/service/HeldItemAdminAlertServiceTest.java`

**Interfaces:**
- Consumes: `UserRepository.findByPermissionNameAndAccountScope(String, String)` → `List<User>`
  (Task 1); `ImportJobRepository.findById(UUID)` → `Optional<ImportJob>` (existing);
  `EmailProvider.send(EmailMessage)` → `EmailResult` (existing); `EmailMessage.html(String to,
  String subject, String html)` (existing static factory); `EmailProperties.getAdminAppBaseUrl()`
  → `String` (existing, nullable); `User.getEmail()` → `String`, `User.SCOPE_ADMIN` (existing);
  `ImportJob.getFileName()`, `.getId()`, `.getLastError()`, `.getFinishedAt()` (existing).
- Produces: `HeldItemAdminAlertService.alertParserGapHeld(UUID jobId)` — consumed by Task 4.
  `HeldItemAdminAlertService` constructor: `(UserRepository, ImportJobRepository,
  HeldStatementRepository, EmailProvider, EmailProperties)` — consumed by Task 3 (same class, adds
  a method) and Task 4/5 (dependency injection).

- [ ] **Step 1: Write the failing tests**

Create `backend/src/test/java/com/finora/service/HeldItemAdminAlertServiceTest.java`:

```java
package com.finora.service;

import com.finora.config.EmailProperties;
import com.finora.entity.ImportJob;
import com.finora.entity.User;
import com.finora.exception.ErrorCode;
import com.finora.repository.HeldStatementRepository;
import com.finora.repository.ImportJobRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HeldItemAdminAlertServiceTest {

    private UserRepository userRepository;
    private ImportJobRepository importJobRepository;
    private HeldStatementRepository heldStatementRepository;
    private EmailProvider emailProvider;
    private EmailProperties emailProperties;
    private HeldItemAdminAlertService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        importJobRepository = mock(ImportJobRepository.class);
        heldStatementRepository = mock(HeldStatementRepository.class);
        emailProvider = mock(EmailProvider.class);
        emailProperties = mock(EmailProperties.class);
        when(emailProperties.getAdminAppBaseUrl()).thenReturn("https://admin.example.com");
        when(emailProvider.send(any())).thenReturn(EmailResult.success(ProviderType.RESEND, "msg-1"));

        service = new HeldItemAdminAlertService(userRepository, importJobRepository,
                heldStatementRepository, emailProvider, emailProperties);
    }

    private User adminUser(String email) {
        User user = new User();
        org.springframework.test.util.ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        user.setEmail(email);
        user.setAccountScope(User.SCOPE_ADMIN);
        return user;
    }

    /** Mirrors what {@code ImportJobWorker.recordFailure} actually does: {@code recordFailure}
     *  first (this is what populates {@code lastError}), then {@code holdForReview} -- not
     *  {@code holdForReview} alone, which never touches {@code lastError}. */
    private ImportJob heldJob() {
        ImportJob job = new ImportJob(UUID.randomUUID(), "Paytm_Statement_January_2026.pdf",
                "hash", "objects/key", "PDF");
        job.markClaimed("worker", Instant.now());
        job.recordFailure("Finora could not find a transaction table anywhere in this statement.",
                "IMPORT_NO_HEADER_DETECTED", ErrorCode.RetryPolicy.FAIL_FAST, Instant.now());
        job.holdForReview("IMPORT_NO_HEADER_DETECTED", Instant.now());
        return job;
    }

    @Test
    void alertParserGapHeld_emailsEveryAdminHoldingImportTriageManage() {
        ImportJob job = heldJob();
        when(importJobRepository.findById(job.getId())).thenReturn(Optional.of(job));
        User admin1 = adminUser("triage-admin-1@example.com");
        User admin2 = adminUser("triage-admin-2@example.com");
        when(userRepository.findByPermissionNameAndAccountScope("IMPORT_TRIAGE_MANAGE", User.SCOPE_ADMIN))
                .thenReturn(List.of(admin1, admin2));

        service.alertParserGapHeld(job.getId());

        verify(emailProvider).send(argThatEmailTo("triage-admin-1@example.com"));
        verify(emailProvider).send(argThatEmailTo("triage-admin-2@example.com"));
    }

    @Test
    void alertParserGapHeld_includesTheFileNameAndAnAdminPortalLink() {
        ImportJob job = heldJob();
        when(importJobRepository.findById(job.getId())).thenReturn(Optional.of(job));
        when(userRepository.findByPermissionNameAndAccountScope(any(), any()))
                .thenReturn(List.of(adminUser("triage-admin@example.com")));

        service.alertParserGapHeld(job.getId());

        org.mockito.ArgumentCaptor<EmailMessage> captor = org.mockito.ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailProvider).send(captor.capture());
        EmailMessage sent = captor.getValue();
        assertThat(sent.html()).contains("Paytm_Statement_January_2026.pdf");
        assertThat(sent.html()).contains("Finora could not find a transaction table anywhere in this statement.");
        assertThat(sent.html()).contains("https://admin.example.com/held-imports");
    }

    @Test
    void alertParserGapHeld_sendsNothingWhenNoAdminHoldsThePermission() {
        ImportJob job = heldJob();
        when(importJobRepository.findById(job.getId())).thenReturn(Optional.of(job));
        when(userRepository.findByPermissionNameAndAccountScope(any(), any())).thenReturn(List.of());

        service.alertParserGapHeld(job.getId());

        verify(emailProvider, never()).send(any());
    }

    @Test
    void alertParserGapHeld_sendsNothingWhenTheJobNoLongerExists() {
        UUID jobId = UUID.randomUUID();
        when(importJobRepository.findById(jobId)).thenReturn(Optional.empty());

        service.alertParserGapHeld(jobId);

        verify(userRepository, never()).findByPermissionNameAndAccountScope(any(), any());
        verify(emailProvider, never()).send(any());
    }

    @Test
    void alertParserGapHeld_oneRecipientsFailureDoesNotStopTheOthers() {
        ImportJob job = heldJob();
        when(importJobRepository.findById(job.getId())).thenReturn(Optional.of(job));
        User failing = adminUser("bounces@example.com");
        User succeeding = adminUser("triage-admin@example.com");
        when(userRepository.findByPermissionNameAndAccountScope(any(), any()))
                .thenReturn(List.of(failing, succeeding));
        when(emailProvider.send(argThatEmailTo("bounces@example.com")))
                .thenReturn(EmailResult.failure(ProviderType.RESEND, "mailbox does not exist"));

        service.alertParserGapHeld(job.getId());

        verify(emailProvider).send(argThatEmailTo("triage-admin@example.com"));
    }

    private static EmailMessage argThatEmailTo(String email) {
        return org.mockito.ArgumentMatchers.argThat(m -> m != null && email.equals(m.to()));
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd backend && ./mvnw -Dtest=HeldItemAdminAlertServiceTest test`
Expected: compile error — `HeldItemAdminAlertService` does not exist.

- [ ] **Step 3: Write the implementation**

Create `backend/src/main/java/com/finora/service/HeldItemAdminAlertService.java`:

```java
package com.finora.service;

import com.finora.config.EmailProperties;
import com.finora.entity.ImportJob;
import com.finora.entity.User;
import com.finora.repository.HeldStatementRepository;
import com.finora.repository.ImportJobRepository;
import com.finora.repository.UserRepository;
import com.finora.util.EmailMasking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Emails every admin holding the relevant permission the moment a statement lands in one of the
 * two triage queues -- a pointer into the admin portal, never a channel for statement content (see
 * {@code docs/superpowers/specs/2026-09-05-held-item-admin-email-alerts-design.md}).
 *
 * <p>Deliberately bypasses {@link NotificationService}: that system is built entirely around one
 * end-user's own channel preferences per {@code NotificationCategory}, which has no shape for
 * "every user holding permission X, unconditionally." Sends directly via {@link EmailProvider},
 * the same pattern {@code AuthService} already uses for its own transactional emails.
 *
 * <p>Never throws. A failed or missing email is logged and the caller continues -- the import
 * pipeline's success can never depend on email deliverability, the same rule every other
 * side-effecting call in this pipeline already follows.
 */
@Service
public class HeldItemAdminAlertService {

    private static final Logger log = LoggerFactory.getLogger(HeldItemAdminAlertService.class);

    private static final String IMPORT_TRIAGE_MANAGE = "IMPORT_TRIAGE_MANAGE";
    private static final String TRUST_REVIEW_MANAGE = "TRUST_REVIEW_MANAGE";

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm 'UTC'", Locale.ENGLISH).withZone(ZoneOffset.UTC);

    private final UserRepository userRepository;
    private final ImportJobRepository importJobRepository;
    private final HeldStatementRepository heldStatementRepository;
    private final EmailProvider emailProvider;
    private final EmailProperties emailProperties;

    public HeldItemAdminAlertService(UserRepository userRepository, ImportJobRepository importJobRepository,
                                      HeldStatementRepository heldStatementRepository, EmailProvider emailProvider,
                                      EmailProperties emailProperties) {
        this.userRepository = userRepository;
        this.importJobRepository = importJobRepository;
        this.heldStatementRepository = heldStatementRepository;
        this.emailProvider = emailProvider;
        this.emailProperties = emailProperties;
    }

    /**
     * A parser-gap hold ({@code ImportJob.Status.HELD_FOR_REVIEW}) was just created. Re-reads the
     * job fresh (rather than being handed the entity) so this is safe to call from
     * {@code AfterCommit.run(...)}, which fires after the transaction that created the hold has
     * committed -- a fresh read at that point is guaranteed to see it.
     */
    public void alertParserGapHeld(UUID jobId) {
        Optional<ImportJob> found = importJobRepository.findById(jobId);
        if (found.isEmpty()) {
            log.warn("Could not send a held-item admin alert for import job {}: job no longer exists", jobId);
            return;
        }
        ImportJob job = found.get();
        String subject = "Statement held for review — " + job.getFileName();
        String html = "<p>A statement failed to import and was held for admin review.</p>"
                + "<ul>"
                + "<li><strong>File:</strong> " + escape(job.getFileName()) + "</li>"
                + "<li><strong>Job ID:</strong> " + job.getId() + "</li>"
                + "<li><strong>Reason:</strong> " + escape(job.getLastError()) + "</li>"
                + "<li><strong>Held at:</strong> " + escape(TIMESTAMP_FORMAT.format(job.getFinishedAt())) + "</li>"
                + "</ul>"
                + "<p><a href=\"" + adminBaseUrl() + "/held-imports\">Open the held-imports queue</a></p>";
        sendToRecipients(IMPORT_TRIAGE_MANAGE, subject, html);
    }

    private String adminBaseUrl() {
        String base = emailProperties.getAdminAppBaseUrl();
        return base == null ? "" : base;
    }

    private void sendToRecipients(String permissionName, String subject, String html) {
        List<User> recipients =
                userRepository.findByPermissionNameAndAccountScope(permissionName, User.SCOPE_ADMIN);
        if (recipients.isEmpty()) {
            log.info("No admin holds {} -- no held-item alert sent for \"{}\"", permissionName, subject);
            return;
        }
        for (User recipient : recipients) {
            try {
                EmailResult result = emailProvider.send(EmailMessage.html(recipient.getEmail(), subject, html));
                if (!result.success()) {
                    log.warn("Held-item admin alert to {} failed: {}",
                            EmailMasking.mask(recipient.getEmail()), result.failureReason());
                }
            } catch (RuntimeException e) {
                log.warn("Held-item admin alert to {} threw", EmailMasking.mask(recipient.getEmail()), e);
            }
        }
    }

    /** Escapes the handful of characters that matter in an HTML email body. The strings placed
     *  here are curated, already user-safe messages ({@code ExtractionCheck}'s own error text,
     *  {@code HoldDecision.summary()}) -- not raw customer input -- but a filename IS attacker-
     *  chosen (see {@code StatementUpload}'s own doc comment), so this costs nothing and removes
     *  any doubt for every field, not just that one. */
    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
```

Add the missing `Optional` import: `import java.util.Optional;` (alongside the other `java.util.*`
imports at the top).

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd backend && ./mvnw -Dtest=HeldItemAdminAlertServiceTest test`
Expected: PASS — all `HeldItemAdminAlertServiceTest` tests green.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/finora/service/HeldItemAdminAlertService.java backend/src/test/java/com/finora/service/HeldItemAdminAlertServiceTest.java
git commit -m "feat(service): admin email alert for parser-gap held imports"
```

---

### Task 3: `HeldItemAdminAlertService` — trust-review alert

**Files:**
- Modify: `backend/src/main/java/com/finora/service/HeldItemAdminAlertService.java` (add one
  method)
- Test: `backend/src/test/java/com/finora/service/HeldItemAdminAlertServiceTest.java` (add tests)

**Interfaces:**
- Consumes: `HeldStatementRepository.findByHeldId(String)` → `Optional<HeldStatement>` (existing);
  `HeldStatement.getHeldId()`, `.getBankName()`, `.getTriggerSummary()`, `.getCreatedAt()`
  (existing).
- Produces: `HeldItemAdminAlertService.alertTrustReviewHeld(String heldId)` — consumed by Task 5.

- [ ] **Step 1: Write the failing tests**

Add to `backend/src/test/java/com/finora/service/HeldItemAdminAlertServiceTest.java`. Add these
imports:

```java
import com.finora.entity.HeldStatement;
```

Add this helper and these tests, inside the class:

```java
    private HeldStatement heldStatement(String heldId, String bankName) {
        HeldStatement held = new HeldStatement(heldId, UUID.randomUUID(), UUID.randomUUID(),
                "objects/key", "Statement period ends before it starts");
        held.recordBank(bankName);
        return held;
    }

    @Test
    void alertTrustReviewHeld_emailsEveryAdminHoldingTrustReviewManage() {
        HeldStatement held = heldStatement("HELD-00001", "HDFC Bank");
        when(heldStatementRepository.findByHeldId("HELD-00001")).thenReturn(Optional.of(held));
        User admin = adminUser("trust-review-admin@example.com");
        when(userRepository.findByPermissionNameAndAccountScope("TRUST_REVIEW_MANAGE", User.SCOPE_ADMIN))
                .thenReturn(List.of(admin));

        service.alertTrustReviewHeld("HELD-00001");

        verify(emailProvider).send(argThatEmailTo("trust-review-admin@example.com"));
    }

    @Test
    void alertTrustReviewHeld_includesTheHeldIdReasonAndAnAdminPortalLink() {
        HeldStatement held = heldStatement("HELD-00002", "HDFC Bank");
        when(heldStatementRepository.findByHeldId("HELD-00002")).thenReturn(Optional.of(held));
        when(userRepository.findByPermissionNameAndAccountScope(any(), any()))
                .thenReturn(List.of(adminUser("trust-review-admin@example.com")));

        service.alertTrustReviewHeld("HELD-00002");

        org.mockito.ArgumentCaptor<EmailMessage> captor = org.mockito.ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailProvider).send(captor.capture());
        EmailMessage sent = captor.getValue();
        assertThat(sent.html()).contains("HELD-00002");
        assertThat(sent.html()).contains("Statement period ends before it starts");
        assertThat(sent.html()).contains("https://admin.example.com/held-statements/HELD-00002");
    }

    @Test
    void alertTrustReviewHeld_omitsTheBankLineWhenNoBankWasDetected() {
        HeldStatement held = heldStatement("HELD-00003", null);
        when(heldStatementRepository.findByHeldId("HELD-00003")).thenReturn(Optional.of(held));
        when(userRepository.findByPermissionNameAndAccountScope(any(), any()))
                .thenReturn(List.of(adminUser("trust-review-admin@example.com")));

        service.alertTrustReviewHeld("HELD-00003");

        org.mockito.ArgumentCaptor<EmailMessage> captor = org.mockito.ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailProvider).send(captor.capture());
        assertThat(captor.getValue().html()).doesNotContain("<strong>Bank:</strong>");
    }

    @Test
    void alertTrustReviewHeld_sendsNothingWhenTheHeldStatementNoLongerExists() {
        when(heldStatementRepository.findByHeldId("HELD-00004")).thenReturn(Optional.empty());

        service.alertTrustReviewHeld("HELD-00004");

        verify(userRepository, never()).findByPermissionNameAndAccountScope(any(), any());
        verify(emailProvider, never()).send(any());
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd backend && ./mvnw -Dtest=HeldItemAdminAlertServiceTest test`
Expected: compile error — `alertTrustReviewHeld` does not exist on `HeldItemAdminAlertService`.

- [ ] **Step 3: Add the method**

In `backend/src/main/java/com/finora/service/HeldItemAdminAlertService.java`, add after
`alertParserGapHeld`:

```java

    /**
     * A trust-review hold ({@code ImportJob.Status.HELD_FOR_TRUST_REVIEW}) was just created.
     * Re-reads the {@link HeldStatement} fresh by its human-readable id -- same "safe to call from
     * {@code AfterCommit}" reasoning as {@link #alertParserGapHeld}, and {@code heldId} is also
     * exactly what the admin portal's own detail route ({@code /held-statements/:heldId}) already
     * uses, so no separate lookup is needed to build the link.
     */
    public void alertTrustReviewHeld(String heldId) {
        Optional<com.finora.entity.HeldStatement> found = heldStatementRepository.findByHeldId(heldId);
        if (found.isEmpty()) {
            log.warn("Could not send a held-item admin alert for held statement {}: it no longer exists", heldId);
            return;
        }
        com.finora.entity.HeldStatement held = found.get();
        String bankLine = held.getBankName() == null || held.getBankName().isBlank()
                ? "" : "<li><strong>Bank:</strong> " + escape(held.getBankName()) + "</li>";
        String subject = "Statement held for trust review — " + held.getHeldId();
        String html = "<p>A statement's extraction was not trusted enough to reach the user's ledger "
                + "unreviewed.</p>"
                + "<ul>"
                + "<li><strong>Held ID:</strong> " + escape(held.getHeldId()) + "</li>"
                + bankLine
                + "<li><strong>Reason:</strong> " + escape(held.getTriggerSummary()) + "</li>"
                + "<li><strong>Held at:</strong> " + escape(TIMESTAMP_FORMAT.format(held.getCreatedAt())) + "</li>"
                + "</ul>"
                + "<p><a href=\"" + adminBaseUrl() + "/held-statements/" + held.getHeldId()
                + "\">Open this held statement</a></p>";
        sendToRecipients(TRUST_REVIEW_MANAGE, subject, html);
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd backend && ./mvnw -Dtest=HeldItemAdminAlertServiceTest test`
Expected: PASS — all `HeldItemAdminAlertServiceTest` tests green, including the four new ones.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/finora/service/HeldItemAdminAlertService.java backend/src/test/java/com/finora/service/HeldItemAdminAlertServiceTest.java
git commit -m "feat(service): admin email alert for trust-review held statements"
```

---

### Task 4: Wire the parser-gap trigger point

**Files:**
- Modify: `backend/src/main/java/com/finora/imports/jobs/ImportJobWorker.java`
- Test: `backend/src/test/java/com/finora/imports/jobs/ImportJobWorkerTest.java`

**Interfaces:**
- Consumes: `HeldItemAdminAlertService.alertParserGapHeld(UUID)` (Task 2);
  `AfterCommit.run(String, Runnable)` (existing, `com.finora.util.AfterCommit`).

- [ ] **Step 1: Write the failing tests**

In `backend/src/test/java/com/finora/imports/jobs/ImportJobWorkerTest.java`:

Add this import alongside the existing ones:

```java
import com.finora.service.HeldItemAdminAlertService;
```

Add `import static org.mockito.Mockito.times;` to the existing static imports.

In `setUp()`, add a new mock field/init and pass it into the `ImportJobWorker` constructor. Change:

```java
    private com.finora.service.HeldStatementService heldStatementService;
    private ImportJobWorker worker;
```

to:

```java
    private com.finora.service.HeldStatementService heldStatementService;
    private HeldItemAdminAlertService heldItemAdminAlertService;
    private ImportJobWorker worker;
```

and change:

```java
        heldStatementService = mock(com.finora.service.HeldStatementService.class);

        worker = new ImportJobWorker(jobStore, importService, statementContentService, observability,
                stageRecorder, new ExceptionClassifier(), notificationService, verificationRecorder,
                heldStatementService, new ParserVersionProvider());
```

to:

```java
        heldStatementService = mock(com.finora.service.HeldStatementService.class);
        heldItemAdminAlertService = mock(HeldItemAdminAlertService.class);

        worker = new ImportJobWorker(jobStore, importService, statementContentService, observability,
                stageRecorder, new ExceptionClassifier(), notificationService, verificationRecorder,
                heldStatementService, new ParserVersionProvider(), heldItemAdminAlertService);
```

Add these tests, placed near `aHeldJobKeepsTheFailureCodeThatCausedTheHold` (after it):

```java
    /** The email fires exactly on the same transition {@link
     *  com.finora.entity.ImportJob#holdForReview} makes, mirroring
     *  {@code aHeldJobKeepsTheFailureCodeThatCausedTheHold}'s own setup. */
    @Test
    void aHeldJobTriggersTheAdminAlert() throws IOException {
        when(importService.parseAndStageWithSession(any(), any(), any()))
                .thenThrow(new IllegalStateException("no header row found"));

        worker.drainOnce();
        runAnotherPass();

        verify(heldItemAdminAlertService).alertParserGapHeld(job.getId());
    }

    /** The negative case {@code aKnownErrorCodeFailureIsNotHeld} already proves the job stays
     *  FAILED; this proves the alert follows the same rule -- no hold, no alert. */
    @Test
    void aKnownErrorCodeFailureDoesNotTriggerTheAdminAlert() throws IOException {
        when(importService.parseAndStageWithSession(any(), any(), any()))
                .thenThrow(new ApiException(ErrorCode.IMPORT_NO_HEADER_DETECTED));

        worker.drainOnce();

        verify(heldItemAdminAlertService, org.mockito.Mockito.never()).alertParserGapHeld(any());
    }

    /** A curated failure carrying recovered-lines evidence still enters HELD_FOR_REVIEW (see
     *  {@code aKnownErrorCodeFailureWithRecoveredEvidenceIsHeldForReview}) and must still alert --
     *  the alert trigger reads the job's final status, not which of the two paths produced it. */
    @Test
    void aKnownErrorCodeFailureWithRecoveredEvidenceTriggersTheAdminAlert() throws IOException {
        when(importService.parseAndStageWithSession(any(), any(), any()))
                .thenThrow(new ApiException(ErrorCode.IMPORT_NO_HEADER_DETECTED.defaultStatus(),
                        ErrorCode.IMPORT_NO_HEADER_DETECTED,
                        "Finora could not find a transaction table anywhere in this statement.",
                        java.util.Map.of("recoveredLines", 4)));

        worker.drainOnce();

        verify(heldItemAdminAlertService).alertParserGapHeld(job.getId());
    }

    /** A reprocessed job that fails the same way again is a NEW hold occurrence and sends its own
     *  alert -- "the fix didn't work" is exactly as actionable as the first failure. */
    @Test
    void aReprocessedJobHeldAgainTriggersASecondAdminAlert() throws IOException {
        when(importService.parseAndStageWithSession(any(), any(), any()))
                .thenThrow(new IllegalStateException("no header row found"));

        worker.drainOnce();
        runAnotherPass();
        verify(heldItemAdminAlertService, times(1)).alertParserGapHeld(job.getId());

        // Same mechanics AdminHeldImportController.reprocess uses: reset to QUEUED, attempt
        // budget restored, then the same unrecognised failure happens again.
        job.returnToQueueForReprocess(Instant.now());
        runAnotherPass();
        runAnotherPass();

        verify(heldItemAdminAlertService, times(2)).alertParserGapHeld(job.getId());
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd backend && ./mvnw -Dtest=ImportJobWorkerTest test`
Expected: compile error — `ImportJobWorker`'s constructor does not accept an 11th argument, and
`HeldItemAdminAlertService` is not yet a dependency of the class under test.

- [ ] **Step 3: Wire the trigger point**

In `backend/src/main/java/com/finora/imports/jobs/ImportJobWorker.java`:

Add these imports alongside the existing ones:

```java
import com.finora.service.HeldItemAdminAlertService;
import com.finora.util.AfterCommit;
```

Add a new field and constructor parameter. Change:

```java
    private final com.finora.service.HeldStatementService heldStatementService;
    private final ParserVersionProvider parserVersionProvider;
```

to:

```java
    private final com.finora.service.HeldStatementService heldStatementService;
    private final ParserVersionProvider parserVersionProvider;
    private final HeldItemAdminAlertService heldItemAdminAlertService;
```

Change the constructor signature and body. Change:

```java
    public ImportJobWorker(ImportJobStore jobStore,
                            ImportService importService,
                            StatementContentService statementContentService,
                            WorkerObservability observability,
                            ImportStageRecorder stageRecorder,
                            ExceptionClassifier exceptionClassifier,
                            NotificationService notificationService,
                            ImportVerificationRecorder verificationRecorder,
                            com.finora.service.HeldStatementService heldStatementService,
                            ParserVersionProvider parserVersionProvider) {
        this.jobStore = jobStore;
        this.importService = importService;
        this.statementContentService = statementContentService;
        this.observability = observability;
        this.stageRecorder = stageRecorder;
        this.exceptionClassifier = exceptionClassifier;
        this.notificationService = notificationService;
        this.verificationRecorder = verificationRecorder;
        this.heldStatementService = heldStatementService;
        this.parserVersionProvider = parserVersionProvider;
```

to:

```java
    public ImportJobWorker(ImportJobStore jobStore,
                            ImportService importService,
                            StatementContentService statementContentService,
                            WorkerObservability observability,
                            ImportStageRecorder stageRecorder,
                            ExceptionClassifier exceptionClassifier,
                            NotificationService notificationService,
                            ImportVerificationRecorder verificationRecorder,
                            com.finora.service.HeldStatementService heldStatementService,
                            ParserVersionProvider parserVersionProvider,
                            HeldItemAdminAlertService heldItemAdminAlertService) {
        this.jobStore = jobStore;
        this.importService = importService;
        this.statementContentService = statementContentService;
        this.observability = observability;
        this.stageRecorder = stageRecorder;
        this.exceptionClassifier = exceptionClassifier;
        this.notificationService = notificationService;
        this.verificationRecorder = verificationRecorder;
        this.heldStatementService = heldStatementService;
        this.parserVersionProvider = parserVersionProvider;
        this.heldItemAdminAlertService = heldItemAdminAlertService;
```

In `recordFailure`, change:

```java
            ImportJob.FailureOutcome[] outcome = {ImportJob.FailureOutcome.RETRY_SCHEDULED};
            int[] attempts = {0};
            jobStore.update(jobId, job -> {
                outcome[0] = job.recordFailure(describe(cause), failureCode, policy, Instant.now());
                attempts[0] = job.getAttemptCount();
                // A dead-lettered unclassified failure is the one case that is plausibly a genuine
                // parser gap rather than a user error or an infrastructure blip. Hold it for triage
                // instead of handing the user a bare FAILED they can do nothing about.
                //
                // Inside the update lambda, deliberately: this is where the managed entity is, and
                // the surrounding REQUIRES_NEW transaction is what persists it. Mutating the job
                // after this block returns would change a detached object and write nothing.
                //
                // outcome[0] keeps its DEAD_LETTERED value, so the switch below still fires the
                // alert. Holding for triage adds a destination; it does not replace engineering
                // visibility.
                if (holdsForTriage(policy, outcome[0], cause)) {
                    job.holdForReview(failureCode, Instant.now());
                }
            });
```

to:

```java
            ImportJob.FailureOutcome[] outcome = {ImportJob.FailureOutcome.RETRY_SCHEDULED};
            int[] attempts = {0};
            boolean[] enteredTriageReview = {false};
            jobStore.update(jobId, job -> {
                outcome[0] = job.recordFailure(describe(cause), failureCode, policy, Instant.now());
                attempts[0] = job.getAttemptCount();
                // A dead-lettered unclassified failure is the one case that is plausibly a genuine
                // parser gap rather than a user error or an infrastructure blip. Hold it for triage
                // instead of handing the user a bare FAILED they can do nothing about.
                //
                // Inside the update lambda, deliberately: this is where the managed entity is, and
                // the surrounding REQUIRES_NEW transaction is what persists it. Mutating the job
                // after this block returns would change a detached object and write nothing.
                //
                // outcome[0] keeps its DEAD_LETTERED value, so the switch below still fires the
                // alert. Holding for triage adds a destination; it does not replace engineering
                // visibility.
                if (holdsForTriage(policy, outcome[0], cause)) {
                    job.holdForReview(failureCode, Instant.now());
                    enteredTriageReview[0] = true;
                }
            });
            // Outside jobStore.update's REQUIRES_NEW transaction, deliberately: this is a real
            // network call (an email send), and AfterCommit is what keeps it from holding a
            // pooled DB connection across that call, or from firing for a hold that then rolled
            // back. Re-reads the job fresh (see HeldItemAdminAlertService.alertParserGapHeld's own
            // doc comment) rather than passing the entity through -- the same reason
            // notifyIfPreviouslyHeld's notification key is derived from the job id, not the object.
            if (enteredTriageReview[0]) {
                AfterCommit.run("held-item admin alert (parser gap)",
                        () -> heldItemAdminAlertService.alertParserGapHeld(jobId));
            }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd backend && ./mvnw -Dtest=ImportJobWorkerTest test`
Expected: PASS — all `ImportJobWorkerTest` tests green, including the four new ones.

- [ ] **Step 5: Check every other construction site of `ImportJobWorker` compiles**

Run: `cd backend && ./mvnw -q -DskipTests compile`
Expected: BUILD SUCCESS. `ImportJobWorker` is a `@Component` — Spring wires the new constructor
argument automatically at runtime since `HeldItemAdminAlertService` is itself a `@Service` bean
(Task 2); no other file references this constructor directly (confirmed: `ImportJobWorkerTest` was
the only direct `new ImportJobWorker(...)` call site in the codebase). If this step fails because
another call site does exist, add the new argument there the same way.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/finora/imports/jobs/ImportJobWorker.java backend/src/test/java/com/finora/imports/jobs/ImportJobWorkerTest.java
git commit -m "feat(imports): alert admins when a statement enters the parser-gap queue"
```

---

### Task 5: Wire the trust-review trigger point

**Files:**
- Modify: `backend/src/main/java/com/finora/service/HeldStatementService.java`
- Create: `backend/src/test/java/com/finora/service/HeldStatementServiceTest.java`

**Interfaces:**
- Consumes: `HeldItemAdminAlertService.alertTrustReviewHeld(String)` (Task 3);
  `AfterCommit.run(String, Runnable)` (existing).

- [ ] **Step 1: Write the failing tests**

Create `backend/src/test/java/com/finora/service/HeldStatementServiceTest.java`. This is the first
direct unit test of `HeldStatementService` — every dependency is mocked, matching
`ImportJobWorkerTest`'s established style, rather than the Spring-context IT style
`HeldStatementServiceRerunIT` uses for its own (different) methods.

```java
package com.finora.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.dto.ImportDto;
import com.finora.entity.HeldStatement;
import com.finora.entity.ImportJob;
import com.finora.imports.ImportService;
import com.finora.imports.ImportSessionService;
import com.finora.imports.analysis.ImportVerificationFindingRepository;
import com.finora.imports.jobs.ParserVersionProvider;
import com.finora.imports.jobs.StagedForJob;
import com.finora.imports.storage.StatementContentService;
import com.finora.imports.trust.HeldStatementIdGenerator;
import com.finora.imports.trust.HoldDecision;
import com.finora.imports.trust.TrustPredicate;
import com.finora.notification.api.NotificationService;
import com.finora.repository.HeldStatementEventRepository;
import com.finora.repository.HeldStatementRepository;
import com.finora.repository.ImportJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HeldStatementServiceTest {

    private HeldStatementRepository repository;
    private HeldStatementEventRepository eventRepository;
    private HeldStatementIdGenerator idGenerator;
    private ImportJobRepository importJobRepository;
    private HeldItemAdminAlertService heldItemAdminAlertService;
    private HeldStatementService service;

    @BeforeEach
    void setUp() {
        repository = mock(HeldStatementRepository.class);
        eventRepository = mock(HeldStatementEventRepository.class);
        idGenerator = mock(HeldStatementIdGenerator.class);
        importJobRepository = mock(ImportJobRepository.class);
        ImportVerificationFindingRepository findingRepository = mock(ImportVerificationFindingRepository.class);
        AuditService auditService = mock(AuditService.class);
        NotificationService notificationService = mock(NotificationService.class);
        ImportSessionService importSessionService = mock(ImportSessionService.class);
        StatementContentService statementContentService = mock(StatementContentService.class);
        ImportService importService = mock(ImportService.class);
        ParserVersionProvider parserVersionProvider = mock(ParserVersionProvider.class);
        heldItemAdminAlertService = mock(HeldItemAdminAlertService.class);

        when(idGenerator.next()).thenReturn("HELD-00099");
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(repository.findByImportJobId(any())).thenReturn(Optional.empty());

        service = new HeldStatementService(repository, eventRepository, idGenerator, importJobRepository,
                findingRepository, auditService, notificationService, importSessionService,
                new ObjectMapper(), statementContentService, importService, parserVersionProvider,
                heldItemAdminAlertService);
    }

    private ImportJob job() {
        ImportJob job = new ImportJob(UUID.randomUUID(), "statement.csv", "hash", "objects/key", "CSV");
        return job;
    }

    private HoldDecision periodIntegrityDecision() {
        return new HoldDecision(true, List.of("Statement period ends before it starts"),
                List.of(TrustPredicate.Category.PERIOD_INTEGRITY));
    }

    @Test
    void createHold_triggersTheAdminAlertWithTheNewHoldsId() {
        ImportJob job = job();
        StagedForJob staged = new StagedForJob(UUID.randomUUID(), 5, 5, "HDFC Bank", List.of(), List.of());

        service.createHold(job, staged, periodIntegrityDecision(), "abc123");

        verify(heldItemAdminAlertService).alertTrustReviewHeld("HELD-00099");
    }

    /** {@code createHold} is idempotent on the job id (its own doc comment) -- a second call for
     *  the same job must not open a second hold, and therefore must not send a second alert. */
    @Test
    void createHold_doesNotAlertASecondTime_whenAHoldAlreadyExistsForThisJob() {
        ImportJob job = job();
        HeldStatement existing = new HeldStatement("HELD-00001", job.getId(), job.getUserId(),
                job.getObjectKey(), "already held");
        when(repository.findByImportJobId(job.getId())).thenReturn(Optional.of(existing));
        StagedForJob staged = new StagedForJob(UUID.randomUUID(), 5, 5, "HDFC Bank", List.of(), List.of());

        service.createHold(job, staged, periodIntegrityDecision(), "abc123");

        verify(heldItemAdminAlertService, org.mockito.Mockito.never()).alertTrustReviewHeld(any());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && ./mvnw -Dtest=HeldStatementServiceTest test`
Expected: compile error — `HeldStatementService`'s constructor does not accept a 13th argument.

- [ ] **Step 3: Wire the trigger point**

In `backend/src/main/java/com/finora/service/HeldStatementService.java`:

Add these imports alongside the existing ones:

```java
import com.finora.util.AfterCommit;
```

(`HeldItemAdminAlertService` is in the same `com.finora.service` package, so it needs no import.)

Add a new field and constructor parameter. Change:

```java
    private final ImportService importService;
    private final ParserVersionProvider parserVersionProvider;

    public HeldStatementService(HeldStatementRepository repository,
                                HeldStatementEventRepository eventRepository,
                                HeldStatementIdGenerator idGenerator,
                                ImportJobRepository importJobRepository,
                                ImportVerificationFindingRepository findingRepository,
                                AuditService auditService,
                                NotificationService notificationService,
                                ImportSessionService importSessionService,
                                ObjectMapper objectMapper,
                                StatementContentService statementContentService,
                                ImportService importService,
                                ParserVersionProvider parserVersionProvider) {
        this.repository = repository;
        this.eventRepository = eventRepository;
        this.idGenerator = idGenerator;
        this.importJobRepository = importJobRepository;
        this.findingRepository = findingRepository;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.importSessionService = importSessionService;
        this.objectMapper = objectMapper;
        this.statementContentService = statementContentService;
        this.importService = importService;
        this.parserVersionProvider = parserVersionProvider;
    }
```

to:

```java
    private final ImportService importService;
    private final ParserVersionProvider parserVersionProvider;
    private final HeldItemAdminAlertService heldItemAdminAlertService;

    public HeldStatementService(HeldStatementRepository repository,
                                HeldStatementEventRepository eventRepository,
                                HeldStatementIdGenerator idGenerator,
                                ImportJobRepository importJobRepository,
                                ImportVerificationFindingRepository findingRepository,
                                AuditService auditService,
                                NotificationService notificationService,
                                ImportSessionService importSessionService,
                                ObjectMapper objectMapper,
                                StatementContentService statementContentService,
                                ImportService importService,
                                ParserVersionProvider parserVersionProvider,
                                HeldItemAdminAlertService heldItemAdminAlertService) {
        this.repository = repository;
        this.eventRepository = eventRepository;
        this.idGenerator = idGenerator;
        this.importJobRepository = importJobRepository;
        this.findingRepository = findingRepository;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.importSessionService = importSessionService;
        this.objectMapper = objectMapper;
        this.statementContentService = statementContentService;
        this.importService = importService;
        this.parserVersionProvider = parserVersionProvider;
        this.heldItemAdminAlertService = heldItemAdminAlertService;
    }
```

In `openHold`, change:

```java
        // actorId null: the system opened this, not a person. The reasons are recorded here as
        // well as on the row because the row's summary is editable context for an operator, while
        // the event is the immutable record of what the predicate actually said at hold time.
        eventRepository.save(new HeldStatementEvent(held.getId(), null, "HELD_CREATED",
                null, HeldStatement.Status.HELD.name(), decision.summary()));
        return held;
    }
```

to:

```java
        // actorId null: the system opened this, not a person. The reasons are recorded here as
        // well as on the row because the row's summary is editable context for an operator, while
        // the event is the immutable record of what the predicate actually said at hold time.
        eventRepository.save(new HeldStatementEvent(held.getId(), null, "HELD_CREATED",
                null, HeldStatement.Status.HELD.name(), decision.summary()));
        // Outside this method's own REQUIRES_NEW transaction by the time it actually runs, same
        // reasoning as ImportJobWorker's parser-gap alert: a real network call must not hold a
        // pooled DB connection, and must not fire for a hold that then rolled back. heldId (not
        // the entity) is what HeldItemAdminAlertService.alertTrustReviewHeld re-reads by.
        AfterCommit.run("held-item admin alert (trust review)",
                () -> heldItemAdminAlertService.alertTrustReviewHeld(held.getHeldId()));
        return held;
    }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd backend && ./mvnw -Dtest=HeldStatementServiceTest test`
Expected: PASS — both `HeldStatementServiceTest` tests green.

- [ ] **Step 5: Check the whole backend still compiles and every existing caller of
      `HeldStatementService` still works**

Run: `cd backend && ./mvnw -q -DskipTests compile`
Expected: BUILD SUCCESS. `HeldStatementService` is a `@Service` — Spring wires the new constructor
argument automatically; no other file constructs it directly with `new` (confirmed earlier in this
session: no unit test in the existing suite called `new HeldStatementService(...)` before this
task). If this step fails because another direct construction site exists, add the new argument
there the same way.

- [ ] **Step 6: Run the full backend test suite**

Run: `cd backend && ./mvnw test`
Expected: BUILD SUCCESS, 0 failures. This is the final task — this run is the end-to-end check that
nothing elsewhere in the suite constructed `ImportJobWorker` or `HeldStatementService` directly and
was missed by the two compile-only checks above, and that no other test asserted an exhaustive
constructor-argument count or similar that this change would violate.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/finora/service/HeldStatementService.java backend/src/test/java/com/finora/service/HeldStatementServiceTest.java
git commit -m "feat(service): alert admins when a statement enters trust review"
```

---

## Self-Review Notes

- **Spec coverage:** §4.1 recipient resolution → Task 1. §4.2 alert service (both queues) → Tasks
  2–3. §4.3 trigger points → Tasks 4–5. §4.4 idempotency (per-occurrence, reprocess sends again) →
  Task 4's `aReprocessedJobHeldAgainTriggersASecondAdminAlert` test. §4.5 content (metadata + link,
  no statement data) → Tasks 2–3's HTML-building code and their content-assertion tests. §7's
  scope-widening note (any path into `HELD_FOR_REVIEW` alerts, regardless of which classification
  produced it) → Task 4's `aKnownErrorCodeFailureWithRecoveredEvidenceTriggersTheAdminAlert` test.
  §6 out-of-scope items (digest, SMS/push, opt-out, new tables) are not touched by any task.
- **Deviation from spec §4.2 point 3:** the spec described "a new purpose-built `EmailProvider`
  method." Investigating `EmailProvider.send(EmailMessage)` (already generic and fully sufficient —
  confirmed by reading `ResendEmailProvider.send`) showed a new interface method would need
  implementing in both `ResendEmailProvider` and `NoOpEmailProvider` for no real benefit, since no
  other caller needs a purpose-built name for this specific email type. This plan has
  `HeldItemAdminAlertService` build the `EmailMessage` itself and call the existing generic `send`
  directly — simpler, touches two fewer files, and `EmailProvider`/its implementations are
  unmodified by this plan entirely.
