package com.finora.service;

import com.finora.dto.ReferralDtos.MyReferralsDto;
import com.finora.entity.Referral;
import com.finora.entity.ReferralCode;
import com.finora.repository.ReferralCodeRepository;
import com.finora.repository.ReferralRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Refer &amp; Earn MVP -- redemption never blocks signup on a bad or self-referring code, a
 *  referral relationship is written once and never updated, and "my referrals" is just a code
 *  plus a count. */
class ReferralServiceTest {

    private ReferralCodeRepository referralCodeRepository;
    private ReferralRepository referralRepository;
    private AuditService auditService;
    private ReferralService service;

    private final UUID referrerId = UUID.randomUUID();
    private final UUID referredId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        referralCodeRepository = mock(ReferralCodeRepository.class);
        referralRepository = mock(ReferralRepository.class);
        auditService = mock(AuditService.class);
        service = new ReferralService(referralCodeRepository, referralRepository, auditService);
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
        verifyNoInteractions(auditService);
    }

    // Structurally this can't happen through the real registration flow today (a brand-new
    // account can't already own a code), but the check is explicit rather than relying on that.
    @Test
    void redeemCode_isASilentNoOp_whenTheCodeBelongsToTheAccountBeingCreated() {
        ReferralCode ownCode = new ReferralCode();
        ownCode.setUserId(referredId);
        ownCode.setCode("SELFCODE");
        when(referralCodeRepository.findByCode("SELFCODE")).thenReturn(Optional.of(ownCode));

        service.redeemCode(referredId, "selfcode");

        verify(referralRepository, never()).save(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void redeemCode_createsAReferral_forAValidCode() {
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
        verify(auditService).record(eq(referredId), eq("REFERRAL_REGISTERED"), eq("Referral"), any(),
                eq(java.util.Map.of("referrerUserId", referrerId.toString())));
    }

    @Test
    void myReferrals_returnsTheCodeAndZeroCount_forAUserWithNoReferrals() {
        ReferralCode existing = new ReferralCode();
        existing.setUserId(referrerId);
        existing.setCode("ABCD1234");
        when(referralCodeRepository.findByUserId(referrerId)).thenReturn(Optional.of(existing));
        when(referralRepository.countByReferrerUserId(referrerId)).thenReturn(0L);

        MyReferralsDto dto = service.myReferrals(referrerId);

        assertThat(dto.code()).isEqualTo("ABCD1234");
        assertThat(dto.referralCount()).isZero();
    }

    @Test
    void myReferrals_returnsTheRealCount_forAUserWithReferrals() {
        ReferralCode existing = new ReferralCode();
        existing.setUserId(referrerId);
        existing.setCode("ABCD1234");
        when(referralCodeRepository.findByUserId(referrerId)).thenReturn(Optional.of(existing));
        when(referralRepository.countByReferrerUserId(referrerId)).thenReturn(3L);

        MyReferralsDto dto = service.myReferrals(referrerId);

        assertThat(dto.referralCount()).isEqualTo(3L);
    }
}
