package com.finora.integrations.google;

import com.finora.dto.ApiResponse;
import com.finora.integrations.google.merchant.GmailReviewService;
import com.finora.security.CurrentUser;
import com.finora.util.LogSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Map;

/**
 * Gmail connection endpoints — Phase B of docs/proposals/gmail-transaction-sync-proposal.md.
 *
 * <p>Under {@code /integrations/}, not {@code /auth/}, and the distinction is the point: this is a
 * third-party DATA grant by a user who is already signed in, not a way to sign in. "Sign in with
 * Google" would be a different feature with different scopes and different consent copy.
 *
 * <p>Nothing here syncs, parses, or reads a mailbox. Connect, callback, status, disconnect.
 */
@RestController
@RequestMapping("/api/v1/integrations/google/gmail")
public class GoogleOAuthController {

    private static final Logger log = LoggerFactory.getLogger(GoogleOAuthController.class);

    private final GmailConnectionService connectionService;
    private final GoogleOAuthProperties properties;
    private final CurrentUser currentUser;
    private final GmailReviewService reviewService;
    private final GmailManualSyncService manualSyncService;

    public GoogleOAuthController(GmailConnectionService connectionService,
                                  GoogleOAuthProperties properties,
                                  CurrentUser currentUser,
                                  GmailReviewService reviewService,
                                  GmailManualSyncService manualSyncService) {
        this.connectionService = connectionService;
        this.properties = properties;
        this.currentUser = currentUser;
        this.reviewService = reviewService;
        this.manualSyncService = manualSyncService;
    }

    /**
     * Starts the flow. Authenticated — this is where Finora learns which user the eventual callback
     * belongs to, and the only place it can, since the callback itself arrives unauthenticated.
     *
     * <p>Returns the URL rather than issuing a redirect: the frontend performs the navigation
     * itself, which keeps the 302 out of an XHR and lets the UI show its own explanation screen
     * (what will be read, and that Finora never sends or deletes mail) before handing the user to
     * Google.
     */
    @PostMapping("/connect")
    public ApiResponse<Map<String, String>> connect() {
        String authorizationUrl = connectionService.beginConnect(currentUser.id());
        return ApiResponse.ok(Map.of("authorizationUrl", authorizationUrl));
    }

    /**
     * Where Google sends the browser back.
     *
     * <p><b>Unauthenticated by necessity</b>, and permitted as such in {@code SecurityConfig}. This
     * is a top-level browser navigation initiated by Google: it carries no Authorization header,
     * and this API keeps no session. Identity is recovered from the {@code state} parameter alone —
     * see {@link GmailOAuthState} for why that value is single-use, expiring, hashed at rest, and
     * generated with 256 bits of entropy.
     *
     * <p>Answers with a redirect rather than JSON, because a human is looking at this response in
     * their address bar, not a script. Success and failure both land back in Finora's settings
     * page; the outcome travels as a query parameter, never as anything that could carry a token.
     */
    @GetMapping("/callback")
    public ResponseEntity<Void> callback(@RequestParam(required = false) String code,
                                          @RequestParam(required = false) String state,
                                          @RequestParam(required = false) String error) {
        // The user pressed Cancel on Google's consent screen, or Google refused. Not an error
        // condition on Finora's side -- return them to where they started, with a note.
        if (error != null && !error.isBlank()) {
            log.info("Gmail consent was not granted: {}", LogSanitizer.sanitize(error));
            return redirectTo("gmail=declined");
        }
        if (code == null || code.isBlank() || state == null || state.isBlank()) {
            return redirectTo("gmail=invalid");
        }

        try {
            connectionService.completeConnect(state, code);
            return redirectTo("gmail=connected");
        } catch (Exception e) {
            // Deliberately no exception text in the redirect: this URL lands in the user's history
            // and referrer headers, and the message could describe internals. The specific reason
            // is logged server-side and the settings page shows the real status by re-fetching it.
            //
            // Bug fix: "logged server-side" was only ever true of the exception's CLASS, not its
            // message -- for an ApiException (the expected case) the message is the one clean,
            // human-written sentence explaining exactly what went wrong, and it was being thrown
            // away, leaving only "ApiException" to diagnose a real failure by. Sanitized the same
            // way the `error` param above is, since GoogleOAuthClient's messages can echo details
            // from Google's own error responses.
            log.warn("Gmail OAuth callback failed: {}: {}", e.getClass().getSimpleName(),
                    LogSanitizer.sanitize(e.getMessage()));
            return redirectTo("gmail=failed");
        }
    }

    /** What the user's settings page shows. Safe to call whether or not anything is connected.
     *  {@code findCurrentConnection}, not {@code findLiveConnection} -- the panel needs to show
     *  REVOKED/DISCONNECTED too, not just the statuses sync itself cares about. */
    @GetMapping("/status")
    public ApiResponse<GmailConnectionStatusDto> status() {
        boolean available = properties.isConfigured();
        return ApiResponse.ok(connectionService.findCurrentConnection(currentUser.id())
                .map(connection -> GmailConnectionStatusDto.of(connection, available,
                        reviewService.countTransactionsFound(connection.getId()),
                        reviewService.countNeedsReview(currentUser.id())))
                .orElseGet(() -> GmailConnectionStatusDto.notConnected(available)));
    }

    /**
     * "Sync Now" — C5.4. Runs discovery+extraction synchronously for this user's connection,
     * the same two calls {@link GmailDiscoveryWorker}'s tick makes, just for one mailbox and one
     * request instead of a scheduled slice. See {@link GmailManualSyncService} for the cooldown
     * and error-mapping this delegates to.
     */
    @PostMapping("/sync-now")
    public ApiResponse<Void> syncNow() {
        manualSyncService.syncNow(currentUser.id());
        return ApiResponse.ok(null, "Gmail synced");
    }

    /**
     * "Test connection" — checks the stored credential against Google right now.
     *
     * <p>Distinct from {@link #status()}, which reports what the database believes. Those diverge in
     * the case that matters most: a user who revokes Finora from their own Google account settings
     * leaves Finora's stored status untouched, because nothing here learns of it until something
     * tries to use the credential. Without this, that discovery happened on the first failed sync.
     *
     * <p>POST rather than GET because it is not free — it spends a request against Google, and a
     * GET invites caching and prefetching that would multiply that cost invisibly.
     *
     * <p>Never returns the access token it mints; verification only needs to know Google honoured
     * the grant.
     */
    @PostMapping("/connection/verify")
    public ApiResponse<GmailVerificationResultDto> verify() {
        return ApiResponse.ok(connectionService.verifyConnection(currentUser.id()));
    }

    /** Revokes at Google where possible and clears the stored credential. */
    @DeleteMapping("/connection")
    public ApiResponse<Void> disconnect() {
        connectionService.disconnect(currentUser.id());
        return ApiResponse.ok(null, "Gmail disconnected");
    }

    /**
     * Sends the browser back into the frontend.
     *
     * <p>The target is built from configuration, never from anything in the request — a redirect
     * target taken from a query parameter is an open redirect, and this endpoint is unauthenticated
     * and therefore reachable by anyone who can make a browser follow a link.
     */
    private ResponseEntity<Void> redirectTo(String query) {
        URI target = UriComponentsBuilder.fromUriString(properties.getPostConnectRedirect())
                .query(query)
                .build(true)
                .toUri();
        return ResponseEntity.status(HttpStatus.FOUND).location(target).build();
    }
}
