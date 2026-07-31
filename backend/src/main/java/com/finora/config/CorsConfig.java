package com.finora.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Exposes a CorsConfigurationSource for SecurityConfig to wire in via .cors(...) — deliberately
 * NOT a standalone CorsFilter bean. A plain @Bean CorsFilter's position relative to Spring
 * Security's own FilterChainProxy is left to Spring Boot's default filter registration order,
 * which is undocumented/implementation-dependent — in practice it can end up running in a spot
 * where it rejects requests that Security's own authorizeHttpRequests rules had already decided
 * to permitAll, which is exactly the "permitAll endpoint still 403s a real browser request but
 * not curl" symptom this was causing (curl doesn't send an Origin header, so it never triggered
 * the CORS rejection path at all — a browser POST always does). Wiring CORS through
 * HttpSecurity.cors(...) instead puts it in the position Spring Security actually expects.
 */
@Configuration
public class CorsConfig {

    @Value("${app.cors.allowed-origins}")
    private String allowedOrigins;

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        // .trim() each entry -- without it, "http://a.com, http://b.com" (a space after the
        // comma, a very natural way to format this property) silently breaks CORS for every
        // origin after the first: CorsConfiguration does an exact string match against the
        // browser's Origin header, which never has a leading space, so " http://b.com" can
        // never match anything a real browser sends.
        List<String> origins = java.util.Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(o -> !o.isEmpty())
                .toList();
        config.setAllowedOrigins(origins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
