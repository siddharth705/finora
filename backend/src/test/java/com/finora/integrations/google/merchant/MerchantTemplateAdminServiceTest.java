package com.finora.integrations.google.merchant;

import com.finora.exception.ApiException;
import com.finora.integrations.google.TrustedSenderDomainRepository;
import com.finora.service.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unlike {@code TrustedSenderDomainServiceTest}'s registry (a real security decision), this table
 * is a data-quality surface -- these tests are about what stops a bad template from silently going
 * live and mis-staging a wrong amount into a real user's ledger, and about the one real footgun
 * routing without an {@code @Order} creates: a template quietly shadowing (or being shadowed by) a
 * hand-written parser for the same domain.
 */
class MerchantTemplateAdminServiceTest {

    private MerchantTemplateRepository templates;
    private AuditService auditService;
    private MerchantEmailParser amazonParser;
    private TemplateEmailParser templateParser;
    private MerchantTemplateAdminService service;

    private final UUID adminId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        templates = mock(MerchantTemplateRepository.class);
        auditService = mock(AuditService.class);
        amazonParser = mock(MerchantEmailParser.class);
        when(amazonParser.canParse(anyString())).thenReturn(false);
        when(amazonParser.claimsDomain(anyString())).thenReturn(false);
        // A real TemplateEmailParser instance (not a mock) so rejectIfClaimedByAnotherParser's own
        // "except TemplateEmailParser itself" exclusion is exercised against the real type, not a
        // mock that happens to answer canParse() a particular way.
        templateParser = new TemplateEmailParser(mock(MerchantTemplateRepository.class));
        TrustedSenderDomainRepository trustedSenders = mock(TrustedSenderDomainRepository.class);
        when(trustedSenders.findByDomain(anyString())).thenReturn(Optional.empty());
        service = new MerchantTemplateAdminService(templates, auditService,
                List.of(amazonParser, templateParser), trustedSenders);

        when(templates.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(templates.findByMerchantDomain(anyString())).thenReturn(Optional.empty());
    }

    private MerchantTemplate existing(String domain, boolean enabled) {
        MerchantTemplate entry = new MerchantTemplate();
        ReflectionTestUtils.setField(entry, "id", UUID.randomUUID());
        entry.setMerchantDomain(domain);
        entry.setMerchantName("Existing Merchant");
        entry.setReceiptMarker("Order Total");
        entry.setAmountPattern("Total: Rs. {amount}");
        entry.setDatePattern("Date: {date}");
        entry.setEnabled(enabled);
        when(templates.findById(entry.getId())).thenReturn(Optional.of(entry));
        return entry;
    }

    /** Admin Portal, Merchant Templates list -- was an unconditional fetch-all (V103 alone seeds
     *  50 rows). PageBounds.safePage/safeSize clamp before the query, same reasoning every other
     *  admin list page's identical clamp test gives. */
    @Test
    void listAll_clampsAnOutOfRangePageAndSize() {
        when(templates.findAllByOrderByMerchantNameAscMerchantDomainAsc(PageRequest.of(0, 100)))
                .thenReturn(new PageImpl<>(List.of()));

        Page<MerchantTemplate> result = service.listAll(-5, 500);

        assertThat(result.getContent()).isEmpty();
        verify(templates).findAllByOrderByMerchantNameAscMerchantDomainAsc(PageRequest.of(0, 100));
    }

    @Test
    void create_savesDisabledByDefaultRegardlessOfCallerAndAuditsWhoCreatedIt() {
        MerchantTemplate saved = service.create(adminId, "  SWIGGY.COM.  ", "Swiggy",
                "Order Summary", null, "Grand Total: Rs. {amount}", "Order Date: {date}");

        assertThat(saved.getMerchantDomain())
                .as("normalised the same way TrustedSenderDomain.normalize would")
                .isEqualTo("swiggy.com");
        assertThat(saved.isEnabled())
                .as("untested-by-construction -- activation is a separate, explicit call")
                .isFalse();
        assertThat(saved.getCreatedByUserId()).isEqualTo(adminId);
        verify(auditService).record(eq(adminId), eq("GMAIL_MERCHANT_TEMPLATE_CREATED"),
                eq("MerchantTemplate"), any(), any());
    }

