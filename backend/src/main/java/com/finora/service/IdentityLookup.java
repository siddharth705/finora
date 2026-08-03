package com.finora.service;

import com.finora.entity.User;
import com.finora.repository.UserRepository;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;

/**
 * The one door into "who is this?", for every identity field, always scoped.
 *
 * Email and phone are the same problem wearing different clothes. Since V52 either one identifies
 * an account only WITHIN a portal scope -- the same person may hold a USER account and an ADMIN
 * account under one email and one mobile number -- so a lookup that omits the scope is ambiguous,
 * and the failure is silent: it authenticates, it just authenticates the wrong account.
 *
 * Having a single symmetrical entry point matters more than the deduplication. Identity resolution
 * used to live inline in {@code AuthService.resolveEmailForLogin}, which meant the phone-variant
 * fallbacks below existed in exactly one caller's head; any second flow that needed to resolve a
 * typed identifier would have re-derived them, and would have re-derived them slightly differently.
 */
@Component
public class IdentityLookup {

    private final UserRepository userRepository;

    public IdentityLookup(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Resolves whatever the user typed -- an email or a phone number -- to their account within one
     * portal scope.
     *
     * Users should not have to remember which one they signed up with, so a single field accepts
     * either. Returns empty rather than throwing when nothing matches: every caller already handles
     * "no such account" the same way it handles a wrong password, which is what keeps this from
     * becoming an account-enumeration oracle.
     */
    public Optional<User> byIdentifier(String identifier, String scope) {
        if (identifier == null || identifier.isBlank()) return Optional.empty();
        return identifier.contains("@")
                ? byEmail(identifier, scope)
                : byPhoneNumber(identifier, scope);
    }

    /**
     * Fails closed to "no match" when two rows differ only by letter case.
     *
     * Case-insensitive email uniqueness is enforced by V52's functional index now, but rows created
     * before it may still differ only in case. Without this guard the underlying single-result
     * query throws, turning a login or a forgot-password into a 500 instead of the ordinary
     * account-not-found path every caller already handles -- and a 500 on one specific address is
     * itself an enumeration signal.
     */
    public Optional<User> byEmail(String email, String scope) {
        try {
            return userRepository.findByEmailIgnoreCaseAndAccountScope(email.trim(), scope);
        } catch (IncorrectResultSizeDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * Tries the number as typed, then the variants a real account may be stored under.
     *
     * Registration normalises to E.164 now, but accounts created before that still carry whatever
     * was originally typed -- so a user who registered with a bare ten-digit number (stored
     * "+919876500000") and later types those same ten digits must still reach their own account.
     * Every variant is scoped; none of them can reach across into the other portal's account.
     */
    public Optional<User> byPhoneNumber(String identifier, String scope) {
        String digitsOnly = identifier.replaceAll("[^0-9]", "");
        return userRepository.findByPhoneNumberAndAccountScope(identifier, scope)
                .or(() -> userRepository.findByPhoneNumberAndAccountScope("+" + digitsOnly, scope))
                .or(() -> userRepository.findByPhoneNumberAndAccountScope(digitsOnly, scope))
                .or(() -> userRepository.findByPhoneNumberAndAccountScope(normalizePhoneNumber(identifier), scope));
    }

    /** True when this identifier is already taken within the scope -- the registration check, for
     *  either field, in one place. */
    public boolean isTaken(String email, String phoneNumber, String scope) {
        return userRepository.existsByEmailIgnoreCaseAndAccountScope(email, scope)
                || userRepository.existsByPhoneNumberAndAccountScope(phoneNumber, scope);
    }

    /** Which portal a client is acting in. Anything unrecognised -- including absent -- is USER, so
     *  a client that has not been updated behaves exactly as it did before scopes existed, and a
     *  value the server never defined cannot name a scope into existence. */
    public static String scopeOrDefault(String rawScope) {
        return User.SCOPE_ADMIN.equalsIgnoreCase(rawScope) ? User.SCOPE_ADMIN : User.SCOPE_USER;
    }

    /**
     * Bare Indian mobile numbers are stored E.164. Kept here alongside the lookups that depend on
     * it, so the normalisation used when WRITING a number and the variants tried when READING one
     * cannot drift apart.
     */
    public static String normalizePhoneNumber(String raw) {
        if (raw == null || raw.isBlank()) return raw;
        String digits = raw.replaceAll("[^0-9]", "");
        if (raw.trim().startsWith("+")) return "+" + digits;
        if (digits.length() == 10) return "+91" + digits;
        return "+" + digits;
    }

    private static String lower(String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }
}
