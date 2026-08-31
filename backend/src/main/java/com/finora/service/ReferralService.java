package com.finora.service;

import com.finora.dto.PagedResponse;
import com.finora.dto.ReferralDtos.AdminReferralSummaryDto;
import com.finora.dto.ReferralDtos.MyReferralDto;
import com.finora.dto.ReferralDtos.MyReferralsDto;
import com.finora.entity.Referral;
import com.finora.entity.ReferralCode;
import com.finora.entity.User;
import com.finora.entity.WalletLedgerEntry;
import com.finora.exception.ApiException;
import com.finora.repository.ReferralCodeRepository;
import com.finora.repository.ReferralRepository;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.UserRepository;
import com.finora.repository.WalletLedgerRepository;
import com.finora.util.PageBounds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * D-28 PR4-C. The referral program (proposal §4) -- codes, invite tracking, and reward crediting.
 * Reward CREDITING is deliberately admin-manual (see {@link #creditReward}), not automatic: the
 * actual reward amount is still an open product decision (proposal §10), same reasoning PR4-A's
 * {@code SubscriptionService.changePlan} gives for why plan grants are admin-manual too. Everything
 * ELSE in the lifecycle -- code issuance, redemption at registration, and the REGISTERED ->
 * SUBSCRIBED transition -- is automatic, because none of those steps require inventing a business
 * term.
 */
@Service
public class ReferralService {

    private static final Logger log = LoggerFactory.getLogger(ReferralService.class);

    private final ReferralCodeRepository referralCodeRepository;
    private final ReferralRepository referralRepository;
    private final WalletLedgerRepository walletLedgerRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final SecureRandom secureRandom = new SecureRandom();

    public ReferralService(ReferralCodeRepository referralCodeRepository, ReferralRepository referralRepository,
                            WalletLedgerRepository walletLedgerRepository, RefreshTokenRepository refreshTokenRepository,
                            UserRepository userRepository, AuditService auditService) {
        this.referralCodeRepository = referralCodeRepository;
        this.referralRepository = referralRepository;
        this.walletLedgerRepository = walletLedgerRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
    }

    /** Lazily creates the user's own shareable code on first request -- there is no natural
     *  earlier moment (registration itself is the one time we can't hand out "your own" code, since
     *  the account doesn't exist yet). */
    @Transactional
    public String myCode(UUID userId) {
        return referralCodeRepository.findByUserId(userId)
                .map(ReferralCode::getCode)
                .orElseGet(() -> {
                    ReferralCode created = new ReferralCode();
                    created.setUserId(userId);
                    created.setCode(generateUniqueCode());
                    return referralCodeRepository.save(created).getCode();
                });
    }

    /** 8 uppercase hex characters -- same SecureRandom + hex convention as
     *  {@code AdminMfaService.generateRecoveryCodes}, short enough to type or paste into a
     *  registration field. Retried on the (astronomically unlikely) collision against the UNIQUE
     *  column rather than trusting one draw. */
    private String generateUniqueCode() {
        for (int attempt = 0; attempt < 5; attempt++) {
            byte[] raw = new byte[4];
            secureRandom.nextBytes(raw);
            StringBuilder hex = new StringBuilder();
            for (byte b : raw) hex.append(String.format("%02X", b));
            String code = hex.toString();
            if (!referralCodeRepository.existsByCode(code)) return code;
        }
        throw new IllegalStateException("Could not generate a unique referral code after 5 attempts.");
    }

    /**
     * Called from {@code AuthService.register()} only -- not the shared {@code createUserRecord}
     * helper, so admin-assisted signup ({@code adminCreateUser}) never creates a referral (there is
     * no organic acquisition to track there). Fails silently (logs, does not throw) on a
     * missing/invalid code: a mistyped or stale referral code must never block someone from
     * completing registration -- the one thing this method is not allowed to do is turn a cosmetic
     * referral link into a hard signup failure.
     */
    @Transactional
    public void redeemCode(UUID referredUserId, String rawCode) {
        if (rawCode == null || rawCode.isBlank()) return;

        Optional<ReferralCode> code = referralCodeRepository.findByCode(rawCode.trim().toUpperCase());
        if (code.isEmpty()) {
            log.info("Referral code {} not recognized at registration for user {} -- ignored, not blocking signup.",
                    rawCode, referredUserId);
            return;
        }

        Referral referral = new Referral();
        referral.setReferrerUserId(code.get().getUserId());
        referral.setReferredUserId(referredUserId);
        referral.setStatus(Referral.STATUS_REGISTERED);
        referral = referralRepository.save(referral);

        auditService.record(referredUserId, "REFERRAL_REGISTERED", "Referral", referral.getId(),
                Map.of("referrerUserId", code.get().getUserId().toString()));
    }

    /**
     * Called from {@code SubscriptionService.changePlan} after a successful plan change. A purely
     * factual transition (this user is now on a paying plan), unlike reward crediting -- no
     * business term is being invented by observing it, so it happens automatically. Silently a
     * no-op if the user was never referred, was already marked SUBSCRIBED, or the plan change is
     * TO the Free plan (a downgrade must never re-trigger or reverse this).
     *
     * <p>{@code actingAdminId}: today the ONLY way to reach this is via {@code changePlan}'s own
     * admin-manual action (no self-service upgrade exists yet, proposal §10), so the audit write's
     * subject stays {@code userId} (whose referral status changed) with the admin recorded
     * separately as {@code actorId} -- same convention as {@code AccountService.create()},
     * enforced by {@code AuditActorAttributionTest} (FG-025).
     */
    @Transactional
    public void onPlanChanged(UUID userId, String newPlanCode, UUID actingAdminId) {
        if ("FREE".equals(newPlanCode)) return;
        referralRepository.findByReferredUserId(userId)
                .filter(r -> Referral.STATUS_REGISTERED.equals(r.getStatus()))
                .ifPresent(r -> {
                    r.setStatus(Referral.STATUS_SUBSCRIBED);
                    referralRepository.save(r);
                    auditService.record(userId, "REFERRAL_SUBSCRIBED", "Referral", r.getId(),
                            Map.of("referrerUserId", r.getReferrerUserId().toString(), "planCode", newPlanCode,
                                    "actorId", actingAdminId.toString()));
                });
    }

    @Transactional(readOnly = true)
    public MyReferralsDto myReferrals(UUID userId) {
        var referrals = referralRepository.findByReferrerUserIdOrderByCreatedAtDesc(userId);
        Map<UUID, User> usersById = userRepository.findAllById(
                referrals.stream().map(Referral::getReferredUserId).distinct().toList()
        ).stream().collect(Collectors.toMap(User::getId, u -> u));

        var dtos = referrals.stream().map(r -> {
            User referred = usersById.get(r.getReferredUserId());
            return new MyReferralDto(r.getId(), referred != null ? referred.getFullName() : null,
                    r.getStatus(), r.getReward(), r.getCreatedAt());
        }).toList();

        BigDecimal balance = walletLedgerRepository.sumAmountByUserId(userId);
        return new MyReferralsDto(dtos, balance);
    }

    /** Admin Portal, Referral dashboard. Was an unconditional {@code findAll()} across the whole
     *  table -- referral volume grows with the user base (every registration through a code adds
     *  a row), same reasoning {@code SubscriptionService.listAll}'s own doc comment gives for the
     *  identical fix there. The user batch-fetch below is already scoped to just this page's
     *  referrer/referred ids, not the whole table. */
    @Transactional(readOnly = true)
    public PagedResponse<AdminReferralSummaryDto> listAll(int page, int size) {
        Page<Referral> referrals = referralRepository.findAllByOrderByCreatedAtDesc(
                PageRequest.of(PageBounds.safePage(page), PageBounds.safeSize(size)));
        Set<UUID> userIds = new HashSet<>();
        referrals.forEach(r -> { userIds.add(r.getReferrerUserId()); userIds.add(r.getReferredUserId()); });
        Map<UUID, User> usersById = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u));

        return PagedResponse.of(referrals.map(r -> {
            User referrer = usersById.get(r.getReferrerUserId());
            User referred = usersById.get(r.getReferredUserId());
            return new AdminReferralSummaryDto(
                    r.getId(),
                    r.getReferrerUserId(), referrer != null ? referrer.getEmail() : null, referrer != null ? referrer.getFullName() : null,
                    r.getReferredUserId(), referred != null ? referred.getEmail() : null, referred != null ? referred.getFullName() : null,
                    r.getStatus(), r.getReward(), r.getCreatedAt());
        }));
    }

    /**
     * Admin-only, manual (see this class's own doc comment for why). Fails closed on suspected
     * self-referral (proposal §4: "check device/IP overlap ... before crediting a reward, rather
     * than building a parallel fingerprinting system") -- reusing {@code RefreshToken}'s own
     * device/IP capture, not a new fingerprinting mechanism. Idempotent by construction: only a
     * referral currently at SUBSCRIBED can be credited, so retrying against an already-REWARDED
     * referral is rejected rather than double-crediting the wallet -- same idempotency-key
     * discipline the proposal cites for the Notification proposal's {@code notification_key} and
     * PR4-B's payment webhook design.
     */
    @Transactional
    public void creditReward(UUID referralId, BigDecimal amount, String reason, UUID actingAdminId) {
        Referral referral = referralRepository.findById(referralId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Referral not found."));
        if (!Referral.STATUS_SUBSCRIBED.equals(referral.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Only a referral whose referred user has subscribed can be credited (current status: "
                            + referral.getStatus() + ").");
        }
        if (sharesADeviceOrIp(referral.getReferrerUserId(), referral.getReferredUserId())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Possible self-referral detected -- these two accounts share a device/IP. Reward not credited.");
        }

        WalletLedgerEntry entry = new WalletLedgerEntry();
        entry.setUserId(referral.getReferrerUserId());
        entry.setAmount(amount);
        entry.setReason(WalletLedgerEntry.REASON_REFERRAL_REWARD);
        entry.setReferenceId(referral.getId());
        walletLedgerRepository.save(entry);

        referral.setStatus(Referral.STATUS_REWARDED);
        referral.setReward(amount);
        referralRepository.save(referral);

        auditService.record(referral.getReferrerUserId(), "REFERRAL_REWARD_CREDITED", "Referral", referral.getId(),
                Map.of("amount", amount.toString(), "reason", reason, "actorId", actingAdminId.toString()));
    }

    private boolean sharesADeviceOrIp(UUID referrerUserId, UUID referredUserId) {
        Set<String> referrerIps = new HashSet<>(refreshTokenRepository.findDistinctLastSeenIpsByUserId(referrerUserId));
        if (referrerIps.isEmpty()) return false;
        return refreshTokenRepository.findDistinctLastSeenIpsByUserId(referredUserId).stream()
                .anyMatch(referrerIps::contains);
    }
}
