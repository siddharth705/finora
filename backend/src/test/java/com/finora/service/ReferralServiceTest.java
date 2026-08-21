package com.finora.service;

import com.finora.dto.ReferralDtos.MyReferralsDto;
import com.finora.entity.Referral;
import com.finora.entity.ReferralCode;
import com.finora.exception.ApiException;
import com.finora.repository.ReferralCodeRepository;
import com.finora.repository.ReferralRepository;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.UserRepository;
import com.finora.repository.WalletLedgerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** D-28 PR4-C. Covers the referral lifecycle -- redemption never blocks signup on a bad code, the
 *  REGISTERED -> SUBSCRIBED transition is automatic and one-directional, and reward crediting is
 *  admin-manual, idempotent (only from SUBSCRIBED), and fails closed on suspected self-referral. */
class ReferralServiceTest {

    private ReferralCodeRepository referralCodeRepository;
    private ReferralRepository referralRepository;
    private WalletLedgerRepository walletLedgerRepository;
    private RefreshTokenRepository refreshTokenRepository;
    private UserRepository userRepository;
    private AuditService auditService;
    private ReferralService service;

    private final UUID referrerId = UUID.randomUUID();
    private final UUID referredId = UUID.randomUUID();
    private final UUID adminId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        referralCodeRepository = mock(ReferralCodeRepository.class);
        referralRepository = mock(ReferralRepository.class);
        walletLedgerRepository = mock(WalletLedgerRepository.class);
        refreshTokenRepository = mock(RefreshTokenRepository.class);
        userRepository = mock(UserRepository.class);
        auditService = mock(AuditService.class);
        service = new ReferralService(referralCodeRepository, referralRepository, walletLedgerRepository,
                refreshTokenRepository, userRepository, auditService);
        when(referralRepository.save(any(Referral.class))).thenAnswer(inv -> {
            Referral r = inv.getArgument(0);
            if (r.getId() == null) ReflectionTestUtils.setField(r, "id", UUID.randomUUID());
            return r;
        });
        when(referralCodeRepository.save(any(ReferralCode.class))).thenAnswer(inv -> {
            ReferralCode c = inv.getArgument(0);
            if (c.getId() == null) ReflectionTestUtils.setField(c, "id", UUID.randomUUID());
            return c;
        });
    }

    private Referral referralWith(String status) {
        Referral r = new Referral();
        ReflectionTestUtils.setField(r, "id", UUID.randomUUID());
        r.setReferrerUserId(referrerId);
        r.setReferredUserId(referredId);
        r.setStatus(status);
        return r;
    }

    @Test
    void myCode_generatesAndPersistsOne_whenTheUserHasNoneYet() {
        when(referralCodeRepository.findByUserId(referrerId)).thenReturn(Optional.empty());
        when(referralCodeRepository.existsByCode(any())).thenReturn(false);

        String code = service.myCode(referrerId);

        assertThat(code).isNotBlank();
        verify(referralCodeRepository).save(any(ReferralCode.class));
    }

    @Test
    void myCode_returnsTheExistingCode_withoutCreatingAnother() {
        ReferralCode existing = new ReferralCode();
        existing.setUserId(referrerId);
        existing.setCode("ABCD1234");
        when(referralCodeRepository.findByUserId(referrerId)).thenReturn(Optional.of(existing));

        String code = service.myCode(referrerId);

        assertThat(code).isEqualTo("ABCD1234");
        verify(referralCodeRepository, never()).save(any());
    }

    @Test
    void redeemCode_isANoOp_whenTheCodeIsBlank() {
        service.redeemCode(referredId, "  ");

        verifyNoInteractions(referralCodeRepository);
        verify(referralRepository, never()).save(any());
    }

    @Test
    void redeemCode_isANoOp_whenTheCodeIsNull() {
        service.redeemCode(referredId, null);

        verifyNoInteractions(referralCodeRepository);
    }

    // The one thing this method must never do: turn a mistyped/stale referral code into a
    // rejected registration. redeemCode() has no return value and throws nothing for this case --
    // the only observable behavior is that no referral row gets created.
    @Test
    void redeemCode_isASilentNoOp_whenTheCodeIsNotRecognized_neverBlockingSignup() {
        when(referralCodeRepository.findByCode("BADCODE1")).thenReturn(Optional.empty());

        service.redeemCode(referredId, "badcode1");

        verify(referralRepository, never()).save(any());
    }

    @Test
    void redeemCode_createsAregisteredReferral_forAValidCode() {
        ReferralCode code = new ReferralCode();
        code.setUserId(referrerId);
        code.setCode("VALIDCOD");
        when(referralCodeRepository.findByCode("VALIDCOD")).thenReturn(Optional.of(code));

        // Lowercase + surrounding whitespace, as a pasted link fragment might arrive.
        service.redeemCode(referredId, "  validcod  ");

        var captor = org.mockito.ArgumentCaptor.forClass(Referral.class);
        verify(referralRepository).save(captor.capture());
        assertThat(captor.getValue().getReferrerUserId()).isEqualTo(referrerId);
        assertThat(captor.getValue().getReferredUserId()).isEqualTo(referredId);
        assertThat(captor.getValue().getStatus()).isEqualTo(Referral.STATUS_REGISTERED);
    }

    @Test
    void onPlanChanged_isANoOp_whenTheNewPlanIsFree() {
        service.onPlanChanged(referredId, "FREE", adminId);

        verifyNoInteractions(referralRepository);
    }

    @Test
    void onPlanChanged_isANoOp_whenTheUserWasNeverReferred() {
        when(referralRepository.findByReferredUserId(referredId)).thenReturn(Optional.empty());

        service.onPlanChanged(referredId, "PLUS", adminId);

        verify(referralRepository, never()).save(any());
    }

    @Test
    void onPlanChanged_movesARegisteredReferralToSubscribed() {
        Referral referral = referralWith(Referral.STATUS_REGISTERED);
        when(referralRepository.findByReferredUserId(referredId)).thenReturn(Optional.of(referral));

        service.onPlanChanged(referredId, "PLUS", adminId);

        assertThat(referral.getStatus()).isEqualTo(Referral.STATUS_SUBSCRIBED);
        verify(referralRepository).save(referral);
    }

    // A downgrade back to Free, or a second plan change after the referral already advanced, must
    // never re-trigger or reverse this transition.
    @Test
    void onPlanChanged_doesNotReTrigger_whenTheReferralIsAlreadyPastRegistered() {
        Referral alreadySubscribed = referralWith(Referral.STATUS_SUBSCRIBED);
        when(referralRepository.findByReferredUserId(referredId)).thenReturn(Optional.of(alreadySubscribed));

        service.onPlanChanged(referredId, "PREMIUM", adminId);

        verify(referralRepository, never()).save(any());
    }

    @Test
    void creditReward_throwsNotFound_forAnUnknownReferral() {
        UUID referralId = UUID.randomUUID();
        when(referralRepository.findById(referralId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.creditReward(referralId, BigDecimal.TEN, "promo", adminId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void creditReward_rejectsAReferralThatHasNotReachedSubscribedYet() {
        Referral referral = referralWith(Referral.STATUS_REGISTERED);
        when(referralRepository.findById(referral.getId())).thenReturn(Optional.of(referral));

        assertThatThrownBy(() -> service.creditReward(referral.getId(), BigDecimal.TEN, "promo", adminId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("REGISTERED");
        verifyNoInteractions(walletLedgerRepository);
    }

    // Idempotency: a referral already at REWARDED must reject a second credit attempt rather than
    // crediting the wallet twice -- same discipline the proposal cites for payment-webhook replay.
    @Test
    void creditReward_rejectsAnAlreadyRewardedReferral() {
        Referral referral = referralWith(Referral.STATUS_REWARDED);
        when(referralRepository.findById(referral.getId())).thenReturn(Optional.of(referral));

        assertThatThrownBy(() -> service.creditReward(referral.getId(), BigDecimal.TEN, "promo", adminId))
                .isInstanceOf(ApiException.class);
        verifyNoInteractions(walletLedgerRepository);
    }

    @Test
    void creditReward_refusesToCredit_whenReferrerAndReferredShareAnIp() {
        Referral referral = referralWith(Referral.STATUS_SUBSCRIBED);
        when(referralRepository.findById(referral.getId())).thenReturn(Optional.of(referral));
        when(refreshTokenRepository.findDistinctLastSeenIpsByUserId(referrerId)).thenReturn(List.of("203.0.113.5"));
        when(refreshTokenRepository.findDistinctLastSeenIpsByUserId(referredId)).thenReturn(List.of("203.0.113.5"));

        assertThatThrownBy(() -> service.creditReward(referral.getId(), BigDecimal.valueOf(100), "promo", adminId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("self-referral");
        verifyNoInteractions(walletLedgerRepository);
        assertThat(referral.getStatus()).isEqualTo(Referral.STATUS_SUBSCRIBED);
    }

    @Test
    void creditReward_creditsTheWalletAndMarksRewarded_whenNoAbuseSignalExists() {
        Referral referral = referralWith(Referral.STATUS_SUBSCRIBED);
        when(referralRepository.findById(referral.getId())).thenReturn(Optional.of(referral));
        when(refreshTokenRepository.findDistinctLastSeenIpsByUserId(referrerId)).thenReturn(List.of("203.0.113.5"));
        when(refreshTokenRepository.findDistinctLastSeenIpsByUserId(referredId)).thenReturn(List.of("198.51.100.9"));

        service.creditReward(referral.getId(), BigDecimal.valueOf(250), "promo", adminId);

        var entryCaptor = org.mockito.ArgumentCaptor.forClass(com.finora.entity.WalletLedgerEntry.class);
        verify(walletLedgerRepository).save(entryCaptor.capture());
        assertThat(entryCaptor.getValue().getUserId()).isEqualTo(referrerId);
        assertThat(entryCaptor.getValue().getAmount()).isEqualByComparingTo(BigDecimal.valueOf(250));
        assertThat(entryCaptor.getValue().getReferenceId()).isEqualTo(referral.getId());
        assertThat(referral.getStatus()).isEqualTo(Referral.STATUS_REWARDED);
        assertThat(referral.getReward()).isEqualByComparingTo(BigDecimal.valueOf(250));
    }

    @Test
    void myReferrals_returnsAnEmptyListAndZeroBalance_forAUserWithNoReferrals() {
        when(referralRepository.findByReferrerUserIdOrderByCreatedAtDesc(referrerId)).thenReturn(List.of());
        when(walletLedgerRepository.sumAmountByUserId(referrerId)).thenReturn(BigDecimal.ZERO);

        MyReferralsDto dto = service.myReferrals(referrerId);

        assertThat(dto.referrals()).isEmpty();
        assertThat(dto.walletBalance()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