    /** Regression coverage: create() originally only normalised the domain (lower-case, strip a
     *  trailing dot) without validating its shape at all -- a wildcard, an email address, or a
     *  full URL would have saved silently, producing a template that could never match any real
     *  authenticated domain with nothing telling the admin why. Reuses
     *  TrustedSenderDomain.requireValid, the same rejection TrustedSenderDomainService.add already
     *  applies to the sibling registry. */
    @Test
    @DisplayName("a malformed domain (wildcard, email address, or URL) is rejected, not silently saved")
    void create_rejectsAMalformedDomainRatherThanSavingItSilently() {
        assertThatThrownBy(() -> service.create(adminId, "*.swiggy.com", "Swiggy",
                "marker", null, "{amount}", "{date}"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.create(adminId, "receipts@swiggy.com", "Swiggy", // synthetic-ok
                "marker", null, "{amount}", "{date}"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.create(adminId, "https://swiggy.com/receipts", "Swiggy",
                "marker", null, "{amount}", "{date}"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.create(adminId, "not a domain", "Swiggy",
                "marker", null, "{amount}", "{date}"))
                .isInstanceOf(ApiException.class);
        verify(templates, never()).save(any());
    }

    @Test
    @DisplayName("a malformed amount pattern (missing the {amount} placeholder) is rejected at save time")
    void create_rejectsAPatternThatWouldNeverCompile() {
        assertThatThrownBy(() -> service.create(adminId, "swiggy.com", "Swiggy",
                "Order Summary", null, "Grand Total: no placeholder here", "Order Date: {date}"))
                .isInstanceOf(ApiException.class);
        verify(templates, never()).save(any());
    }

    @Test
    @DisplayName("creating a template for a domain a hand-written parser already claims is refused")
    void create_refusesADomainAlreadyHandledByAHandWrittenParser() {
        when(amazonParser.claimsDomain("amazon.in")).thenReturn(true);

        assertThatThrownBy(() -> service.create(adminId, "amazon.in", "Amazon",
                "Order #", null, "Total: Rs. {amount}", "Date: {date}"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already handled");
        verify(templates, never()).save(any());
    }

    /** Regression coverage for a real gap found in code review: the guard above must consult
     *  {@code claimsDomain}, not {@code canParse}. A config-gated hand-written parser (PhonePe,
     *  CRED, Paytm) answers {@code canParse} {@code false} while its feature flag is off -- which
     *  is the default in every environment -- so guarding on {@code canParse} would let an admin
     *  create and activate a template for a domain one of those parsers owns but simply hasn't been
     *  switched on for yet, reproducing the exact wrong-attribution bug those parsers exist to fix.
     *  Uses a real {@link PhonePeEmailParser} (not a mock) with its {@code enabled} flag left at
     *  its production default (false), so this proves the actual class, not a stubbed stand-in. */
    @Test
    @DisplayName("a domain a disabled config-gated parser owns is still refused, not just an enabled one")
    void create_refusesADomainOwnedByADisabledConfigGatedParser() {
        PhonePeEmailParser disabledPhonePeParser = new PhonePeEmailParser();
        assertThat(disabledPhonePeParser.canParse("phonepe.com"))
                .as("sanity check: this parser is disabled, matching every real environment's default")
                .isFalse();
        TrustedSenderDomainRepository trustedSenders = mock(TrustedSenderDomainRepository.class);
        when(trustedSenders.findByDomain(anyString())).thenReturn(Optional.empty());
        MerchantTemplateAdminService serviceWithPhonePe = new MerchantTemplateAdminService(
                templates, auditService, List.of(disabledPhonePeParser, templateParser),
                trustedSenders);

        assertThatThrownBy(() -> serviceWithPhonePe.create(adminId, "phonepe.com", "PhonePe",
                "Payment Successful", null, "Amount Paid: Rs. {amount}", "Date: {date}"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already handled");
        verify(templates, never()).save(any());
    }

    @Test
    @DisplayName("a duplicate domain is a conflict, not a silent overwrite")
    void create_refusesADuplicateDomainRegardlessOfItsCurrentStatus() {
        MerchantTemplate disabled = existing("zomato.com", false);
        when(templates.findByMerchantDomain("zomato.com")).thenReturn(Optional.of(disabled));

        assertThatThrownBy(() -> service.create(adminId, "zomato.com", "Zomato",
                "Order Summary", null, "Grand Total: Rs. {amount}", "Order Date: {date}"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already exists");
        verify(templates, never()).save(any());
    }

    @Test
    void create_refusesBlankFields() {
        assertThatThrownBy(() -> service.create(adminId, "", "Swiggy", "marker", null, "{amount}", "{date}"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.create(adminId, "swiggy.com", "  ", "marker", null, "{amount}", "{date}"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("a template can be created with an exclusion marker, carried through untouched")
    void create_carriesTheNonReceiptMarkerThrough() {
        MerchantTemplate saved = service.create(adminId, "swiggy.com", "Swiggy",
                "Order Summary", "Refund Processed|Order Cancelled",
                "Grand Total: Rs. {amount}", "Order Date: {date}");

        assertThat(saved.getNonReceiptMarker()).isEqualTo("Refund Processed|Order Cancelled");
    }

    @Test
    @DisplayName("editing an active template's matching fields auto-disables it pending re-test")
    void update_autoDisablesAnActiveTemplateWhenMatchingFieldsChange() {
        MerchantTemplate entry = existing("uber.com", true);

        MerchantTemplate result = service.update(adminId, entry.getId(), "Uber",
                "Trip Total", null, "Total: Rs. {amount}", "Date: {date}");

        assertThat(result.isEnabled())
                .as("an untested fix must not go live just because it was typed into an edit form")
                .isFalse();
        ArgumentCaptor<Map<String, Object>> metadata = ArgumentCaptor.forClass(Map.class);
        verify(auditService).record(eq(adminId), eq("GMAIL_MERCHANT_TEMPLATE_UPDATED"),
                eq("MerchantTemplate"), eq(entry.getId()), metadata.capture());
        assertThat(metadata.getValue().get("autoDisabled")).isEqualTo(true);
        assertThat(metadata.getValue().get("changedFields"))
                .as("only receiptMarker actually changed -- amount/date/nonReceiptMarker did not")
                .isEqualTo(List.of("receiptMarker"));
    }

    @Test
    @DisplayName("adding an exclusion marker to an active template auto-disables it pending re-test")
    void update_autoDisablesAnActiveTemplateWhenTheNonReceiptMarkerChanges() {
        MerchantTemplate entry = existing("uber.com", true);

        MerchantTemplate result = service.update(adminId, entry.getId(), entry.getMerchantName(),
                entry.getReceiptMarker(), "Trip Cancelled", entry.getAmountPattern(), entry.getDatePattern());

        assertThat(result.isEnabled())
                .as("changing which emails a live template excludes is a matching-field change too")
                .isFalse();
        assertThat(result.getNonReceiptMarker()).isEqualTo("Trip Cancelled");
        ArgumentCaptor<Map<String, Object>> metadata = ArgumentCaptor.forClass(Map.class);
        verify(auditService).record(eq(adminId), eq("GMAIL_MERCHANT_TEMPLATE_UPDATED"),
                eq("MerchantTemplate"), eq(entry.getId()), metadata.capture());
        assertThat(metadata.getValue().get("autoDisabled")).isEqualTo(true);
        assertThat(metadata.getValue().get("changedFields")).isEqualTo(List.of("nonReceiptMarker"));
    }

    @Test
    @DisplayName("editing only the merchant name reports no changed matching fields")
    void update_relabellingAloneReportsNoChangedFields() {
        MerchantTemplate entry = existing("uber.com", true);

        service.update(adminId, entry.getId(), "Uber India",
                entry.getReceiptMarker(), entry.getNonReceiptMarker(), entry.getAmountPattern(),
                entry.getDatePattern());

        ArgumentCaptor<Map<String, Object>> metadata = ArgumentCaptor.forClass(Map.class);
        verify(auditService).record(eq(adminId), eq("GMAIL_MERCHANT_TEMPLATE_UPDATED"),
                eq("MerchantTemplate"), eq(entry.getId()), metadata.capture());
        assertThat(metadata.getValue().get("autoDisabled")).isEqualTo(false);
        assertThat(metadata.getValue().get("changedFields")).isEqualTo(List.of());
    }

    @Test
    @DisplayName("editing only the merchant name does not disable an active template")
    void update_relabellingAloneDoesNotDisable() {
        MerchantTemplate entry = existing("uber.com", true);

        MerchantTemplate result = service.update(adminId, entry.getId(), "Uber India",
                entry.getReceiptMarker(), entry.getNonReceiptMarker(), entry.getAmountPattern(),
                entry.getDatePattern());

        assertThat(result.isEnabled()).isTrue();
        assertThat(result.getMerchantName()).isEqualTo("Uber India");
    }

    @Test
    void update_cannotChangeTheDomain() {
        MerchantTemplate entry = existing("uber.com", true);

        service.update(adminId, entry.getId(), "Uber",
                entry.getReceiptMarker(), entry.getNonReceiptMarker(), entry.getAmountPattern(),
                entry.getDatePattern());

        assertThat(entry.getMerchantDomain())
                .as("no setter for the domain is exposed on update -- it can only be set at creation")
                .isEqualTo("uber.com");
    }

    @Test
    void update_rejectsAMalformedPatternTheSameAsCreate() {
        MerchantTemplate entry = existing("uber.com", false);

        assertThatThrownBy(() -> service.update(adminId, entry.getId(), "Uber",
                "Trip Total", null, "no placeholder here", "Date: {date}"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void activate_flipsEnabledAndAudits() {
        MerchantTemplate entry = existing("uber.com", false);

        MerchantTemplate result = service.activate(adminId, entry.getId());

        assertThat(result.isEnabled()).isTrue();
        verify(auditService).record(eq(adminId), eq("GMAIL_MERCHANT_TEMPLATE_ACTIVATED"),
                eq("MerchantTemplate"), eq(entry.getId()), any());
    }

    @Test
    void deactivate_neverDeletesTheRow() {
        MerchantTemplate entry = existing("uber.com", true);

        service.deactivate(adminId, entry.getId());

        verify(templates, never()).delete(any());
        verify(templates, never()).deleteById(any());
        verify(auditService).record(eq(adminId), eq("GMAIL_MERCHANT_TEMPLATE_DEACTIVATED"),
                eq("MerchantTemplate"), eq(entry.getId()), any());
    }

    @Test
    void activate_isANoOpAndUnauditedWhenAlreadyActive() {
        MerchantTemplate entry = existing("uber.com", true);

        service.activate(adminId, entry.getId());

        verify(auditService, never()).record(any(), anyString(), anyString(), any(), any());
    }

    @Test
    void unknownIdIs404() {
        UUID missing = UUID.randomUUID();
        when(templates.findById(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.activate(adminId, missing))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("No such merchant template");
    }
}
