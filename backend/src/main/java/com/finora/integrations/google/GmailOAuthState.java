package com.finora.integrations.google;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * One pending "user pressed Connect Gmail", redeemed when Google redirects back.
 *
 * <h2>Why this exists at all</h2>
 *
 * Google returns by redirecting the user's BROWSER to the callback URL. That request carries no
 * Authorization header and no session — this API is stateless — so nothing on it says which Finora
 * user it belongs to. The only channel that survives the round trip is the {@code state} parameter
 * Google echoes back.
 *
 * <p>That makes {@code state} load-bearing for identity, not merely CSRF defence, and raises the
 * bar it has to clear: unguessable, bound to the user who began the flow, short-lived, and
 * <b>single use</b>. The last of those is why this is a table. A signed stateless token can carry a
 * user id and an expiry; nothing stateless can enforce "redeemable exactly once", and without that
 * a callback URL replayed out of browser history, a referrer header, or a shared screenshot links
 * the account again.
 *
 * <h2>The value is hashed, not stored</h2>
 *
 * {@link #stateHash} is SHA-256 of the state. The raw value passes through the user's browser and
 * Google's servers, so anyone holding it can complete a link for the bound user — it is a bearer
 * credential. Finora only ever needs to COMPARE a presented state against a stored one, never to
 * reproduce it, which is precisely the case hashing is for (contrast the refresh token, which must
 * be replayed to Google and is therefore encrypted instead — ADR-007). A leaked database yields no
 * usable states.
 */
@Entity
@Table(name = "gmail_oauth_states")
public class GmailOAuthState {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "state_hash", nullable = false, unique = true, length = 64)
    private String stateHash;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** Where to send the browser once the callback completes. Validated against an allowlist when
     *  the flow STARTS, so a crafted state cannot turn the callback into an open redirect. */
    @Column(name = "return_path", length = 512)
    private String returnPath;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** Set on redemption. Non-null means this state has already been used, and a second callback
     *  presenting it is a replay. Kept rather than deleted so a replay stays distinguishable from
     *  an entirely unknown state. */
    @Column(name = "consumed_at")
    private Instant consumedAt;

    public boolean isConsumed() { return consumedAt != null; }

    public boolean isExpired(Instant now) { return expiresAt.isBefore(now); }

    /** Redeemable exactly once, and only before it expires. */
    public boolean isRedeemable(Instant now) { return !isConsumed() && !isExpired(now); }

    public void consume(Instant now) { this.consumedAt = now; }

    public UUID getId() { return id; }
    public String getStateHash() { return stateHash; }
    public void setStateHash(String stateHash) { this.stateHash = stateHash; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public String getReturnPath() { return returnPath; }
    public void setReturnPath(String returnPath) { this.returnPath = returnPath; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Instant getConsumedAt() { return consumedAt; }
}
