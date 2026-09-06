package com.finora.config;

import com.finora.support.ClientIdentity;
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
        // setAllowedOriginPatterns, not setAllowedOrigins: Cloudflare Pages mints a distinct
        // subdomain per branch preview (e.g. finora-<branch>.finora-cng.pages.dev), so an
        // exact-match list can never cover "the next PR's preview" -- only a pattern like
        // https://*.finora-cng.pages.dev can. Patterns are safe alongside allowCredentials(true)
        // below (unlike the literal wildcard "*", which Spring rejects when credentials are
        // allowed); each entry here can still be a plain exact origin with no "*" in it.
        config.setAllowedOriginPatterns(origins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        // BH-036. CorrelationIdFilter reuses the client's X-Request-Id when one is supplied and
        // echoes it back on every response, but CORS listed it in neither allowedHeaders nor
        // exposedHeaders -- so from a browser the feature could not work in either direction. A
        // cross-origin request carrying the header fails preflight, and the response header is
        // invisible to JavaScript.
        //
        // Latent rather than broken today, because no client sends it yet. It is the kind of thing
        // discovered when someone adds client-side tracing and loses an afternoon to a preflight
        // error that has nothing to do with their change.
        //
        // The client-identity pair is the case that comment predicted, and it arrived the moment a
        // client actually started sending a custom header. This app is deployed cross-origin
        // (static frontend on Cloudflare, backend on Railway), so every request the web app makes
        // is preflighted -- omitting these two would not have broken support ticket metadata, it
        // would have failed EVERY request the browser sends, on a change that looks like it only
        // touches support.
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", CorrelationIdFilter.HEADER_NAME,
                ClientIdentity.PLATFORM_HEADER, ClientIdentity.VERSION_HEADER));
        // Exposed as well as allowed: allowedHeaders governs what the browser may SEND, exposed
        // governs what JavaScript may READ off the response. Correlating a client-side error report
        // with a server log needs the latter.
        config.setExposedHeaders(List.of(CorrelationIdFilter.HEADER_NAME));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
