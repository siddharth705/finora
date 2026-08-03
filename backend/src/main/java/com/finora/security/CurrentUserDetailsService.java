package com.finora.security;

import com.finora.entity.User;
import java.util.UUID;
import com.finora.repository.UserRepository;
import com.finora.service.AuthorizationService;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class CurrentUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;
    private final AuthorizationService authorizationService;

    public CurrentUserDetailsService(UserRepository userRepository, AuthorizationService authorizationService) {
        this.userRepository = userRepository;
        this.authorizationService = authorizationService;
    }

    /**
     * Resolves the principal by USER ID, not by email.
     *
     * The Spring Security "username" for this application is the user's id, and has to be: since
     * V52 an email identifies a user only within a portal scope, so a principal keyed on email is
     * ambiguous the moment one person holds both a USER-scope and an ADMIN-scope account. The id is
     * the identity; the email is a label on it.
     *
     * This costs nothing elsewhere -- the JWT already carries the id as its subject, so every
     * authenticated request already had the unambiguous value available and was choosing the
     * ambiguous one.
     *
     * @param userId the user's UUID in string form (from the JWT subject, or supplied by
     *               {@code AuthService.login} once it has resolved which scoped account is being
     *               authenticated)
     */
    @Override
    public UserDetails loadUserByUsername(String userId) throws UsernameNotFoundException {
        UUID id;
        try {
            id = UUID.fromString(userId);
        } catch (IllegalArgumentException e) {
            // Anything that is not a UUID cannot be a principal here. Reported as "not found"
            // rather than propagating a parse error, so a stale token minted before this change
            // (whose subject was an email) fails as a clean 401 rather than a 500.
            throw new UsernameNotFoundException("Principal is not a user id");
        }

        User user = userRepository.findById(id)
                .orElseThrow(() -> new UsernameNotFoundException("No user with id " + userId));

        // Authorities now come from AuthorizationService (docs/engineering-directive-phase1.md,
        // Priority 2) rather than a single hardcoded "ROLE_" + user.getRole() -- see that class
        // for why this is additive-only relative to the previous behavior.
        return org.springframework.security.core.userdetails.User
                .withUsername(user.getId().toString())
                .password(user.getPasswordHash())
                .authorities(authorizationService.effectiveAuthorities(user))
                .build();
    }
}
