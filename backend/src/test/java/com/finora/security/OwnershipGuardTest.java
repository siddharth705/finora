package com.finora.security;

import com.finora.exception.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The fail-closed properties every ownership check in the app now inherits. The null cases below
 * are the reason this logic was worth centralizing at all: open-coded as
 * {@code entity.getUserId().equals(userId)} -- the form every service used before -- a null owner
 * id throws NullPointerException and surfaces as a 500, and a reviewer reading that line cannot
 * tell whether the null case denies or explodes. Here it is asserted.
 */
class OwnershipGuardTest {

    /** Minimal stand-in for any user-owned entity. */
    private record OwnedThing(UUID userId) {}

    private final UUID userId = UUID.randomUUID();

    @Test
    void requireOwned_returnsTheEntity_whenTheCallerOwnsIt() {
        OwnedThing thing = new OwnedThing(userId);

        OwnedThing result = OwnershipGuard.requireOwned(
                Optional.of(thing), OwnedThing::userId, userId, "Account");

        assertThat(result).isSameAs(thing);
    }

    @Test
    void requireOwned_throwsNotFound_whenTheRowDoesNotExist() {
        assertThatThrownBy(() -> OwnershipGuard.requireOwned(
                Optional.<OwnedThing>empty(), OwnedThing::userId, userId, "Account"))
                .isInstanceOf(ApiException.class)
                .hasMessage("Account not found")
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void requireOwned_throwsForbidden_whenTheRowBelongsToSomeoneElse() {
        OwnedThing someoneElses = new OwnedThing(UUID.randomUUID());

        assertThatThrownBy(() -> OwnershipGuard.requireOwned(
                Optional.of(someoneElses), OwnedThing::userId, userId, "Transaction"))
                .isInstanceOf(ApiException.class)
                .hasMessage("This transaction does not belong to you")
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void requireOwnedBy_failsClosed_whenTheEntityHasNoOwnerAtAll() {
        // An orphaned/unmigrated row must deny, not NullPointerException into a 500.
        assertThatThrownBy(() -> OwnershipGuard.requireOwnedBy(
                new OwnedThing(null), OwnedThing::userId, userId, "Goal"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void requireOwnedBy_failsClosed_whenTheCallerIdIsNull() {
        // Should be unreachable behind the auth filters, but "unreachable" is not a security
        // control -- a null caller must never match a real owner.
        assertThatThrownBy(() -> OwnershipGuard.requireOwnedBy(
                new OwnedThing(userId), OwnedThing::userId, null, "Goal"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void requireOwnedBy_failsClosed_whenBothIdsAreNull() {
        // The one case a naive Objects.equals(ownerId, userId) would wrongly ALLOW.
        assertThatThrownBy(() -> OwnershipGuard.requireOwnedBy(
                new OwnedThing(null), OwnedThing::userId, null, "Goal"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void multiWordLabels_readCorrectlyInBothMessages() {
        assertThatThrownBy(() -> OwnershipGuard.requireOwned(
                Optional.<OwnedThing>empty(), OwnedThing::userId, userId, "Statement import"))
                .hasMessage("Statement import not found");

        assertThatThrownBy(() -> OwnershipGuard.requireOwnedBy(
                new OwnedThing(UUID.randomUUID()), OwnedThing::userId, userId, "Statement import"))
                .hasMessage("This statement import does not belong to you");
    }
}
