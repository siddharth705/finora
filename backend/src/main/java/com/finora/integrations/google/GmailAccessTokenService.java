package com.finora.integrations.google;

import com.finora.exception.ApiException;
import com.finora.security.crypto.EncryptedValue;
import com.finora.security.crypto.EncryptionException;
import com.finora.security.crypto.EncryptionService;
import com.finora.service.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.UUID;

/**
 * Turns a stored Gmail connection into a usable access token — the first thing in Finora that
 * actually USES the credential Phase B persisted, rather than merely holding or revoking it.
 *
 * <p>Access tokens are not stored. They live about an hour, and minting one costs a single request
 * against a refresh token Finora already holds; persisting them would add a second secret to
 * protect, a second expiry to track, and a second thing to invalidate on disconnect, in exchange
 * for saving one HTTP call per sync. The refresh token is the credential worth protecting, and it
 * already is (ADR-007).
 *
 * <p><b>This class owns the {@code REAUTH_REQUIRED} transition</b>, which nothing produced before
 * it existed. That status is what tells a user their mailbox needs reconnecting, and the only place
 * Finora can learn it is here: the moment Google refuses the stored grant.
 */
@Service
public class GmailAccessTokenService {

    private static final Logger log = LoggerFactory.getLogger(GmailAccessTokenService.class);

    private final GmailConnectionRepository connections;
    private final GoogleOAuthClient googleClient;
    private final EncryptionService encryptionService;
    private final AuditService auditService;
    private final TransactionTemplate transactionTemplate;

    public GmailAccessTokenService(GmailConnectionRepository connections,
                                    GoogleOAuthClient googleClient,
                                    EncryptionService encryptionService,
                                    AuditService auditService,
                                    TransactionTemplate transactionTemplate) {
        this.connections = connections;
        this.googleClient = googleClient;
        this.encryptionService = encryptionService;
        this.auditService = auditService;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * Mints a fresh access token for this connection.
     *
     * <p>Deliberately NOT {@code @Transactional}: the Google call in the middle must not run while a
     * pooled database connection is held (BH-016, BH-047, and the same mistake caught in Phase B's
     * own pre-commit check). The only database write here happens after Google has answered, in its
     * own short transaction.
     *
     * @throws GmailReauthRequiredException if the grant is gone — the connection has been flipped to
     *         {@code REAUTH_REQUIRED} before this is thrown, so the caller does not have to
     * @throws ApiException on a transient failure, which leaves the connection untouched
     */
    public String accessTokenFor(GmailConnection connection) {
        if (!connection.getStatus().isLive()) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "This Gmail connection is not active.");
        }

        EncryptedValue credential = connection.credential();
        if (credential == null) {
            // A live connection with no credential should be impossible -- disconnect clears the
            // credential and the status together. If it happens, the row is not usable and saying
            // so is better than a confusing null downstream.
            markReauthRequired(connection, "credential-missing");
            throw new GmailReauthRequiredException(
                    "This Gmail connection holds no credential and must be reconnected.");
        }

        String refreshToken;
        try {
            refreshToken = encryptionService.decrypt(credential);
        } catch (EncryptionException e) {
            // The key that encrypted this is gone or wrong -- see the encryption runbook's lost-key
            // section. Unrecoverable for this connection, and reconnecting is exactly the remedy the
            // runbook prescribes, so REAUTH_REQUIRED is the honest state rather than a 500 on every
            // future sync.
            log.error("Cannot decrypt the Gmail credential for connection {} -- marking it for "
                    + "reconnection. Check FINORA_ENCRYPTION_KEY against the runbook.", connection.getId());
            markReauthRequired(connection, "credential-undecryptable");
            throw new GmailReauthRequiredException(
                    "The stored Gmail credential could not be read and must be reconnected.");
        }

        try {
            GoogleOAuthClient.TokenResponse refreshed = googleClient.refreshAccessToken(refreshToken);
            return refreshed.access_token();
        } catch (GmailReauthRequiredException e) {
            // Google says the grant is dead. This is the transition the status exists for.
            markReauthRequired(connection, "invalid-grant");
            throw e;
        }
        // A transient ApiException propagates untouched, deliberately: a timeout or a 5xx says
        // nothing about whether the grant is still good, and disconnecting a working integration
        // over one is a worse outcome than a failed sync that retries.
    }

    /**
     * Flips the connection and records why, in its own transaction.
     *
     * <p>Audited because this is a state change a user will ask about — "why did my Gmail
     * disconnect?" — and the answer is only reconstructable if the reason was written down at the
     * time. Uses the existing {@code AuditService} rather than a Gmail-specific log (ADR-007's
     * companion decision in the Phase B review).
     */
    private void markReauthRequired(GmailConnection connection, String reason) {
        UUID connectionId = connection.getId();
        transactionTemplate.executeWithoutResult(tx ->
                connections.findById(connectionId).ifPresent(fresh -> {
                    // Re-read inside the transaction: the caller's copy may be stale, and a
                    // connection the user disconnected in the meantime must not be resurrected
                    // into REAUTH_REQUIRED.
                    if (!fresh.getStatus().isLive()) return;
                    fresh.markReauthRequired();
                    connections.save(fresh);
                    auditService.record(fresh.getUserId(), "GMAIL_REAUTH_REQUIRED",
                            "GmailConnection", fresh.getId(),
                            Map.of("reason", reason, "googleUserId", fresh.getGoogleUserId()));
                }));
        log.info("Gmail connection {} now requires reauthentication ({}).", connectionId, reason);
    }
}
