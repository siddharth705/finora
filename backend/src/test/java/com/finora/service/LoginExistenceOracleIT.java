package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.dto.AuthDtos.LoginRequest;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * BH-014's regression suite. Five wrong passwords used to turn any email address into an
 * account-existence oracle on an unauthenticated, public endpoint; these prove it no longer does,
 * and that the lockout it leaked through is still fully enforced.
 *
 * <h2>The three outcomes, and which one differs</h2>
 *
 * <pre>
 *   wrong password, account exists      -> 401  "Invalid credentials"
 *   wrong password, no such account     -> 401  "Invalid credentials"
 *   locked account                      -> 423  "This account is temporarily locked..."
 * </pre>
 *
 * <p>The first two are indistinguishable on purpose, and {@code AuthService.login} works hard to
 * keep them that way — {@code resolveEmailForLogin}, {@code findUserByEmailIgnoreCaseSafely} and
 * the {@code "no-such-account"} principal all exist for it. The lockout check undoes that. Submit
 * five wrong passwords: an address that exists starts answering 423, an address that does not
 * answers 401 forever. Registration is confirmed with a handful of requests and no credential.
 *
 * <h2>This is already known, and that is the point</h2>
 *
 * <p>{@code AuthService.login}'s own comment says so — the suspension check was moved below
 * password verification for exactly this reason, and the comment then concedes the lockout check
 * "leaks the same way and is reported rather than papered over here", because "closing it means
 * synthesising lockout state for identifiers that do not exist, which is a design, not an edit".
 *
 * <p>That framing assumed the fix had to make a nonexistent address behave like a locked one. It
 * does not: making a locked account behave like a wrong password is the same indistinguishability
 * from the outside, costs no new state, and keeps the lockout fully enforced. The account still
 * cannot be logged into — the caller simply is not told why.
 *
 * <h2>Status code is not the whole channel</h2>
 *
 * <p>The last test measures elapsed time. The locked path returns BEFORE
 * {@code authenticationManager.authenticate}, so it skips BCrypt entirely, while both 401 paths pay
 * for it. Equalising the status code without noticing that would leave a timing oracle behind a
 * response that looks identical — which is worse than the loud version, because it reads as fixed.
 */
class LoginExistenceOracleIT extends AbstractIntegrationTest {

    @Autowired private AuthService authService;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private PlatformSettingsService platformSettingsService;

    private static final String RIGHT = "correct-horse-battery-staple";
    private static final String WRONG = "definitely-not-the-password";

