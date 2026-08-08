package com.finora.config;

import com.finora.security.JwtAuthFilter;
import com.finora.security.PhoneVerificationFilter;
import com.finora.security.RestAuthenticationEntryPoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfigurationSource;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity // enables @PreAuthorize("hasRole('ADMIN')") etc. on controller/service methods
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final PhoneVerificationFilter phoneVerificationFilter;
    private final UserDetailsService userDetailsService;
    private final CorsConfigurationSource corsConfigurationSource;
    private final RestAuthenticationEntryPoint restAuthenticationEntryPoint;

    /**
     * True outside the prod profile, and it gates the Swagger matcher below.
     *
     * <p>The API docs were {@code permitAll()} in EVERY profile, and the only thing keeping them off
     * the public internet was {@code application-prod.yml} setting {@code springdoc.*.enabled:
     * false}. Two layers that must agree, where only one of them was actually deciding anything.
     *
     * <p>That is a live trap rather than a theoretical one, because the prod config's own comment
     * invites the change that springs it: "Re-enable deliberately (e.g. behind a separate auth-gated
     * route, or only for an internal network) if you need it." Someone taking that invitation flips
     * one boolean, and the endpoint they expected to be auth-gated is served to anonymous callers --
     * because the authorization rule they never looked at already said {@code permitAll}. A full
     * OpenAPI description of a financial API is reconnaissance: every route, every parameter, every
     * DTO field.
     *
     * <p>Gated on the PROFILE rather than on {@code springdoc.api-docs.enabled}, deliberately.
     * Binding to springdoc's own flag would keep the two settings consistent but would preserve the
     * property that enabling docs in prod makes them public, which is the thing worth preventing.
     * With the profile check, turning springdoc back on in prod yields authenticated-only docs --
     * exactly the "auth-gated route" the prod comment asks for, reached by default instead of by
     * remembering.
     */
    private final boolean apiDocsPubliclyReachable;

    /** Package-private and static so {@code SecurityConfigTest} can assert the decision directly.
     *  Standing the whole context up under the prod profile is not a practical way to test this --
     *  ProductionConfigValidator refuses to start one without real secrets and a configured
     *  Firebase credential - and a rule whose failure mode is "silently permits anonymous access"
     *  needs a test that names the profile it must close. Same reasoning, and the same shape, as
     *  {@code ProductionConfigValidator.looksLikePlaceholderSecret}. */
    static boolean apiDocsPubliclyReachable(Environment environment) {
        return !environment.matchesProfiles("prod");
    }

    public SecurityConfig(JwtAuthFilter jwtAuthFilter, PhoneVerificationFilter phoneVerificationFilter,
                           UserDetailsService userDetailsService,
                           CorsConfigurationSource corsConfigurationSource,
                           RestAuthenticationEntryPoint restAuthenticationEntryPoint,
                           Environment environment) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.phoneVerificationFilter = phoneVerificationFilter;
        this.userDetailsService = userDetailsService;
        this.corsConfigurationSource = corsConfigurationSource;
        this.restAuthenticationEntryPoint = restAuthenticationEntryPoint;
        this.apiDocsPubliclyReachable = apiDocsPubliclyReachable(environment);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        // BCrypt — matches the PRD's security requirement directly.
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public DaoAuthenticationProvider authenticationProvider(PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource))
            .csrf(csrf -> csrf.disable()) // stateless JWT API — CSRF protection is a browser-session concern
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> {
                auth.requestMatchers("/api/v1/auth/**").permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/v1/setup/status").permitAll()
                    .requestMatchers("/actuator/health").permitAll();
                // Anonymous Swagger outside prod only -- see apiDocsPubliclyReachable's doc comment.
                // Must be registered BEFORE anyRequest(): Spring Security evaluates rules in
                // declaration order, and it rejects any requestMatchers() added after anyRequest()
                // outright rather than silently ignoring it.
                if (apiDocsPubliclyReachable) {
                    auth.requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll();
                }
                auth.anyRequest().authenticated();
            })
            // Without this, Spring Security's default entry point answers every unauthenticated
            // request (missing/expired/invalid JWT) with 403, not 401 — which breaks the
            // frontend's silent-refresh-on-401 logic. See RestAuthenticationEntryPoint's own
            // doc comment for the full explanation.
            .exceptionHandling(ex -> ex.authenticationEntryPoint(restAuthenticationEntryPoint))
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
            // Must run strictly after JwtAuthFilter -- it depends on the authenticated
            // principal JwtAuthFilter populates in the security context.
            .addFilterAfter(phoneVerificationFilter, JwtAuthFilter.class)
            // Modern HTTP security headers. Spring Security ships these disabled-by-default
            // or with framework defaults that don't reflect current OWASP guidance for an API
            // serving financial data, so they're set explicitly here.
            .headers(headers -> headers
                .contentSecurityPolicy(csp -> csp.policyDirectives(
                        "default-src 'self'; frame-ancestors 'none'; base-uri 'self'"))
                .httpStrictTransportSecurity(hsts -> hsts
                        .includeSubDomains(true)
                        .maxAgeInSeconds(31536000))
                .frameOptions(frame -> frame.deny())
                .referrerPolicy(referrer -> referrer
                        .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                .permissionsPolicy(permissions -> permissions
                        .policy("geolocation=(), microphone=(), camera=()"))
            );

        return http.build();
    }
}
