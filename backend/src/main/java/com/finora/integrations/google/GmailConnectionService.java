package com.finora.integrations.google;

import com.finora.exception.ApiException;
import com.finora.repository.UserRepository;
import com.finora.security.crypto.EncryptedValue;
import com.finora.security.crypto.EncryptionService;
import com.finora.service.AuditService;
import com.finora.util.TokenHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Gmail connection lifecycle: begin a flow, redeem the callback, report status, disconnect.
 *
 * <p>Phase B of docs/proposals/gmail-transaction-sync-proposal.md. Nothing here syncs, parses, or
 * ingests — this establishes and holds the credential, and stops.
 */
@Service
public class GmailConnectionService {

    private static final Logger log = LoggerFactory.getLogger(GmailConnectionService.class);

    /** How long a user has between pressing Connect and finishing Google's consent screen. Short
     *  on purpose: a consent screen is completed in a minute or abandoned, and a longer-lived state
     *  is only a wider window for a leaked callback URL to be replayed. */
    private static final Duration STATE_TTL = Duration.ofMinutes(10);

    /** 256 bits of entropy. This value is the sole thing binding a callback to a user (see
     *  {@link GmailOAuthState}), so it has to be infeasible to guess rather than merely unique. */
    private static final int STATE_BYTES = 32;

    /** How many expired states one sweep removes. Bounded for the same reason
     *  {@code ImportSessionService.CLEANUP_BATCH_SIZE} is — a backlog drains across runs. */
    private static final int STATE_SWEEP_BATCH_SIZE = 200;

    @org.springframework.beans.factory.annotation.Value("${app.integrations.google.state-cleanup.enabled:true}")
    private boolean stateCleanupEnabled;

    private static final List<GmailConnection.Status> LIVE =
            List.copyOf(GmailConnection.Status.LIVE);

    private final GmailConnectionRepository connections;
    private final GmailOAuthStateRepository states;
    private final GoogleOAuthClient googleClient;
    private final GoogleOAuthProperties properties;
    private final EncryptionService encryptionService;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final GmailAccessTokenService accessTokenService;
    private final GmailApiClient gmailApiClient;
    private final TransactionTemplate transactionTemplate;
    private final SecureRandom secureRandom = new SecureRandom();

    public GmailConnectionService(GmailConnectionRepository connections,
                                   GmailOAuthStateRepository states,
                                   GoogleOAuthClient googleClient,
                                   GoogleOAuthProperties properties,
                                   EncryptionService encryptionService,
                                   UserRepository userRepository,
                                   AuditService auditService,
                                   GmailAccessTokenService accessTokenService,
                                   GmailApiClient gmailApiClient,
                                   TransactionTemplate transactionTemplate) {
        this.connections = connections;
        this.states = states;
        this.googleClient = googleClient;
        this.properties = properties;
        this.encryptionService = encryptionService;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.accessTokenService = accessTokenService;
        this.gmailApiClient = gmailApiClient;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * Starts a flow and returns the Google URL the browser should be sent to.
     *
     * <p>The returned state is generated here, stored hashed, and never logged. The caller receives
     * the URL only — it has no reason to see the raw state, and neither does anything else in
     * Finora.
     */
    @Transactional
    public String beginConnect(UUID userId) {
        requireConfigured();

        // Rejected here rather than at the unique index, so the user gets "you already have a
        // mailbox connected" instead of an opaque conflict after sitting through a consent screen.
        //
        // Deliberately CONNECTED only, not the whole LIVE set. REAUTH_REQUIRED is the state the
        // "Reconnect" button itself targets -- a dead grant that "nothing but the user reconnecting
        // will fix" (see the status's own doc comment). Blocking that case here made the one action
        // the UI offers for it fail every time: Settings.tsx renders "Reconnect Gmail" precisely
        // when needsReconnect is true, and that button calls this same method. persistConnection
        // below retires the stale REAUTH_REQUIRED row so the fresh one doesn't collide with the
        // one-live-connection-per-user index (V80).
        connections.findByUserIdAndStatusIn(userId, LIVE)
                .filter(existing -> existing.getStatus() == GmailConnection.Status.CONNECTED)
                .ifPresent(existing -> {
                    throw new ApiException(HttpStatus.CONFLICT,
                            "A Gmail account (" + existing.getGoogleEmail() + ") is already connected. "
                                    + "Disconnect it first to connect a different one.");
                });

        byte[] raw = new byte[STATE_BYTES];
        secureRandom.nextBytes(raw);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);

        GmailOAuthState pending = new GmailOAuthState();
        pending.setStateHash(TokenHasher.sha256(state));
        pending.setUserId(userId);
        pending.setExpiresAt(Instant.now().plus(STATE_TTL));
        states.save(pending);

        return googleClient.buildAuthorizationUrl(state);
    }

