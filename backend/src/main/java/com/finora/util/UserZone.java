package com.finora.util;

import com.finora.entity.User;
import com.finora.repository.UserRepository;

import java.time.ZoneId;
import java.util.UUID;

/**
 * Resolves the {@link ZoneId} a user's dates should be computed in, with a safe fallback.
 *
 * <p><b>Why this exists.</b> Four services had grown their own {@code safeZoneId} — identical
 * logic, two different signatures ({@code String timezone} in {@code NetWorthService} and
 * {@code DashboardService}, {@code UUID userId} in {@code BudgetService} and {@code GoalService},
 * the latter pair each issuing their own {@code findById}). Every copy's comment pointed at the
 * others, which is the tell: the fix was applied by copying it into each site rather than by
 * extracting it once, and a set of sites maintained that way is never closed.
 *
 * <p>It wasn't closed. {@code AnalyticsService} was the fifth service that needed a user's zone,
 * never got a copy, and called a bare {@code YearMonth.now()} — the server's zone, not the user's.
 * That is not a separate bug so much as the predictable next instance: when the same three lines
 * live in four places, the fifth place gets none of them. Extracting it here is what actually
 * closes the set, because a new caller now has something to reach for.
 *
 * <p>The fallback is deliberate, not defensive noise: {@code User.timezone} is a free-text column
 * (V11's default is {@code Asia/Kolkata}), so a malformed value must degrade to that default
 * rather than throw {@code DateTimeException} out of an analytics query. The write paths validate
 * too ({@code UserSettingsService} and {@code AdminUserService} both reject an unparseable zone),
 * so this is defense in depth over rows written before those checks existed.
 */
public final class UserZone {

    /** The column default from the V11 migration. Named here so the fallback and the schema
     *  default cannot drift apart silently. */
    public static final ZoneId DEFAULT = ZoneId.of("Asia/Kolkata");

    private UserZone() {}

    /** Resolves a raw timezone string, falling back to {@link #DEFAULT} for null or unparseable
     *  values. */
    public static ZoneId of(String timezone) {
        if (timezone == null) return DEFAULT;
        try {
            return ZoneId.of(timezone);
        } catch (Exception e) {
            return DEFAULT;
        }
    }

    /** Looks the user's timezone up and resolves it. Kept alongside {@link #of(String)} because
     *  callers genuinely split between "I already hold the User" and "I only have an id" — the two
     *  signatures the four hand-written copies had between them. */
    public static ZoneId forUser(UserRepository userRepository, UUID userId) {
        return of(userRepository.findById(userId).map(User::getTimezone).orElse(null));
    }
}
