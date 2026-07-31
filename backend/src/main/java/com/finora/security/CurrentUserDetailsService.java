package com.finora.security;

import com.finora.entity.User;
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

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("No user with email " + email));

        // Authorities now come from AuthorizationService (docs/engineering-directive-phase1.md,
        // Priority 2) rather than a single hardcoded "ROLE_" + user.getRole() -- see that class
        // for why this is additive-only relative to the previous behavior.
        return org.springframework.security.core.userdetails.User
                .withUsername(user.getEmail())
                .password(user.getPasswordHash())
                .authorities(authorizationService.effectiveAuthorities(user))
                .build();
    }
}
