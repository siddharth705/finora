package com.finora.architecture.fixtures;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Deliberately vulnerable fixture -- NOT production code, and never registered with Spring (it
 * lives in test sources, which component scanning never reaches).
 *
 * <p>This reproduces the exact shape of the real {@code AdminAccountController} bug: a
 * {@code @RequestBody} parameter typed as a record that declares real Jakarta Bean Validation
 * constraints ({@code @NotBlank}, {@code @Size}, ...) on its own components, but the handler
 * parameter itself carries no {@code @Valid}/{@code @Validated}. Spring only runs Bean Validation
 * on a {@code @RequestBody} when the parameter (not the class, not the constraint annotations
 * alone) is marked {@code @Valid} or {@code @Validated} -- so every constraint on the DTO is
 * silently dead code on this path: a blank/oversized field reaches the service layer unchecked
 * and fails later as a raw, unhandled exception instead of a clean 400.
 *
 * <p>Its only purpose is to prove {@link com.finora.architecture.ValidatedRequestBodyTest}
 * actually detects that shape. Without it, the "every constrained request body is validated"
 * assertion passes just as happily when the detection logic is broken as when the codebase is
 * genuinely clean -- and a rule that cannot fail is worse than none, because it manufactures
 * confidence.
 *
 * <p>Do not "fix" this class by adding the missing {@code @Valid}. Its unvalidated handler is the
 * test input.
 */
@RestController
@RequestMapping("/api/v1/fixture/unvalidated-request-body")
public class UnvalidatedConstrainedRequestBodyControllerFixture {

    /** Carries a real constraint ({@code @NotBlank}) -- exactly like {@code AccountDto.CreateRequest}. */
    public record ConstrainedRequest(@NotBlank String name) {}

    /** The bug shape: a constrained request body with no {@code @Valid} on the parameter. */
    @PostMapping("/unguarded")
    public String unvalidatedHandler(@RequestBody ConstrainedRequest request) {
        return "the @NotBlank constraint above is never actually checked";
    }

    /** A correctly-annotated sibling, so the rule is shown to flag only the offender. */
    @PostMapping("/guarded")
    public String validatedHandler(@Valid @RequestBody ConstrainedRequest request) {
        return "properly validated";
    }
}
