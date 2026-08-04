package com.finora.util;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.ReportAsSingleViolation;
import jakarta.validation.constraints.Pattern;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.CONSTRUCTOR;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.TYPE_USE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * A URL that is safe to hand to a client as a clickable link: {@code http://} or {@code https://}
 * only.
 *
 * <p><b>Reusable security rule, not a point patch.</b> The bug this exists to prevent has already
 * happened once, as a stored XSS. {@code Bank.websiteUrl} -- writable by any admin holding
 * {@code BANK_MANAGE} -- had no scheme validation server-side at all, so
 * {@code javascript:alert(1)} could be persisted and was then rendered verbatim as a real
 * {@code <a href>} on that bank's Summary tab in the admin portal, executing in the admin origin
 * for every other admin who opened the drawer.
 *
 * <p>The admin portal has since shipped a client-side render guard, but that is one consumer's
 * defence, not validation of the stored data. The value was still being persisted unvalidated, so
 * any other current or future consumer -- mobile, an export, an email template, a report -- would
 * re-introduce the identical hazard. The invariant belongs at the layer that writes the data, which
 * is what this annotation enforces.
 *
 * <p>Applied to every admin- or user-supplied field in this codebase that a client renders as a
 * link: {@code BankDto.CreateRequest.websiteUrl}, {@code BankDto.UpdateRequest.websiteUrl}, and
 * {@code MerchantDto.UpdateRequest.website}. ({@code Merchant.logoUrl} is deliberately NOT
 * annotated -- it has a setter but no caller anywhere, so no request can reach it. If a write path
 * is ever added, it needs this annotation too.)
 *
 * <h2>Why a {@code @Pattern} rather than {@code @org.hibernate.validator.constraints.URL}</h2>
 * {@code @URL} is considerably more permissive about schemes than its name suggests -- it delegates
 * to {@code java.net.URL} parsing, accepts any scheme the JVM has a handler for, and its
 * {@code protocol} attribute constrains only a single scheme, so it cannot express "http or https"
 * without composition anyway. An anchored pattern says exactly what is allowed and nothing else,
 * which is the honest constraint for a security control.
 *
 * <h2>Why {@code \A}/{@code \z} rather than {@code ^}/{@code $}</h2>
 * Java's {@code $} matches at the end of input <em>or before a line terminator at the end of
 * input</em>, so {@code ^https?://\S+$} would also accept {@code "https://ok\n"}. {@code \A} and
 * {@code \z} are unconditional input anchors with no line-terminator special case, which removes
 * that class of surprise entirely rather than relying on it not mattering.
 *
 * <p>The whole expression is optional so that {@code ""} still validates: the admin form sends an
 * empty string (not an omitted field) for every optional text input left blank, and
 * {@code BankManagementService.blankToNull} is what turns that into a null column. Rejecting
 * {@code ""} here would break creating a bank without a website. Null is ignored by
 * {@code @Pattern} as usual.
 */
@Documented
@Constraint(validatedBy = {})
@Target({METHOD, FIELD, ANNOTATION_TYPE, CONSTRUCTOR, PARAMETER, TYPE_USE})
@Retention(RUNTIME)
@Pattern(regexp = SafeHttpUrl.PATTERN)
@ReportAsSingleViolation
public @interface SafeHttpUrl {

    /**
     * {@code http://} or {@code https://} followed by at least one non-whitespace character, or
     * nothing at all.
     *
     * <p>{@code [^\s]+} (not {@code .+}) is what rejects a value that merely <em>starts</em>
     * plausibly and then smuggles something else in after a newline or tab -- every character
     * through to the end of input has to be part of the URL.
     */
    String PATTERN = "\\A(?:(?i:https?)://[^\\s]+)?\\z";

    String message() default "must be an http:// or https:// URL";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
