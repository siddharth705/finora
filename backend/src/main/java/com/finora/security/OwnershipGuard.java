package com.finora.security;

import com.finora.exception.ApiException;
import org.springframework.http.HttpStatus;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * The single implementation of "this row belongs to the calling user" -- the check that separates
 * a multi-tenant app from a data breach.
 *
 * <p>Reusable security rule, not a point patch. Before this existed, every service carried its own
 * private {@code getOwned()} copy of the same six lines: {@code AccountService},
 * {@code TransactionService} (twice), {@code GoalService}, {@code RuleService},
 * {@code StatementImportService}, {@code RelationshipService} (twice), {@code ImportService},
 * {@code ImportSessionService}. TransactionService's copy even carried a comment explaining why it
 * was duplicating AccountService's. That is precisely the shape of code that produces an IDOR: the
 * check is a convention rather than a mechanism, so a new write path only has to *forget* to call
 * its local helper -- which is exactly what {@code TransactionService.create()} did, accepting any
 * caller-supplied {@code accountId} and letting any user post transactions onto a stranger's
 * account.
 *
 * <p>Consolidating gives one place to audit, one place to fix, and one place to reason about the
 * two properties that actually matter:
 *
 * <ul>
 *   <li><b>Not-found and forbidden stay distinct.</b> A missing row is 404, someone else's row is
 *       403. This deliberately does not collapse both into 404: every service in this codebase
 *       already made that distinction, admin tooling depends on it, and the ids here are
 *       unguessable v4 UUIDs, so the enumeration risk that motivates 404-for-everything does not
 *       apply.</li>
 *   <li><b>It fails closed.</b> A null owner id, a null caller id, or a mismatch all deny. There
 *       is no input to this method that results in "allow by accident" -- see the explicit null
 *       handling in {@link #requireOwnedBy}, which is the whole reason ownership comparison lives
 *       here rather than being open-coded as {@code entity.getUserId().equals(userId)} (an
 *       expression that throws rather than denies when the owner id is null).</li>
 * </ul>
 *
 * <p>Enforced by {@code OwnershipGuardUsageTest}, which fails the build if a service reintroduces
 * a hand-rolled ownership comparison instead of calling this.
 */
public final class OwnershipGuard {

    private OwnershipGuard() {}

    /**
     * Fetch-and-verify, the common case: {@code requireOwned(repo.findById(id), Thing::getUserId,
     * userId, "Thing")}.
     *
     * @param found       the repository lookup result, empty if no such row exists
     * @param ownerIdOf   extracts the owning user id from the entity
     * @param userId      the calling user
     * @param entityLabel capitalized singular noun used in both messages, e.g. {@code "Account"},
     *                    {@code "Statement import"}
     * @throws ApiException 404 if absent, 403 if owned by anyone other than {@code userId}
     */
    public static <T> T requireOwned(Optional<T> found, Function<T, UUID> ownerIdOf,
                                     UUID userId, String entityLabel) {
        T entity = found.orElseThrow(
                () -> new ApiException(HttpStatus.NOT_FOUND, entityLabel + " not found"));
        return requireOwnedBy(entity, ownerIdOf, userId, entityLabel);
    }

    /**
     * Verify only -- for callers that already hold the entity, or that must interleave another
     * check between the lookup and the ownership test (e.g. {@code RuleService} rejecting GLOBAL
     * rules before considering ownership at all).
     *
     * @throws ApiException 403 unless the entity is owned by exactly {@code userId}
     */
    public static <T> T requireOwnedBy(T entity, Function<T, UUID> ownerIdOf,
                                       UUID userId, String entityLabel) {
        UUID ownerId = ownerIdOf.apply(entity);
        // Fail closed on every degenerate input. Written as an explicit null check rather than
        // ownerId.equals(userId) (which throws on a null owner) or userId.equals(ownerId) (which
        // throws on an unauthenticated caller): a 500 from an NPE is a worse answer than a 403,
        // and neither null may ever be treated as a match.
        if (ownerId == null || userId == null || !ownerId.equals(userId)) {
            throw new ApiException(HttpStatus.FORBIDDEN,
                    "This " + possessiveLabel(entityLabel) + " does not belong to you");
        }
        return entity;
    }

    /** {@code "Statement import"} -> {@code "statement import"}, for mid-sentence use. */
    private static String possessiveLabel(String entityLabel) {
        if (entityLabel == null || entityLabel.isEmpty()) return "record";
        return Character.toLowerCase(entityLabel.charAt(0)) + entityLabel.substring(1);
    }
}
