package com.finora.integrations.google;

import com.finora.dto.ApiResponse;
import com.finora.security.CurrentUser;
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

    public GoogleOAuthController(GmailConnectionService connectionService,
                                  GoogleOAuthProperties properties,
                                  CurrentUser currentUser) {
        this.connectionService = connectionService;
        this.properties = properties;
        this.currentUser = currentUser;
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
            log.info("Gmail consent was not granted: {}", error);
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
            log.warn("Gmail OAuth callback failed: {}", e.getClass().getSimpleName());
            return redirectTo("gmail=failed");
        }
    }

    /** What the user's settings page shows. Safe to call whether or not anything is connected. */
    @GetMapping("/status")
    public ApiResponse<GmailConnectionStatusDto> status() {
        boolean available = properties.isConfigured();
        return ApiResponse.ok(connectionService.findLiveConnection(currentUser.id())
                .map(connection -> GmailConnectionStatusDto.of(connection, available))
                .orElseGet(() -> GmailConnectionStatusDto.notConnected(available)));
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
