package com.finora.integrations.setu;

import com.finora.entity.FeatureEntitlement;
import com.finora.exception.ApiException;
import com.finora.service.AuditService;
import com.finora.service.EntitlementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class SetuConsentServiceTest {

    private AccountAggregatorLinkRepository links;
    private SetuConsentGateway gateway;
    private EntitlementService entitlementService;
    private AuditService auditService;
    private SetuConsentService service;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        links = mock(AccountAggregatorLinkRepository.class);
        gateway = mock(SetuConsentGateway.class);
        entitlementService = mock(EntitlementService.class);
        auditService = mock(AuditService.class);
        service = new SetuConsentService(links, gateway, entitlementService, auditService);

        when(entitlementService.hasEntitlement(userId, FeatureEntitlement.ACCOUNT_AGGREGATOR_SYNC))
                .thenReturn(true);
        when(gateway.isConfigured()).thenReturn(true);
        when(links.findByUserIdAndLinkIdempotencyKey(userId, "idem-1")).thenReturn(Optional.empty());
        when(links.save(any(AccountAggregatorLink.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void refusesAUserWithoutTheEntitlement() {
        when(entitlementService.hasEntitlement(userId, FeatureEntitlement.ACCOUNT_AGGREGATOR_SYNC))
                .thenReturn(false);

        assertThatThrownBy(() -> service.initiateLink(userId, FiType.DEPOSIT, "idem-1"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(org.springframework.http.HttpStatus.FORBIDDEN);

        verifyNoInteractions(gateway);
    }

    @Test
    void createsAConsentPendingLinkAndAuditsIt() {
        when(gateway.createConsent(userId.toString(), FiType.DEPOSIT))
                .thenReturn(new SetuConsentInitiation("consent-handle-1", "https://aa.example/redirect"));

        SetuConsentService.InitiateLinkResult result = service.initiateLink(userId, FiType.DEPOSIT, "idem-1");

        assertThat(result.redirectUrl()).isEqualTo("https://aa.example/redirect");
        assertThat(result.link().getStatus()).isEqualTo(AccountAggregatorLinkStatus.CONSENT_PENDING);
        assertThat(result.link().getConsentHandleId()).isEqualTo("consent-handle-1");
        assertThat(result.link().getFiType()).isEqualTo(FiType.DEPOSIT);
        verify(auditService).record(eq(userId), eq("ACCOUNT_AGGREGATOR_CONSENT_CREATED"),
                eq("AccountAggregatorLink"), any());
    }

    @Test
    void isIdempotentOnARepeatedKey() {
        AccountAggregatorLink existing = new AccountAggregatorLink();
        existing.setUserId(userId);
        existing.setFiType(FiType.DEPOSIT);
        existing.setLinkIdempotencyKey("idem-1");
        existing.setConsentHandleId("consent-handle-1");
        when(links.findByUserIdAndLinkIdempotencyKey(userId, "idem-1")).thenReturn(Optional.of(existing));

        SetuConsentService.InitiateLinkResult result = service.initiateLink(userId, FiType.DEPOSIT, "idem-1");

        assertThat(result.link()).isSameAs(existing);
        assertThat(result.redirectUrl()).isNull();
        verifyNoInteractions(gateway);
    }

    @Test
    void marksTheLinkFailedWhenSetuRejectsTheRequest() {
        when(gateway.createConsent(userId.toString(), FiType.DEPOSIT))
                .thenThrow(new RuntimeException("Setu 500"));

        assertThatThrownBy(() -> service.initiateLink(userId, FiType.DEPOSIT, "idem-1"))
                .isInstanceOf(ApiException.class);

        verify(links).save(argThat(link -> link.getStatus() == AccountAggregatorLinkStatus.LINK_FAILED));
        verify(auditService).record(eq(userId), eq("ACCOUNT_AGGREGATOR_LINK_FAILED"),
                eq("AccountAggregatorLink"), any());
    }

    @Test
    void refusesWhenSetuItselfIsNotConfigured() {
        when(gateway.isConfigured()).thenReturn(false);

        assertThatThrownBy(() -> service.initiateLink(userId, FiType.DEPOSIT, "idem-1"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);

        verify(links, never()).save(any());
    }

    @Test
    void recoversWhenTwoConcurrentRequestsRaceOnTheSameIdempotencyKey() {
        when(gateway.createConsent(userId.toString(), FiType.DEPOSIT))
                .thenReturn(new SetuConsentInitiation("consent-handle-1", "https://aa.example/redirect"));

        // Simulates the loser of the race: the unique index rejects this save because the other
        // concurrent request's row already committed first.
        AccountAggregatorLink winner = new AccountAggregatorLink();
        winner.setUserId(userId);
        winner.setFiType(FiType.DEPOSIT);
        winner.setLinkIdempotencyKey("idem-1");
        winner.setConsentHandleId("consent-handle-1");
        when(links.save(any(AccountAggregatorLink.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate key"));
        when(links.findByUserIdAndLinkIdempotencyKey(userId, "idem-1"))
                .thenReturn(Optional.empty()) // first check inside initiateLink
                .thenReturn(Optional.of(winner)); // re-lookup after the constraint violation

        SetuConsentService.InitiateLinkResult result = service.initiateLink(userId, FiType.DEPOSIT, "idem-1");

        assertThat(result.link()).isSameAs(winner);
        assertThat(result.redirectUrl()).isNull();
    }
}
