package com.finora.repository;

import com.finora.entity.Referral;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ReferralRepository extends JpaRepository<Referral, UUID> {

    /** Referrals page -- "number of successful referrals" is just a count, not a list. */
    long countByReferrerUserId(UUID referrerUserId);

    /** AccountPurgeSweepService -- referrals the purged user made as a referrer. Does not touch
     *  the OTHER user's own row-half; see {@link #deleteByReferredUserId} for that. */
    void deleteByReferrerUserId(UUID referrerUserId);

    /** AccountPurgeSweepService -- the (at most one) referral row where the purged user is the one
     *  who was referred. Deleting this loses the record of who referred them, an accepted
     *  consequence of a full account purge, same as any other joint record this sweep removes
     *  without preserving the other party's half. */
    void deleteByReferredUserId(UUID referredUserId);
}