    /**
     * Redeems a callback: validates the state, exchanges the code, and stores the encrypted
     * credential.
     *
     * <p><b>The state is validated before anything else happens</b> — before the code is exchanged,
     * before any outbound call. An unknown, expired, replayed, or already-consumed state means this
     * callback is not a flow Finora started, and the correct response is to do nothing at all
     * rather than to spend a request finding out what the code is worth.
     *
     * @return the connection that now exists
     */
    public GmailConnection completeConnect(String state, String code) {
        requireConfigured();

        // Deliberately NOT one transaction around this whole method. The two Google calls below sit
        // between the two transactional blocks, because holding a pooled database connection across
        // an outbound HTTP call is a failure mode this codebase has already been burned by twice:
        // BH-016 (a hung Resend endpoint starving the pool from inside @Transactional) and BH-047
        // (row locks held across an object-storage write). With a pool of ten and a 15-second read
        // timeout, ten concurrent connects against a slow Google would take the whole application
        // down, not just Gmail.
        UUID userId = consumeState(state);

        GoogleOAuthClient.TokenResponse tokens = googleClient.exchangeCode(code);
        GoogleOAuthClient.UserInfo identity = googleClient.fetchUserInfo(tokens.access_token());

        return persistConnection(userId, tokens, identity);
    }

