package com.finora.service;

import com.finora.dto.ReferralDtos.MyReferralsDto;
import com.finora.entity.Referral;
import com.finora.entity.ReferralCode;
import com.finora.repository.ReferralCodeRepository;
import com.finora.repository.ReferralRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Refer &amp; Earn MVP -- the only thing this program tracks is whether a referral relationship
 * exists between two users. No reward, wallet, status lifecycle, or admin review: see the scope
 * cut this class replaced (formerly D-28 PR4-C) for what got removed and why. Every referral
 * relationship a user can see is, by definition, "successful" -- there's no other kind.
 */
@Service
public class ReferralService {

    private static final Logger log = LoggerFactory.getLogger(ReferralService.class);

    private final ReferralCodeRepository referralCodeRepository;
    private final ReferralRepository referralRepository;
    private final AuditService auditService;
    private final SecureRandom secureRandom = new SecureRandom();

    public ReferralService(ReferralCodeRepository referralCodeRepository, ReferralRepository referralRepository,
                            AuditService auditService) {
        this.referralCodeRepository = referralCodeRepository;
        this.referralRepository = referralRepository;
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
     * no organic acquisition to track there). A code can only be supplied at this one moment --
     * there's no later "add a referral code" path for an existing account.
     * <p>
     * Fails silently (logs, does not throw) on a missing/invalid code: a mistyped or stale
     * referral code must never block someone from completing registration -- the one thing this
     * method is not allowed to do is turn a cosmetic referral link into a hard signup failure.
     * Self-referral is rejected the same way: {@code referredUserId} is a brand-new account, so it
     * can never already own the code being redeemed today, but the check is kept explicit rather
     * than relying on that being true forever.
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
        if (code.get().getUserId().equals(referredUserId)) {
            log.info("Referral code {} belongs to the account being created ({}) -- ignored, not blocking signup.",
                    rawCode, referredUserId);
            return;
        }

        Referral referral = new Referral();
        referral.setReferrerUserId(code.get().getUserId());
        referral.setReferredUserId(referredUserId);
        referral = referralRepository.save(referral);

        auditService.record(referredUserId, "REFERRAL_REGISTERED", "Referral", referral.getId(),
                Map.of("referrerUserId", code.get().getUserId().toString()));
    }

    /**
     * Referrals page: the user's own code (created if this is their first visit) and how many
     * people they've successfully referred. Deliberately NOT {@code readOnly = true}: this calls
     * {@link #myCode} via a plain self-invocation, which bypasses Spring's proxy and so runs
     * under THIS method's transaction rather than getting its own -- under a read-only
     * transaction that write would be silently dropped (Hibernate's MANUAL flush mode eats it
     * with no exception), the exact class of bug this codebase has already hit twice elsewhere.
     */
    @Transactional
    public MyReferralsDto myReferrals(UUID userId) {
        String code = myCode(userId);
        long count = referralRepository.countByReferrerUserId(userId);
        return new MyReferralsDto(code, count);
    }
}
