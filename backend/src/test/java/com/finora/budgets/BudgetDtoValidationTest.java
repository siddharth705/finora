package com.finora.budgets;

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
 * Exercises the validation constraints added to BudgetDto.UpsertRequest -- same gap as
 * GoalDto: Budgets.tsx already guards client-side against a zero/negative monthlyLimit (it's
 * used as a divisor for the progress bar), but nothing enforced this server-side.
 */
class BudgetDtoValidationTest {

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
    void upsertRequest_zeroMonthlyLimit_isRejected() {
        var req = new BudgetDto.UpsertRequest("Groceries", BigDecimal.ZERO);
        Set<ConstraintViolation<BudgetDto.UpsertRequest>> violations = validator.validate(req);
        assertThat(violatesField(violations, "monthlyLimit")).isTrue();
    }

    @Test
    void upsertRequest_negativeMonthlyLimit_isRejected() {
        var req = new BudgetDto.UpsertRequest("Groceries", new BigDecimal("-100"));
        Set<ConstraintViolation<BudgetDto.UpsertRequest>> violations = validator.validate(req);
        assertThat(violatesField(violations, "monthlyLimit")).isTrue();
    }

    @Test
    void upsertRequest_nullMonthlyLimit_isRejected() {
        var req = new BudgetDto.UpsertRequest("Groceries", null);
        Set<ConstraintViolation<BudgetDto.UpsertRequest>> violations = validator.validate(req);
        assertThat(violatesField(violations, "monthlyLimit")).isTrue();
    }

    @Test
    void upsertRequest_blankCategoryName_isRejected() {
        var req = new BudgetDto.UpsertRequest("   ", new BigDecimal("5000"));
        Set<ConstraintViolation<BudgetDto.UpsertRequest>> violations = validator.validate(req);
        assertThat(violatesField(violations, "categoryName")).isTrue();
    }

    @Test
    void upsertRequest_wellFormed_passesEveryConstraint() {
        var req = new BudgetDto.UpsertRequest("Groceries", new BigDecimal("5000"));
        Set<ConstraintViolation<BudgetDto.UpsertRequest>> violations = validator.validate(req);
        assertThat(violations).isEmpty();
    }
}