    private User registered() {
        User user = new User();
        user.setEmail("oracle-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash(passwordEncoder.encode(RIGHT));
        user.setFullName("Oracle Target");
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private HttpStatus attempt(String identifier) {
        ApiException thrown = catchThrowableOfType(
                () -> authService.login(new LoginRequest(identifier, WRONG, null)), ApiException.class);
        assertThat(thrown).as("a wrong password must always fail").isNotNull();
        return thrown.getStatus();
    }

    /** Drives the account to the lockout threshold. */
    private void exhaust(String identifier) {
        int max = platformSettingsService.getEntity().getMaxFailedLoginAttempts();
        for (int i = 0; i < max; i++) attempt(identifier);
    }

    @Test
    @DisplayName("BH-014: after five wrong passwords, an existing address answers differently from a nonexistent one")
    void repeatedFailuresRevealWhetherTheAccountExists() {
        User real = registered();
        String fake = "no-such-user-" + UUID.randomUUID() + "@example.com";

        HttpStatus realBefore = attempt(real.getEmail());
        HttpStatus fakeBefore = attempt(fake);

        exhaust(real.getEmail());
        exhaust(fake);

        HttpStatus realAfter = attempt(real.getEmail());
        HttpStatus fakeAfter = attempt(fake);

        System.out.printf(
                "%nBH-014 reproduction -- login responses to a WRONG password%n"
                + "                         registered address   unregistered address%n"
                + "  first attempt ........ %-20s %s%n"
                + "  after %d failures ..... %-20s %s%n"
                + "  distinguishable? ..... %s%n%n",
                realBefore, fakeBefore,
                platformSettingsService.getEntity().getMaxFailedLoginAttempts(), realAfter, fakeAfter,
                realAfter.equals(fakeAfter) ? "no -- indistinguishable" : "YES -- ORACLE STILL OPEN");

        assertThat(realBefore)
                .as("before the threshold the two were always indistinguishable")
                .isEqualTo(fakeBefore);

        // THE assertion. Before the fix realAfter was 423 and fakeAfter was 401, and the difference
        // was exactly "this address is registered".
        assertThat(realAfter)
                .as("after the threshold a registered address must still answer exactly as an "
                        + "unregistered one -- this is the oracle, and it is the whole finding")
                .isEqualTo(fakeAfter);
        assertThat(realAfter)
                .as("and both answer the same 401 a wrong password always did")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("BH-014: the lockout is still fully enforced -- the correct password is refused while locked")
    void theLockoutItselfIsUnweakened() {
        User real = registered();
        exhaust(real.getEmail());

        // The other half of the fix, and the one a careless implementation breaks: it would be
        // trivial to remove the oracle by removing the lockout. The account must still be
        // unenterable, with the RIGHT password, for as long as the lock stands.
        ApiException withCorrectPassword = catchThrowableOfType(
                () -> authService.login(new LoginRequest(real.getEmail(), RIGHT, null)), ApiException.class);

        assertThat(withCorrectPassword)
                .as("a locked account must refuse even the correct password -- otherwise the "
                        + "lockout is decorative and this 'fix' is a regression")
                .isNotNull();
        assertThat(withCorrectPassword.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);

        User reloaded = userRepository.findById(real.getId()).orElseThrow();
        assertThat(reloaded.getLockedUntil())
                .as("the lock state itself is untouched -- only what we SAY about it changed")
                .isNotNull();
        assertThat(reloaded.getFailedLoginAttempts())
                .as("and the counter that produced it still stands")
                .isGreaterThanOrEqualTo(platformSettingsService.getEntity().getMaxFailedLoginAttempts());
    }

    @Test
    @DisplayName("BH-014: the response body must not name the reason either")
    void theMessageIsAlsoIndistinguishable() {
        User real = registered();
        String fake = "no-such-user-" + UUID.randomUUID() + "@example.com";
        exhaust(real.getEmail());

        // Status parity is not enough on its own -- "This account is temporarily locked" in the
        // body would leak just as loudly to anyone reading the response instead of its code.
        ApiException locked = catchThrowableOfType(
                () -> authService.login(new LoginRequest(real.getEmail(), WRONG, null)), ApiException.class);
        ApiException unknown = catchThrowableOfType(
                () -> authService.login(new LoginRequest(fake, WRONG, null)), ApiException.class);

        assertThat(locked.getMessage())
                .as("same words, not merely the same status code")
                .isEqualTo(unknown.getMessage());
        assertThat(locked.getMessage()).doesNotContainIgnoringCase("lock");
    }

    @Test
    @DisplayName("BH-014: the locked path also returns faster, because it skips BCrypt entirely")
    void theLockedPathIsMeasurablyCheaperThanAPasswordCheck() {
        User real = registered();
        String fake = "no-such-user-" + UUID.randomUUID() + "@example.com";
        exhaust(real.getEmail());

        // Warm both paths before measuring -- the first call through either pays for class loading
        // and JIT, which would swamp the difference being measured.
        for (int i = 0; i < 3; i++) { attempt(real.getEmail()); attempt(fake); }

        long lockedNanos = 0, unauthorizedNanos = 0;
        int rounds = 5;
        for (int i = 0; i < rounds; i++) {
            long t0 = System.nanoTime();
            attempt(real.getEmail());
            lockedNanos += System.nanoTime() - t0;

            t0 = System.nanoTime();
            attempt(fake);
            unauthorizedNanos += System.nanoTime() - t0;
        }
        long lockedMs = lockedNanos / rounds / 1_000_000;
        long unauthorizedMs = unauthorizedNanos / rounds / 1_000_000;

        System.out.printf(
                "BH-014 timing side-channel (mean of %d, warmed)%n"
                + "  locked account (423, skips BCrypt) ...... %d ms%n"
                + "  unknown account (401, pays for BCrypt) .. %d ms%n"
                + "  Status and body are now identical; this gap is NOT closed. See the PR question.%n%n",
                rounds, lockedMs, unauthorizedMs);

        // Deliberately NOT asserted as a hard threshold -- that would be a machine-speed assertion
        // and would flake on a loaded CI runner. The number is printed so the fix can be judged
        // against it, and so nobody claims the oracle is closed on the strength of the status code
        // alone. BCrypt is intentionally slow; skipping it is intentionally fast; the gap is
        // structural, not incidental.
        assertThat(lockedMs).as("recorded, not bounded").isGreaterThanOrEqualTo(0);
    }
}
