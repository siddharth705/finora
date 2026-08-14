package com.finora.integrations.google;

import com.finora.exception.ApiException;
import com.finora.service.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Phase C3. Managing this registry is a security action — every entry decides whether authenticated
 * mail from a domain may become financial records — so these tests are about what the service
 * refuses and what it records, not about CRUD mechanics.
 */
class TrustedSenderDomainServiceTest {

    private TrustedSenderDomainRepository domains;
    private AuditService auditService;
    private TrustedSenderDomainService service;

    private final UUID adminId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        domains = mock(TrustedSenderDomainRepository.class);
        auditService = mock(AuditService.class);
        service = new TrustedSenderDomainService(domains, auditService);
        when(domains.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(domains.findByDomain(anyString())).thenReturn(Optional.empty());
    }

    private TrustedSenderDomain existing(String domain, TrustedSenderDomain.Status status) {
        TrustedSenderDomain entry = new TrustedSenderDomain();
        org.springframework.test.util.ReflectionTestUtils.setField(entry, "id", UUID.randomUUID());
        entry.setDomain(domain);
        entry.setMerchantName("Existing Merchant");
        entry.setStatus(status);
        when(domains.findById(entry.getId())).thenReturn(Optional.of(entry));
        return entry;
    }

    @Test
    void add_storesTheDomainNormalisedAndAuditsWhoDidIt() {
        TrustedSenderDomain saved = service.add(adminId, "  AMAZON.IN.  ", "Amazon");

        assertThat(saved.getDomain())
                .as("stored canonically so the gate's exact match cannot miss it on case or a dot")
                .isEqualTo("amazon.in");
        assertThat(saved.getAddedByUserId()).isEqualTo(adminId);
        verify(auditService).record(eq(adminId), eq("GMAIL_TRUSTED_DOMAIN_CREATED"),
                eq("TrustedSenderDomain"), any(), any());
    }

    /**
     * A wildcard is the one thing this registry must never accept. Storing it would either match
     * nothing — an entry an admin believes is protecting them — or, if matching were ever loosened,
     * trust an entire suffix.
     */
    @Test
    @DisplayName("a wildcard domain is refused rather than interpreted")
    void add_refusesAWildcard() {
        assertThatThrownBy(() -> service.add(adminId, "*.amazon.in", "Amazon"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("exact");
        verify(domains, never()).save(any());
    }

    @Test
    void add_refusesAnAddressOrUrlRatherThanASilentlyUselessEntry() {
        assertThatThrownBy(() -> service.add(adminId, "receipts@example.test", "Amazon"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.add(adminId, "https://amazon.in/orders", "Amazon"))
                .isInstanceOf(ApiException.class);
        verify(domains, never()).save(any());
    }

    @Test
    void add_refusesSomethingThatIsNotADomain() {
        assertThatThrownBy(() -> service.add(adminId, "not a domain", "X"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.add(adminId, "localhost", "X"))
                .as("a single label is not a sender domain")
                .isInstanceOf(ApiException.class);
    }

    /**
     * Re-adding must not silently resurrect a domain someone disabled. That would undo a security
     * decision without anyone deciding to — and without the audit trail showing a re-enable.
     */
    @Test
    @DisplayName("adding a domain that was previously disabled is a conflict, not a silent re-enable")
    void add_doesNotResurrectADisabledDomain() {
        TrustedSenderDomain disabled = new TrustedSenderDomain();
        disabled.setDomain("amazon.in");
        disabled.setMerchantName("Amazon");
        disabled.setStatus(TrustedSenderDomain.Status.DISABLED);
        when(domains.findByDomain("amazon.in")).thenReturn(Optional.of(disabled));

        assertThatThrownBy(() -> service.add(adminId, "amazon.in", "Amazon"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("DISABLED");
        verify(domains, never()).save(any());
    }

    @Test
    void disable_keepsTheRowAndAuditsIt() {
        TrustedSenderDomain entry = existing("amazon.in", TrustedSenderDomain.Status.ACTIVE);

        TrustedSenderDomain result = service.setStatus(adminId, entry.getId(),
                TrustedSenderDomain.Status.DISABLED);

        assertThat(result.getStatus()).isEqualTo(TrustedSenderDomain.Status.DISABLED);
        verify(domains, never()).delete(any());
        verify(domains, never()).deleteById(any());
        verify(auditService).record(eq(adminId), eq("GMAIL_TRUSTED_DOMAIN_DISABLED"),
                eq("TrustedSenderDomain"), eq(entry.getId()), any());
    }

    /** Re-enabling silently restores parse-trust, which makes it the more important of the two
     *  directions to have recorded. */
    @Test
    void enable_isAuditedTooNotJustDisable() {
        TrustedSenderDomain entry = existing("amazon.in", TrustedSenderDomain.Status.DISABLED);

        service.setStatus(adminId, entry.getId(), TrustedSenderDomain.Status.ACTIVE);

        verify(auditService).record(eq(adminId), eq("GMAIL_TRUSTED_DOMAIN_ENABLED"),
                eq("TrustedSenderDomain"), eq(entry.getId()), any());
    }

    @Test
    void settingTheStatusItAlreadyHasRecordsNothing() {
        TrustedSenderDomain entry = existing("amazon.in", TrustedSenderDomain.Status.ACTIVE);

        service.setStatus(adminId, entry.getId(), TrustedSenderDomain.Status.ACTIVE);

        verify(auditService, never()).record(any(), anyString(), anyString(), any(), any());
    }

    /**
     * The domain is immutable. Editing it in place would move trust to a different sender under a
     * row whose audit trail still describes the original — so the API only lets the label change.
     */
    @Test
    @DisplayName("relabelling changes the merchant name and never the domain")
    void rename_cannotChangeWhichDomainIsTrusted() {
        TrustedSenderDomain entry = existing("amazon.in", TrustedSenderDomain.Status.ACTIVE);

        TrustedSenderDomain result = service.rename(adminId, entry.getId(), "Amazon India");

        assertThat(result.getMerchantName()).isEqualTo("Amazon India");
        assertThat(result.getDomain()).isEqualTo("amazon.in");
        verify(auditService).record(eq(adminId), eq("GMAIL_TRUSTED_DOMAIN_RELABELLED"),
                eq("TrustedSenderDomain"), eq(entry.getId()), any());
    }

    @Test
    void unknownIdIs404() {
        UUID missing = UUID.randomUUID();
        when(domains.findById(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.setStatus(adminId, missing, TrustedSenderDomain.Status.DISABLED))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("No such trusted sender domain");
    }
}
