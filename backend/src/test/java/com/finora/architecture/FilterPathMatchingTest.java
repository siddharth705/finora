package com.finora.architecture;

import com.finora.architecture.registry.GuardianRule;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.PathContainer;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A servlet filter that reads the raw request URI must PARSE it, never string-compare it.
 *
 * <p>This is the enforced half of a real bypass. {@code RateLimitFilter} used to pick its limiter
 * with a {@code switch} over {@code request.getRequestURI()}, comparing the raw request line
 * against exact literals like {@code "/api/v1/auth/login"}. But nothing else in the stack routes
 * that way: DispatcherServlet's handler mapping and SecurityConfig's {@code requestMatchers(...)}
 * both match PathPattern against the DECODED path, where {@code %6C} is simply {@code l}. So
 * {@code POST /api/v1/auth/%6Cogin} was routed to AuthController.login() and performed an entirely
 * ordinary login attempt, while the filter saw an unrecognised path and applied no limiter at all
 * -- making every rate limiter in the application bypassable, with no credential and no
 * authentication, by percent-encoding any single character of the path.
 *
 * <p>The reason this is worth a build-breaking rule rather than a code comment is that the broken
 * version looks completely correct and stays green: every test in {@code RateLimitFilterTest}
 * passed the whole time, because a test author naturally writes the canonical spelling of a path.
 * The defect is invisible from inside the filter and invisible from inside its tests -- it only
 * appears when you compare the filter's matching against the router's, which no test does by
 * accident. The same trap is waiting for the next filter that needs to know "which endpoint is
 * this?", and there is nothing about {@code getRequestURI()} that warns you.
 *
 * <p>So the rule is mechanical: a filter may read {@code getRequestURI()} (there is no other way to
 * get the path this early in the chain, before Spring has parsed it), but it must hand the result
 * to {@link PathContainer#parsePath} and match a {@code PathPattern} against that -- which is the
 * same decoded view the router uses, and therefore cannot disagree with it. Matching via Spring
 * Security's own {@code RequestMatcher} types (as {@code PhoneVerificationFilter} does) satisfies
 * this too, since those never touch {@code getRequestURI()} directly.
 *
 * <p>If you are here because this test failed: you added a filter that string-compares the raw URI.
 * Match a parsed {@code PathPattern} instead -- see {@code RateLimitFilter.limiterFor} for the
 * shape.
 */
class FilterPathMatchingTest {

    @GuardianRule(
            id = "FG-026",
            category = GuardianRule.Category.SECURITY,
            intent = "A filter reading the raw request URI must parse it, never string-compare it.",
            source = "Incident: rate-limit bypass via percent-encoding",
            introduced = "2026-08-04",
            owner = "architecture",
            verification = GuardianRule.Verification.MANUAL_FALSIFICATION)
    @Test
    void filtersThatReadTheRawRequestUriMustParseItRatherThanCompareIt() {
        JavaClasses classes = ProductionClasses.INSTANCE;

        List<String> offenders = new ArrayList<>();

        for (JavaClass javaClass : classes) {
            if (!javaClass.isAssignableTo(Filter.class)) continue;

            boolean readsRawUri = false;
            boolean parsesIt = false;

            for (JavaMethodCall call : javaClass.getMethodCallsFromSelf()) {
                var target = call.getTarget();
                if (target.getOwner().isAssignableTo(HttpServletRequest.class)
                        && target.getName().equals("getRequestURI")) {
                    readsRawUri = true;
                }
                if (target.getOwner().isEquivalentTo(PathContainer.class)
                        && target.getName().equals("parsePath")) {
                    parsesIt = true;
                }
            }

            if (readsRawUri && !parsesIt) {
                offenders.add(javaClass.getFullName());
            }
        }

        assertThat(offenders)
                .as("""
                        These filters read request.getRequestURI() without parsing it. The raw URI is \
                        NOT what Spring routes on -- "/api/v1/auth/%%6Cogin" is routed to \
                        AuthController.login() but does not string-equal "/api/v1/auth/login", so any \
                        filter deciding "which endpoint is this?" by string comparison can be bypassed \
                        by percent-encoding a single character. Parse it with \
                        PathContainer.parsePath(...) and match a PathPattern (see \
                        RateLimitFilter.limiterFor), or use a Spring Security RequestMatcher.""")
                .isEmpty();
    }
}
