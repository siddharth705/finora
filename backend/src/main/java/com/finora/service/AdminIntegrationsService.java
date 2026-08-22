package com.finora.service;

import com.finora.dto.HealthDtos.ProviderStatusDto;
import com.finora.dto.IntegrationsDto.IntegrationDto;
import com.finora.dto.IntegrationsDto.IntegrationsOverviewDto;
import com.finora.dto.IntegrationsDto.UpcomingIntegrationDto;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Backs the admin portal's Integrations page. Reuses AdminHealthRegistryService as the single
 * source of live status -- this never runs its own checks -- and layers a curated one-line
 * "what/why" description on top, for the subset of registered HealthProviders that are genuinely
 * third-party integrations rather than internal engine checks (Database, Financial Intelligence
 * Engine, Statement Import stay off this page; they're the Operational Dashboard's concern).
 *
 * DESCRIPTIONS is keyed by HealthProvider.name() -- a provider with no entry here simply doesn't
 * appear on this page, which is the deliberate whitelist mechanism: adding a new integration to
 * this page is a one-line addition here, not a flag to unset somewhere.
 */
@Service
public class AdminIntegrationsService {

    private static final Map<String, String> DESCRIPTIONS = new LinkedHashMap<>();
    static {
        DESCRIPTIONS.put("Statement Storage", "Durable object storage (Cloudflare R2, or filesystem in dev) for uploaded bank statement files");
        DESCRIPTIONS.put("Email Provider", "Resend -- password reset, welcome, and password-changed emails");
        DESCRIPTIONS.put("SMS Provider", "2Factor.in -- transactional alert SMS (not OTP)");
        DESCRIPTIONS.put("Gmail Sync", "Reads a connected mailbox to auto-detect transactions from receipt emails");
        DESCRIPTIONS.put("Google Sign-In", "Sign-in via a Google ID token -- a separate OAuth client from Gmail Sync");
        DESCRIPTIONS.put("Apple Sign-In", "Sign-in via Sign in with Apple (mobile today)");
        DESCRIPTIONS.put("OCR (Tesseract)", "Recognises text on scanned/image-only PDF bank statements");
        DESCRIPTIONS.put("Error Monitoring", "Sentry -- backend crash/error visibility, with PII scrubbing before anything leaves the process");
    }

    /**
     * Payment/subscription processing has a Subscription.paymentProvider column and a billing-
     * history service/controller, but no live Stripe/Razorpay client or outbound call anywhere in
     * the codebase -- schema and UI ahead of the integration itself. Listed here, not as a
     * HealthProvider, because there is genuinely nothing running yet to report a status on.
     */
    private static final List<UpcomingIntegrationDto> UPCOMING = List.of(
            new UpcomingIntegrationDto("Payment Provider (Stripe/Razorpay)",
                    "Subscription and billing-history schema already exist; no live payment client is wired up yet")
    );

    private final AdminHealthRegistryService healthRegistryService;

    public AdminIntegrationsService(AdminHealthRegistryService healthRegistryService) {
        this.healthRegistryService = healthRegistryService;
    }

    public IntegrationsOverviewDto overview() {
        List<IntegrationDto> integrations = healthRegistryService.platformHealth().providers().stream()
                .filter(p -> DESCRIPTIONS.containsKey(p.name()))
                .map(this::toIntegrationDto)
                .toList();

        return new IntegrationsOverviewDto(integrations, UPCOMING);
    }

    private IntegrationDto toIntegrationDto(ProviderStatusDto p) {
        return new IntegrationDto(p.name(), p.category(), DESCRIPTIONS.get(p.name()), p.status(), p.detail());
    }
}
