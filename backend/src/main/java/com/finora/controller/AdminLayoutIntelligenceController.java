package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.imports.LayoutIntelligenceService;
import com.finora.imports.LayoutIntelligenceService.EvidenceReport;
import com.finora.imports.LayoutIntelligenceService.LayoutSummary;
import com.finora.imports.LayoutIntelligenceService.LayoutTimelinePoint;
import com.finora.imports.LayoutIntelligenceService.UnknownHeaderSummary;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Layout intelligence for the admin console: what document structures Finora actually sees, which
 * recur, which changed, and where the parser struggles.
 *
 * <h2>Why this is not a privacy hazard despite being platform-wide</h2>
 * Aggregating across users is the point -- "how many distinct layouts exist" is not a per-user
 * question. What keeps that operational telemetry rather than cross-user exposure is that every
 * response is keyed by layout fingerprint and carries counts, durations and header names only. No
 * user id, account, transaction, bank or file name appears in any of these types. Nothing here
 * feeds back into another user's import either: this is a read-only reporting surface, and layout
 * REUSE is explicitly not built (see docs/engineering/layout-intelligence-proposal.md).
 *
 * <h2>Gating</h2>
 * PLATFORM_DIAGNOSTICS_VIEW rather than a new permission: this is engineering telemetry about how
 * the import pipeline is performing, the same category as AdminDiagnosticsController, and it grants
 * no ability to change anything.
 *
 * <h2>What these numbers are for</h2>
 * Deciding where to spend parser effort, and eventually whether layout reuse is worth building at
 * all -- including the outcome where the evidence says no. They carry no thresholds and drive no
 * automatic behaviour; see LayoutIntelligenceService on why that restraint is deliberate.
 */
@RestController
@RequestMapping("/api/v1/admin/imports/layouts")
@PreAuthorize("hasAuthority('PLATFORM_DIAGNOSTICS_VIEW')")
public class AdminLayoutIntelligenceController {

    private final LayoutIntelligenceService layoutIntelligenceService;

    public AdminLayoutIntelligenceController(LayoutIntelligenceService layoutIntelligenceService) {
        this.layoutIntelligenceService = layoutIntelligenceService;
    }

    /** Every layout, most-used first, with its stable/unstable capability split. */
    @GetMapping
    public ApiResponse<List<LayoutSummary>> overview() {
        return ApiResponse.ok(layoutIntelligenceService.layoutOverview());
    }

    /**
     * Layouts whose latest import diverges structurally from the pattern before it.
     *
     * Surfaces that something changed. Deliberately does not say what to do about it -- a layout
     * can legitimately vary between statements, and calling that a fault would train people to
     * ignore the signal.
     */
    @GetMapping("/drifting")
    public ApiResponse<List<LayoutSummary>> drifting() {
        return ApiResponse.ok(layoutIntelligenceService.driftingLayouts());
    }

    /**
     * Headers no hint list recognises, ordered so the ones spanning the most layouts come first.
     *
     * The highest-value report here. A header appearing across several distinct layouts is a gap in
     * TransactionNormalizer's hint lists rather than one bank's quirk, which turns "where should
     * the parser improve" from a guess into a ranked list.
     */
    @GetMapping("/unknown-headers")
    public ApiResponse<List<UnknownHeaderSummary>> unknownHeaders() {
        return ApiResponse.ok(layoutIntelligenceService.unknownHeaders());
    }

    /** One layout's imports, oldest first, flagging each point where its structure changed. */
    @GetMapping("/{fingerprint}/timeline")
    public ApiResponse<List<LayoutTimelinePoint>> timeline(@PathVariable String fingerprint) {
        return ApiResponse.ok(layoutIntelligenceService.timeline(fingerprint));
    }

    /**
     * The report that decides whether layout reuse is ever worth building -- first encounters
     * versus recurrences on duration, unknown headers and skipped rows, with a written verdict.
     *
     * "No measurable benefit" is a successful result, and the verdict says so in those words rather
     * than leaving a table of near-identical numbers to be read hopefully.
     */
    @GetMapping("/evidence")
    public ApiResponse<EvidenceReport> evidence() {
        return ApiResponse.ok(layoutIntelligenceService.evidenceReport());
    }
}
