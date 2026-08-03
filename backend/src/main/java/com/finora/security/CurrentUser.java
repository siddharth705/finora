package com.finora.security;

import com.finora.repository.UserRepository;
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

    private final UserRepository userRepository;

    public CurrentUser(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * The authenticated user's id.
     *
     * The principal's "username" IS the id (see {@link CurrentUserDetailsService}), so this is a
     * parse rather than a lookup. It used to resolve the email to a row on every single call --
     * one database query per authenticated request, for a value the principal already carried,
     * and ambiguous since V52 made an email unique only within a portal scope.
     */
    public UUID id() {
        UserDetails principal = (UserDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        try {
            return UUID.fromString(principal.getUsername());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Authenticated principal is not a user id", e);
        }
    }
}
