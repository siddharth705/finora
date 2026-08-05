package com.finora.architecture.fixtures;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Deliberately vulnerable fixture -- NOT production code, and it lives in test sources.
 * It IS nonetheless component-scanned: target/test-classes sits under
 * com.finora, so @SpringBootTest registers a stereotype-annotated fixture as a real bean --
 * which is why these fixtures take no constructor dependencies. Giving one an un-declared
 * dependency fails context loading for every integration test in the suite.
 *
 * <p>This reproduces the exact shape of the real AdminSearchController bug: an admin-mapped
 * handler with no {@code @PreAuthorize}, reachable by any authenticated user. Its only purpose is
 * to prove {@link com.finora.architecture.AdminEndpointAuthorizationTest} actually detects that
 * shape. Without it, the "no unguarded endpoints" assertion passes just as happily when the
 * detection logic is broken as when the codebase is genuinely clean -- and a security rule that
 * cannot fail is worse than none, because it manufactures confidence.
 *
 * <p>Do not "fix" this class by adding the missing annotation. Its unguarded method is the test
 * input.
 */
@RestController
@RequestMapping("/api/v1/admin/fixture")
public class UnguardedAdminControllerFixture {

    /** The bug shape: an admin endpoint with no authorization annotation at all. */
    @GetMapping
    public String unguardedHandler() {
        return "any authenticated user can reach this";
    }

    /** A correctly-guarded sibling, so the rule is shown to flag only the offender. */
    @PostMapping
    @PreAuthorize("hasAuthority('USER_VIEW')")
    public String guardedHandler() {
        return "properly gated";
    }
}