    /**
     * Validates and burns the state, in its own short transaction.
     *
     * <p>Committing the consumption BEFORE the Google calls is deliberate. If the exchange then
     * fails, the state is spent and the user starts over — which is the correct direction to fail,
     * since Google's authorization code is itself single-use and a state left redeemable would be a
     * replay window held open by an error.
     *
     * @return the user the flow was started by — the only place that identity exists
     */
    private UUID consumeState(String state) {
        return transactionTemplate.execute(tx -> {
            String stateHash = TokenHasher.sha256(state);
            Instant now = Instant.now();

            // Strix security review, CWE-367. This used to read the row, check isRedeemable(), then
            // save -- which two concurrent callbacks could both pass before either wrote, defeating
            // the single-use guarantee this whole design rests on. The claim is one conditional
            // UPDATE now: exactly one caller sees 1, everyone else sees 0 and stops here, before any
            // outbound call and before anything is bound to a user. See the repository method's own
            // doc comment for why an atomic claim rather than a pessimistic read lock.
            if (states.claimForRedemption(stateHash, now) == 0) {
                // Read only to say something useful in the log -- the decision is already made.
                // Deliberately ONE message for every failure: a caller who did not start the flow
                // learns nothing about whether a state existed, was already used, or expired.
                states.findByStateHash(stateHash).ifPresentOrElse(
                        s -> log.warn("Rejected a Gmail OAuth callback: state was {} for user {}.",
                                s.isConsumed() ? "already used" : "expired", s.getUserId()),
                        () -> log.warn("Rejected a Gmail OAuth callback: unknown state."));
                throw new ApiException(HttpStatus.BAD_REQUEST,
                        "This Gmail connection link has expired or was already used. Start again from Settings.");
            }

            GmailOAuthState claimed = states.findByStateHash(stateHash)
                    .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST,
                            "This Gmail connection link is not valid. Start again from Settings."));

            UUID userId = claimed.getUserId();
            if (!userRepository.existsById(userId)) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                        "The account this connection belongs to no longer exists.");
            }
            return userId;
        });
    }

    /** The database half, after Google has answered. Own transaction, no network inside it. */
    private GmailConnection persistConnection(UUID userId,
                                               GoogleOAuthClient.TokenResponse tokens,
                                               GoogleOAuthClient.UserInfo identity) {
        // Google returns a refresh token only when it considers the grant new -- which is why
        // buildAuthorizationUrl forces prompt=consent. Without one, the connection would work for
        // about an hour and then be unrenewable, so refusing now is far better than storing
        // something that quietly stops working. Checked before opening a transaction: it is pure
        // validation and there is nothing to roll back.
        if (tokens.refresh_token() == null || tokens.refresh_token().isBlank()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "Google did not return a refresh token, so this connection could not be kept "
                            + "active. Remove Finora from your Google account permissions and try again.");
        }

        List<String> granted = googleClient.grantedScopes(tokens);
        Instant now = Instant.now();

        return transactionTemplate.execute(tx -> {
            // The same mailbox must not be connected to two Finora accounts -- each would ingest
            // the same receipts and attribute them to a different person. Checked here for a clear
            // message; the partial unique index in V80 is what actually guarantees it under a race.
            connections.findByGoogleUserIdAndStatusIn(identity.sub(), LIVE)
                    .filter(existing -> !existing.getUserId().equals(userId))
                    .ifPresent(existing -> {
                        // Diagnostic: which two accounts, and what state the other one is in.
                        // No secrets here -- ids and an enum value, the same shape already logged
                        // a few lines below for the success path.
                        log.warn("Gmail reconnect for user {} refused: googleUserId {} is already "
                                        + "{} on connection {} belonging to user {}.",
                                userId, identity.sub(), existing.getStatus(), existing.getId(),
                                existing.getUserId());
                        throw new ApiException(HttpStatus.CONFLICT,
                                "That Google account is already connected to another Finora account.");
                    });

            // beginConnect now lets a REAUTH_REQUIRED row through (that is the whole point of
            // "Reconnect"), so one can still be live here. Close it before inserting the fresh row
            // below -- otherwise this insert collides with uq_gmail_connections_active_user (V80),
            // which allows only one CONNECTED/REAUTH_REQUIRED row per user. DISCONNECTED rather than
            // a dedicated status: it drops out of LIVE and out of NEEDS_RECONNECT the same way a
            // user-initiated disconnect does, which is exactly right once this reconnect succeeds.
            connections.findByUserIdAndStatusIn(userId, LIVE)
                    .ifPresent(stale -> {
                        stale.close(GmailConnection.Status.DISCONNECTED);
                        connections.save(stale);
                    });

            GmailConnection connection = new GmailConnection();
            connection.setUserId(userId);
            connection.setGoogleUserId(identity.sub());
            connection.setGoogleEmail(identity.email() == null ? "" : identity.email());
            connection.setGrantedScopes(String.join(" ", granted));
            connection.setStatus(GmailConnection.Status.CONNECTED);
            connection.setConnectedAt(now);
            connection.storeCredential(encrypt(tokens.refresh_token()));
            GmailConnection saved = connections.save(connection);

            auditService.record(userId, "GMAIL_CONNECTED", "GmailConnection", saved.getId(),
                    Map.of("googleUserId", identity.sub(),
                            "googleEmail", saved.getGoogleEmail(),
                            "grantedScopes", granted));
            log.info("Gmail connected for user {} (connection {}).", userId, saved.getId());
            return saved;
        });
    }

    /** The user's live connection, if they have one -- {@code CONNECTED} or {@code
     *  REAUTH_REQUIRED} only, the two statuses Gmail sync actually acts on. */
    @Transactional(readOnly = true)
    public Optional<GmailConnection> findLiveConnection(UUID userId) {
        return connections.findByUserIdAndStatusIn(userId, LIVE);
    }

    /**
     * The user's current connection row, whatever its status -- unlike {@link #findLiveConnection},
     * this also surfaces {@code REVOKED} and {@code DISCONNECTED}. Exists for {@code GET /status}
     * specifically: {@code findLiveConnection}'s {@code LIVE} set deliberately excludes both (sync
     * has nothing to do with either), but the status panel needs to tell a user "your connection
     * was revoked, reconnect" apart from "you've never connected" -- collapsing both to "not
     * connected" was C6.1's own bug, since REVOKED was invisible to every caller before this
     * method existed. {@code redeemCallback} always inserts a fresh row on connect rather than
     * reviving a disconnected/revoked one (a user who reconnects several times accumulates several
     * historical rows), so the most recently created row is the only one that reflects the
     * account's current state -- everything older is history, not status.
     */
    @Transactional(readOnly = true)
    public Optional<GmailConnection> findCurrentConnection(UUID userId) {
        return connections.findByUserIdOrderByCreatedAtDesc(userId).stream().findFirst();
    }

    /**
     * Checks the stored credential against Google, on demand.
     *
     * <p>{@code GET /status} reports what the database believes; this reports what Google will
     * actually honour. They diverge in exactly the case that matters — a grant revoked from the
     * user's own Google account settings leaves Finora's status untouched, because nothing here
     * learns of it until something tries to use the credential. Before this existed, that discovery
     * happened on a user's first failed sync; now they and support can ask directly.
     *
     * <p>Deliberately not {@code @Transactional}: {@link GmailAccessTokenService#accessTokenFor}
     * calls Google, and that must not happen with a pooled connection held (BH-016/BH-047). It does
     * its own short write if the status changes.
     *
     * <p>The minted access token is discarded. Verification only needs to know Google honoured the
     * grant; keeping the token would add a second secret to protect for no benefit.
     */
    public GmailVerificationResultDto verifyConnection(UUID userId) {
        // Matches beginConnect/completeConnect. Without it, a deployment whose Google client
        // configuration was removed would send a blank client_id to Google, get invalid_client back,
        // and report "could not reach Google -- try again shortly" -- advice that can never work,
        // for a problem the operator has to fix rather than the user.
        requireConfigured();

        GmailConnection connection = connections.findByUserIdAndStatusIn(userId, LIVE)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "No Gmail account is connected."));

        // Checked before spending a request: the scope Google recorded at consent time is already
        // known, and if gmail.readonly is absent then no token, however fresh, can read this
        // mailbox. Calling Gmail to be told 403 would cost a round trip to learn what the row
        // already says.
        if (!connection.hasGmailReadScope()) {
            return GmailVerificationResultDto.scopeNotGranted(connection);
        }

        try {
            String accessToken = accessTokenService.accessTokenFor(connection);
            // The token refreshing proves the GRANT is alive; it does not prove Gmail will honour
            // it. Reading the profile is the cheapest call that does, and it is what turns this
            // endpoint from "your credential works" into "Finora can read your mailbox".
            gmailApiClient.getProfile(accessToken);
            return GmailVerificationResultDto.healthy(connection);
        } catch (GmailReauthRequiredException e) {
            // accessTokenFor has already flipped the connection and audited why -- this only has to
            // report it. The user's next status poll will agree with what they are told here.
            return GmailVerificationResultDto.reauthRequired();
        } catch (GmailScopeNotGrantedException e) {
            // Gmail refused on permission grounds despite the recorded scope -- the Gmail API may be
            // disabled on the Cloud project, or the grant was narrowed after the fact. Reported
            // distinctly rather than as "reconnect", because a re-consent that does not change the
            // scope will fail identically.
            return GmailVerificationResultDto.scopeNotGranted(connection);
        } catch (ApiException e) {
            // Transient. The connection is untouched, so report its existing status rather than
            // inventing a failure state: sending someone through a consent screen because Google
            // timed out would be a worse answer than "try again".
            return GmailVerificationResultDto.temporarilyUnavailable(connection);
        }
    }

    /**
     * Disconnects: revokes at Google where possible, then clears the local credential.
     *
     * <p>Ordered that way on purpose. Revocation is attempted first because it is the only step
     * that needs the token, but its failure does not stop the disconnect — the user asked to
     * disconnect, and keeping a credential because a third party returned an error would be the
     * wrong answer to that request.
     *
     * <p>Phase B scope: this stops future syncing and drops the credential. Deleting transactions
     * that a future sync created is a separate question, and there is nothing to delete yet.
     */
    public void disconnect(UUID userId) {
        // Same split as completeConnect, for the same reason: tryRevoke is an outbound call to
        // Google and must not run while a pooled database connection is held open (BH-016/BH-047).
        GmailConnection connection = transactionTemplate.execute(tx ->
                connections.findByUserIdAndStatusIn(userId, LIVE)
                        .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                                "No Gmail account is connected.")));

        boolean revoked = false;
        EncryptedValue credential = connection.credential();
        if (credential != null) {
            try {
                revoked = googleClient.tryRevoke(encryptionService.decrypt(credential));
            } catch (Exception e) {
                // An undecryptable credential (a key removed too early -- see the runbook) must not
                // trap the user in a connection they cannot remove. Clearing it is still correct
                // and is what they asked for.
                log.warn("Could not decrypt the stored Gmail credential for user {} while "
                        + "disconnecting; clearing it anyway.", userId);
            }
        }

        boolean revokedAtGoogle = revoked;
        transactionTemplate.executeWithoutResult(tx -> {
            connection.close(GmailConnection.Status.DISCONNECTED);
            connections.save(connection);
            auditService.record(userId, "GMAIL_DISCONNECTED", "GmailConnection", connection.getId(),
                    Map.of("googleUserId", connection.getGoogleUserId(),
                            "revokedAtGoogle", revokedAtGoogle));
        });
        log.info("Gmail disconnected for user {} (revoked at Google: {}).", userId, revoked);
    }

    /**
     * Removes expired OAuth states, a bounded batch at a time.
     *
     * <p>Without this the table only grows: every abandoned consent screen leaves a row, and
     * nothing in the connect/callback path ever deletes one. Same shape as
     * {@code ImportSessionService.sweepExpiredSessions} — bounded per run so a backlog drains
     * across runs rather than in one unbounded delete.
     *
     * <p>Expired rows carry no secret (the state is stored only as a hash), so this is housekeeping
     * rather than a security control — the single-use and expiry checks already refuse them long
     * before they are swept.
     *
     * @return how many rows were removed, so a test can see the sweep did something
     */
    @Transactional
    public int sweepExpiredStates() {
        List<GmailOAuthState> expired = states.findByExpiresAtBeforeOrderByExpiresAtAsc(
                Instant.now(), PageRequest.of(0, STATE_SWEEP_BATCH_SIZE));
        if (expired.isEmpty()) return 0;
        states.deleteAll(expired);
        return expired.size();
    }

    /**
     * The scheduled trigger. Flag-gated for the same reason
     * {@code ImportSessionService.scheduledSweep} is: an integration suite needs deterministic
     * state, and a background thread deleting rows mid-test is the cross-test pollution BH-058 was
     * about. {@code application-test.yml} turns it off and tests call
     * {@link #sweepExpiredStates()} directly.
     *
     * <p>{@code fixedDelay}, not {@code fixedRate}: the next sweep starts after the previous one
     * finishes, so a slow sweep cannot pile up overlapping runs.
     */
    @Scheduled(fixedDelayString = "${app.integrations.google.state-cleanup.interval-ms:3600000}",
               initialDelayString = "${app.integrations.google.state-cleanup.initial-delay-ms:120000}")
    public void scheduledStateSweep() {
        if (!stateCleanupEnabled) return;
        int removed = sweepExpiredStates();
        if (removed > 0) {
            log.info("Removed {} expired Gmail OAuth state(s).", removed);
        }
    }

    private EncryptedValue encrypt(String refreshToken) {
        return encryptionService.encrypt(refreshToken);
    }

    private void requireConfigured() {
        if (!properties.isConfigured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Gmail connection is not available on this deployment.");
        }
    }
}
