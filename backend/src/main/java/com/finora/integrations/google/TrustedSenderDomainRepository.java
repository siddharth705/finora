package com.finora.integrations.google;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TrustedSenderDomainRepository extends JpaRepository<TrustedSenderDomain, UUID> {

    /**
     * Exact-match lookup — the only way trust is ever established.
     *
     * <p>There is deliberately no {@code findByDomainEndingWith} or {@code LIKE} variant anywhere in
     * this interface. A suffix rule for {@code amazon.in} would also accept
     * {@code amazon.in.attacker.example}, a domain an attacker can register today, and the resulting
     * "trusted" receipts would carry whatever amounts they chose.
     */
    Optional<TrustedSenderDomain> findByDomain(String domain);

    /** Every trusted domain, for the in-memory cache the gate consults per message. Small by design
     *  — this list earns additions one at a time. */
    List<TrustedSenderDomain> findByStatus(TrustedSenderDomain.Status status);

    List<TrustedSenderDomain> findAllByOrderByMerchantNameAscDomainAsc();
}
