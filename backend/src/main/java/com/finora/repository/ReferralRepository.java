package com.finora.repository;

import com.finora.entity.Referral;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReferralRepository extends JpaRepository<Referral, UUID> {

    List<Referral> findByReferrerUserIdOrderByCreatedAtDesc(UUID referrerUserId);

    /** {@code referred_user_id} is unique (V101) -- at most one row can ever match. Used by
     *  {@code ReferralService.onPlanChanged} to find the referral (if any) a newly-paying user
     *  arrived through. */
    Optional<Referral> findByReferredUserId(UUID referredUserId);

    /** Admin Portal, Referral dashboard list -- grows with referral volume (roughly bounded by
     *  user count, same shape of risk as SubscriptionRepository.findAllByOrderByCreatedAtDesc's
     *  own doc comment), so this replaced an unconditional {@code findAll} the same way. */
    Page<Referral> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /** AccountPurgeSweepService -- referrals the purged user made as a referrer. Does not touch
     *  the OTHER user's own row-half; see {@link #deleteByReferredUserId} for that. */
    void deleteByReferrerUserId(UUID referrerUserId);

    /** AccountPurgeSweepService -- the (at most one) referral row where the purged user is the one
     *  who was referred. Deleting this loses the record of who referred them, an accepted
     *  consequence of a full account purge, same as any other joint record this sweep removes
     *  without preserving the other party's half. */
    void deleteByReferredUserId(UUID referredUserId);
}
