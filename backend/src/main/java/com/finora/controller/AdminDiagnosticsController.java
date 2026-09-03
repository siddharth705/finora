package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.dto.DiagnosticsDto.PlatformDiagnosticsDto;
import com.finora.service.AdminDiagnosticsService;
import io.sentry.Sentry;
import io.sentry.SentryLevel;
import io.sentry.protocol.SentryId;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Platform Diagnostics -- gated by PLATFORM_DIAGNOSTICS_VIEW (V34), a read-only permission
 * distinct from SYSTEM_SETTINGS: this page has no configuration-mutation power, so it shouldn't
 * require the same permission as actually changing platform settings or feature flags does. See
 * AdminDiagnosticsService/DiagnosticsDto for what this deliberately is and isn't.
 */
@RestController
@RequestMapping("/api/v1/admin/diagnostics")
@PreAuthorize("hasAuthority('PLATFORM_DIAGNOSTICS_VIEW')")
public class AdminDiagnosticsController {

    private final AdminDiagnosticsService diagnosticsService;

    public AdminDiagnosticsController(AdminDiagnosticsService diagnosticsService) {
        this.diagnosticsService = diagnosticsService;
    }

    @GetMapping
    public ApiResponse<PlatformDiagnosticsDto> overview() {
        return ApiResponse.ok(diagnosticsService.overview());
    }

    /**
     * <b>TEMPORARY -- remove once the pipeline has been verified once in production.</b>
     *
     * <p>Emits one deliberate event so the path from this process to a Sentry issue can be observed
     * end to end: SDK initialisation, DSN authentication, network transport, ingestion, and the
     * environment/release tagging {@code MonitoringConfig} applies. The startup log already proves
     * initialisation; nothing proves the hops after it, and monitoring that has never delivered an
     * event is an unknown rather than a guarantee.
     *
     * <p><b>captureMessage, not captureException.</b> An exception event is indistinguishable from a
     * real application failure in the one place people look during an incident. This is not testing
     * exception handling -- it is testing transport -- and a message exercises every hop that
     * matters without leaving a synthetic bug in the issue stream. The level is WARNING so it is
     * visible without reading as an incident, and the text is marked so nobody mistakes it for one.
     *
     * <p><b>Why an endpoint at all.</b> Throwing from a controller would prove nothing:
     * {@code GlobalExceptionHandler} handles it and has no Sentry integration, so the event would
     * never be emitted and a working pipeline would look broken. Explicit capture is the only path
     * that actually reports.
     *
     * <p>Gated on SYSTEM_SETTINGS rather than this class's PLATFORM_DIAGNOSTICS_VIEW, overriding it
     * at method level (the pattern {@code AdminStatementAnalysisController} uses). That permission
     * is documented as read-only with no mutation power, and transmitting to an external service is
     * not read-only.
     *
     * @return the marker embedded in the event, Sentry's own id for it, and whether the SDK is
     *         enabled -- which is what separates "captured" from "silently discarded".
     */
    @PostMapping("/sentry-test")
    @PreAuthorize("hasAuthority('SYSTEM_SETTINGS')")
    public ApiResponse<Map<String, String>> verifySentryPipeline() {
        String marker = UUID.randomUUID().toString();
        SentryId eventId = Sentry.captureMessage(
                "[MANUAL VERIFICATION] Fynora Sentry pipeline check " + marker, SentryLevel.WARNING);
        return ApiResponse.ok(Map.of(
                "marker", marker,
                "eventId", eventId.toString(),
                "sentryEnabled", String.valueOf(Sentry.isEnabled())),
                "Verification event emitted");
    }
}
