package com.finora.security;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Every controller resolves "which user am I acting on behalf of" through this,
 * rather than trusting a userId in the request body/path — that's what makes the
 * JWT the actual source of tenant identity instead of something a client could spoof.
 */
@Component
public class CurrentUser {

    /**
     * The authenticated user's id.
     *
     * The principal's "username" IS the id (see {@link CurrentUserDetailsService}), so this is a
     * parse rather than a lookup. It used to resolve the email to a row on every single call --
     * one database query per authenticated request, for a value the principal already carried,
     * and ambiguous since V52 made an email unique only within a portal scope.
     */
    public UUID id() {
        // Bug 50 (docs/quality/bug-reports/BUG_REVIEW_REPORT.md). Used to cast the principal to
        // UserDetails unconditionally, with no null check on getAuthentication() and no
        // instanceof check on the principal. SecurityConfig's anyRequest().authenticated() makes
        // an anonymous call unreachable today (an unauthenticated request never gets this far),
        // but that's a property of the CURRENT authorization rules, not of this method -- an
        // anonymous request's principal is the literal String "anonymousUser" (or the
        // Authentication itself is null), and either one used to throw a bare
        // ClassCastException/NullPointerException that GlobalExceptionHandler's catch-all turned
        // into a 500 INTERNAL_ERROR with an ERROR log line, one `permitAll` matcher away from
        // being reachable, instead of the clean 401 an unauthenticated caller should see.
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication != null && authentication.getPrincipal() instanceof UserDetails principal)) {
            throw new BadCredentialsException("No authenticated user in the current request");
        }
        try {
            return UUID.fromString(principal.getUsername());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Authenticated principal is not a user id", e);
        }
    }

    /**
     * Whether the calling principal carries a given permission — for the small number of endpoints
     * that serve two audiences at once (a ticket's owner, or any admin) rather than being gated
     * entirely by a class-level {@code @PreAuthorize}. {@code SupportTicketService}'s attachment
     * download and ticket detail are the first callers: the same route re-checks "is this yours" or
     * "can you see any ticket" per request, exactly the posture
     * {@code docs/proposals/support-help-feedback-proposal.md} §3.6 describes for attachments.
     *
     * <p>Returns {@code false} rather than throwing when there is no authentication in context —
     * this answers "can they" for a caller already known to exist, not "who is calling".
     */
    public boolean hasAuthority(String authority) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return false;
        }
        return authentication.getAuthorities().stream()
                .anyMatch(granted -> granted.getAuthority().equals(authority));
    }
}
