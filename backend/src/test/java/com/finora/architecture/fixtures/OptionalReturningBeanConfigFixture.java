package com.finora.architecture.fixtures;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Optional;

/**
 * Deliberately broken fixture -- NOT production code, and never registered with Spring (it lives
 * in test sources, which component scanning never reaches).
 *
 * <p>Reproduces the exact shape of the real {@code FirebaseConfig} bug: a {@code @Bean} method
 * declared to return {@code Optional<String>}. Spring registers a bean of type
 * {@code Optional<String>} for it, but any OTHER bean requesting {@code Optional<String>} via
 * constructor/field injection never receives it -- Spring's dependency resolution special-cases
 * any {@code Optional<X>} injection point to mean "optionally autowire a plain bean of type X",
 * so it looks for a bean of raw type {@code String} instead and silently injects
 * {@link Optional#empty()}, regardless of what this method actually returned. This is what made
 * {@code FirebasePhoneVerificationProvider.isConfigured()} always report "not configured" even
 * with valid Firebase credentials.
 *
 * <p>Its only purpose is to prove {@link com.finora.architecture.NoOptionalBeanReturnTypeTest}
 * actually detects that shape. Do not "fix" this class by changing its return type -- the broken
 * signature is the test input.
 */
@Configuration
public class OptionalReturningBeanConfigFixture {

    /** The bug shape: a @Bean method whose declared return type is Optional<T>. */
    @Bean
    public Optional<String> brokenBean() {
        return Optional.of("this bean type can never be correctly injected as Optional<String>");
    }

    /** The correct pattern: return the plain type, nullable, so no bean registers on absence. */
    @Bean
    public String correctlyTypedBean() {
        return "a real consumer of this can request Optional<String> and get proper semantics";
    }
}
