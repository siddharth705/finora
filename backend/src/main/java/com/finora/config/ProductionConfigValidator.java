package com.finora.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * Production-readiness pass: every secret in application.yml has a local-dev-convenience default
 * ({@code ${JWT_SECRET:change-this-to-a-long-random-secret-in-your-env-file-min-32-chars}},
 * {@code ${DB_PASSWORD:finora}}) -- exactly right for a friction-free `docker compose up`, but
 * that same convenience means a real deployment that simply forgets to set one of these env vars
 * doesn't fail loudly; it starts up completely normally, using a publicly-known, guessable value
 * to protect real user sessions or a real database. "Graceful startup when environment variables
 * are missing" cuts both ways: missing-and-obvious-crash is fine, missing-and-silently-insecure
 * in production is the actually dangerous failure mode, so this fails loudly and immediately
 * instead. Deliberately does nothing outside the prod profile -- these placeholder defaults are
 * exactly what makes local dev and CI convenient, and must keep working with zero setup there.
 */
@Component
public class ProductionConfigValidator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ProductionConfigValidator.class);

    private static final String DEFAULT_JWT_SECRET =
            "change-this-to-a-long-random-secret-in-your-env-file-min-32-chars";
    private static final String DEFAULT_DB_PASSWORD = "finora";

    private final Environment environment;
    private final JwtProperties jwtProperties;

    public ProductionConfigValidator(Environment environment, JwtProperties jwtProperties) {
        this.environment = environment;
        this.jwtProperties = jwtProperties;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean isProd = Arrays.asList(environment.getActiveProfiles()).contains("prod");
        if (!isProd) return;

        StringBuilder problems = new StringBuilder();

        String secret = jwtProperties.getSecret();
        if (secret == null || secret.equals(DEFAULT_JWT_SECRET)) {
            problems.append("- JWT_SECRET is unset or still the placeholder default. ")
                    .append("Set a real random 32+ character value.\n");
        } else if (secret.length() < 32) {
            problems.append("- JWT_SECRET is set but shorter than the 32 characters HS256 requires.\n");
        }

        String dbPassword = environment.getProperty("spring.datasource.password");
        if (DEFAULT_DB_PASSWORD.equals(dbPassword)) {
            problems.append("- DB_PASSWORD is unset or still the local-dev default (\"finora\"). ")
                    .append("Set the real database password.\n");
        }

        if (!problems.isEmpty()) {
            String message = "Refusing to start with the prod profile active and insecure default "
                    + "configuration still in place:\n" + problems
                    + "These defaults exist for local development convenience only.";
            log.error(message);
            throw new IllegalStateException(message);
        }

        log.info("Production configuration validated: JWT_SECRET and DB_PASSWORD are not using their local-dev defaults.");
    }
}
