package com.finora.architecture;

import com.finora.architecture.registry.GuardianSelfTest;
import com.finora.architecture.registry.GuardianRule;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every identity lookup on {@link UserRepository} must take an account scope.
 *
 * Deleting the unscoped methods fixed today. It does not stop someone adding
 * {@code findByEmail(String)} back next month -- Spring Data will happily implement it from the
 * name alone, no implementation to review, and the call site will look entirely reasonable. The
 * resulting bug is the worst kind: it compiles, it passes every test written against a single
 * account, and in production it authenticates the wrong one of the two accounts a person holds
 * under one email.
 *
 * So the rule is enforced rather than remembered. Email and phone are the same problem wearing
 * different clothes, and both are covered here for the same reason.
 *
 * <p>If you are here because this test failed: you added an identity lookup without a scope. Add
 * {@code AndAccountScope} to the method name and pass the scope from the caller
 * ({@code IdentityLookup.scopeOrDefault} turns a client-supplied value into one safely). If you
 * genuinely need an unscoped query -- an admin listing across both portals, say -- name it so it
 * cannot be mistaken for an identity lookup, e.g. {@code searchAllScopes}.
 */
class ScopedIdentityLookupTest {

    /** Name fragments that mean "this method resolves a human by something they typed". */
    private static final List<String> IDENTITY_FIELDS = List.of("Email", "PhoneNumber", "Identifier");

    private static final String SCOPE_MARKER = "AccountScope";

    /**
     * Methods that legitimately mention an identity field without resolving one.
     *
     * Kept as an explicit list rather than a pattern: an escape hatch that is easy to widen stops
     * being a guard. Anything added here should be obviously not-a-lookup on sight.
     */
    private static final List<String> ALLOWED = List.of(
            // Free-text admin search across name/email/phone. Not an identity resolution -- it
            // returns a page of partial matches for a human to pick from, and is already
            // permission-gated.
            "search",
            // Platform-wide aggregate counts (AdminOperationalDashboardService, AdminStatsService)
            // that exclude exactly one hardcoded system constant, BootstrapService.BOOTSTRAP_IDENTIFIER
            // -- never a client-typed value. That constant isn't shaped like a real email address, so
            // it can't collide with a real account's identity in either portal the way this rule
            // guards against. Adding AndAccountScope here would be wrong, not just unnecessary: these
            // counts are deliberately unscoped across both portals (they've always counted ADMIN- and
            // USER-scope rows together), and scoping them would silently change what "total users"
            // means rather than fix an identity-resolution bug.
            "countByEmailNot",
            "countByStatusAndEmailNot");

    @GuardianRule(
            id = "FG-027",
            category = GuardianRule.Category.SECURITY,
            intent = "Every identity lookup on UserRepository is tenant-scoped.",
            source = "Incident: unscoped identity lookup",
            introduced = "2026-08-03",
            owner = "architecture",
            verification = GuardianRule.Verification.SELF_TEST)
    @Test
    void everyIdentityLookupOnUserRepositoryIsScoped() {
        List<String> unscoped = new ArrayList<>();

        for (Method method : UserRepository.class.getDeclaredMethods()) {
            String name = method.getName();
            if (ALLOWED.contains(name)) continue;

            boolean resolvesIdentity = IDENTITY_FIELDS.stream().anyMatch(name::contains);
            if (resolvesIdentity && !name.contains(SCOPE_MARKER)) {
                unscoped.add(name);
            }
        }

        assertThat(unscoped)
                .as("""
                    These UserRepository methods resolve a user by something they typed, without an \
                    account scope. Since V52 an email and a phone number identify an account only \
                    within a portal, so an unscoped lookup silently picks one of the two accounts a \
                    person may hold under the same address. Add AndAccountScope, or rename the \
                    method so it cannot be mistaken for an identity lookup.""")
                .isEmpty();
    }

    @GuardianSelfTest(rule = "FG-027")
    @Test
    void theScopedLookupsThisRuleProtectsStillExist() {
        // Guards the guard: the check above passes trivially if the methods are renamed or removed,
        // so it would keep passing while silently protecting nothing.
        List<String> names = new ArrayList<>();
        for (Method m : UserRepository.class.getDeclaredMethods()) names.add(m.getName());

        assertThat(names).contains(
                "findByEmailIgnoreCaseAndAccountScope",
                "existsByEmailIgnoreCaseAndAccountScope",
                "findByPhoneNumberAndAccountScope",
                "existsByPhoneNumberAndAccountScope");
    }

    @GuardianSelfTest(rule = "FG-027")
    @Test
    void theRuleActuallyFiresOnAnUnscopedMethod() {
        // A check that cannot be shown to fail is not a control. This reproduces the exact shape of
        // the mistake -- a plausible-looking findByEmail -- against the same logic the real test
        // runs, proving it would be caught rather than waved through.
        List<String> unscoped = new ArrayList<>();
        for (String name : List.of("findByEmail", "existsByPhoneNumber", "findByIdentifier")) {
            if (IDENTITY_FIELDS.stream().anyMatch(name::contains) && !name.contains(SCOPE_MARKER)) {
                unscoped.add(name);
            }
        }

        assertThat(unscoped)
                .as("the rule must reject exactly the methods that were deleted in V52's change")
                .containsExactly("findByEmail", "existsByPhoneNumber", "findByIdentifier");
    }
}
