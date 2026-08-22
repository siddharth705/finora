package com.finora.dto;

import com.finora.dto.AuthDtos.RegisterRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the @Email/@Pattern/@Size constraints on RegisterRequest directly through Jakarta
 * Bean Validation -- the same mechanism Spring's @Valid triggers on the controller, but without
 * needing a running server. This is the layer that was actually missing the reported bug: a
 * user could type an email address into the Mobile Number field and the form accepted it. The
 * frontend's own input filtering now makes that impossible to type in the first place (see
 * Register.tsx's sanitizePhoneInput), but the backend has to reject it too -- a browser
 * extension, a script, or any other API caller can send whatever bytes it wants, so the backend
 * is what actually determines whether bad data can ever reach the database.
 */
class RegisterRequestValidationTest {

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

    private RegisterRequest request(String email, String password, String fullName, String phoneNumber) {
        return new RegisterRequest(email, password, fullName, phoneNumber, null);
    }

    private boolean violatesField(Set<ConstraintViolation<RegisterRequest>> violations, String field) {
        return violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals(field));
    }

    @Test
    void anEmailAddressTypedIntoThePhoneNumberField_isRejected() {
        RegisterRequest req = request("jane@example.com", "Password123", "Jane Doe", "jane@example.com");

        Set<ConstraintViolation<RegisterRequest>> violations = validator.validate(req);

        assertThat(violatesField(violations, "phoneNumber")).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "abc1234567",       // letters mixed with digits
            "98765abcde",       // letters at the end
            "+91 98765 43210",  // spaces
            "9876543210!",      // trailing symbol
            "12345",            // too short
            "123456789012345678", // too long
    })
    void malformedPhoneNumbers_areRejected(String phoneNumber) {
        RegisterRequest req = request("jane@example.com", "Password123", "Jane Doe", phoneNumber);

        Set<ConstraintViolation<RegisterRequest>> violations = validator.validate(req);

        assertThat(violatesField(violations, "phoneNumber")).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = { "9876543210", "+919876543210", "+15551234567" })
    void wellFormedPhoneNumbers_passValidation(String phoneNumber) {
        RegisterRequest req = request("jane@example.com", "Password123", "Jane Doe", phoneNumber);

        Set<ConstraintViolation<RegisterRequest>> violations = validator.validate(req);

        assertThat(violatesField(violations, "phoneNumber")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = { "not-an-email", "missing-at-sign.com", "double@@at.com", "no-domain@" })
    void malformedEmails_areRejected(String email) {
        RegisterRequest req = request(email, "Password123", "Jane Doe", "9876543210");

        Set<ConstraintViolation<RegisterRequest>> violations = validator.validate(req);

        assertThat(violatesField(violations, "email")).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = { "   ", "\t", "\n" })
    void aFullNameContainingOnlyWhitespace_isRejected(String fullName) {
        RegisterRequest req = request("jane@example.com", "Password123", fullName, "9876543210");

        Set<ConstraintViolation<RegisterRequest>> violations = validator.validate(req);

        assertThat(violatesField(violations, "fullName")).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = { "Jane123", "Jane@Doe", "J4ne", "Jane_Doe", "123456" })
    void aFullNameContainingDigitsOrSymbols_isRejected(String fullName) {
        RegisterRequest req = request("jane@example.com", "Password123", fullName, "9876543210");

        Set<ConstraintViolation<RegisterRequest>> violations = validator.validate(req);

        assertThat(violatesField(violations, "fullName")).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = { "Jane Doe", "Jean-Luc Picard", "O'Brien", "Md. Rahman", "  Jane Doe  " })
    void legitimateFullNames_passValidation(String fullName) {
        RegisterRequest req = request("jane@example.com", "Password123", fullName, "9876543210");

        Set<ConstraintViolation<RegisterRequest>> violations = validator.validate(req);

        assertThat(violatesField(violations, "fullName")).isFalse();
    }

    @Test
    void aPasswordUnder8Characters_isRejected() {
        RegisterRequest req = request("jane@example.com", "short1", "Jane Doe", "9876543210");

        Set<ConstraintViolation<RegisterRequest>> violations = validator.validate(req);

        assertThat(violatesField(violations, "password")).isTrue();
    }

    @Test
    void aPasswordOver72Characters_isRejected() {
        // Bcrypt silently truncates at 72 bytes -- accepting a longer password here would let a
        // user believe two different passwords are distinct when bcrypt would hash them
        // identically past that point.
        String tooLong = "a".repeat(73);
        RegisterRequest req = request("jane@example.com", tooLong, "Jane Doe", "9876543210");

        Set<ConstraintViolation<RegisterRequest>> violations = validator.validate(req);

        assertThat(violatesField(violations, "password")).isTrue();
    }

    @Test
    void aWellFormedRequest_passesEveryConstraint() {
        RegisterRequest req = request("jane@example.com", "Password123", "Jane Doe", "+919876543210");

        Set<ConstraintViolation<RegisterRequest>> violations = validator.validate(req);

        assertThat(violations).isEmpty();
    }
}
