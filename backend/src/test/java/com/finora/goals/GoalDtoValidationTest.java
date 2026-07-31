package com.finora.goals;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the validation constraints added to GoalDto.CreateRequest/ContributionRequest --
 * following the same direct-Validator pattern as RegisterRequestValidationTest, no Spring
 * context needed. Covers the gap found during review: the frontend (Goals.tsx) already blocks
 * non-positive amounts and blank names client-side, but nothing enforced this server-side.
 */
class GoalDtoValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    private boolean violatesField(Set<? extends ConstraintViolation<?>> violations, String field) {
        return violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals(field));
    }

    @Test
    void contributionRequest_zeroAmount_isRejected() {
        var req = new GoalDto.ContributionRequest(BigDecimal.ZERO);
        Set<ConstraintViolation<GoalDto.ContributionRequest>> violations = validator.validate(req);
        assertThat(violatesField(violations, "amount")).isTrue();
    }

    @Test
    void contributionRequest_negativeAmount_isRejected() {
        var req = new GoalDto.ContributionRequest(new BigDecimal("-50.00"));
        Set<ConstraintViolation<GoalDto.ContributionRequest>> violations = validator.validate(req);
        assertThat(violatesField(violations, "amount")).isTrue();
    }

    @Test
    void contributionRequest_nullAmount_isRejected() {
        var req = new GoalDto.ContributionRequest(null);
        Set<ConstraintViolation<GoalDto.ContributionRequest>> violations = validator.validate(req);
        assertThat(violatesField(violations, "amount")).isTrue();
    }

    @Test
    void contributionRequest_positiveAmount_passesValidation() {
        var req = new GoalDto.ContributionRequest(new BigDecimal("500.00"));
        Set<ConstraintViolation<GoalDto.ContributionRequest>> violations = validator.validate(req);
        assertThat(violations).isEmpty();
    }

    @Test
    void createRequest_zeroTargetAmount_isRejected() {
        var req = new GoalDto.CreateRequest("Emergency Fund", BigDecimal.ZERO, null, null);
        Set<ConstraintViolation<GoalDto.CreateRequest>> violations = validator.validate(req);
        assertThat(violatesField(violations, "targetAmount")).isTrue();
    }

    @Test
    void createRequest_negativeTargetAmount_isRejected() {
        var req = new GoalDto.CreateRequest("Emergency Fund", new BigDecimal("-1000"), null, null);
        Set<ConstraintViolation<GoalDto.CreateRequest>> violations = validator.validate(req);
        assertThat(violatesField(violations, "targetAmount")).isTrue();
    }

    @Test
    void createRequest_blankName_isRejected() {
        var req = new GoalDto.CreateRequest("   ", new BigDecimal("1000"), null, null);
        Set<ConstraintViolation<GoalDto.CreateRequest>> violations = validator.validate(req);
        assertThat(violatesField(violations, "name")).isTrue();
    }

    @Test
    void createRequest_nullStartingAmount_isAllowed() {
        // A brand-new goal legitimately starts with nothing saved yet.
        var req = new GoalDto.CreateRequest("Emergency Fund", new BigDecimal("1000"), null, null);
        Set<ConstraintViolation<GoalDto.CreateRequest>> violations = validator.validate(req);
        assertThat(violatesField(violations, "currentAmount")).isFalse();
    }

    @Test
    void createRequest_negativeStartingAmount_isRejected() {
        var req = new GoalDto.CreateRequest("Emergency Fund", new BigDecimal("1000"), new BigDecimal("-1"), null);
        Set<ConstraintViolation<GoalDto.CreateRequest>> violations = validator.validate(req);
        assertThat(violatesField(violations, "currentAmount")).isTrue();
    }

    @Test
    void createRequest_wellFormed_passesEveryConstraint() {
        var req = new GoalDto.CreateRequest("Emergency Fund", new BigDecimal("1000"), new BigDecimal("200"), null);
        Set<ConstraintViolation<GoalDto.CreateRequest>> violations = validator.validate(req);
        assertThat(violations).isEmpty();
    }
}
