package com.finora.integrations.google.merchant;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MerchantTemplateRepository extends JpaRepository<MerchantTemplate, UUID> {

    /** Mirrors {@code TrustedSenderDomainRepository.findByDomain}'s own shape and its own reasoning
     *  for a live per-message lookup rather than a cached in-memory copy: the entire point of a
     *  template is that editing this row takes effect without a deploy, and C3's gate already pays
     *  the identical cost (one indexed lookup per message) for the same property. */
    Optional<MerchantTemplate> findByMerchantDomainAndEnabledTrue(String merchantDomain);

    /** Any status -- unlike the lookup above, this is for the admin create-conflict check, which
     *  must see a disabled row too (the same domain cannot be claimed twice regardless of
     *  enabled), not just what a real message would currently match against. */
    Optional<MerchantTemplate> findByMerchantDomain(String merchantDomain);

    /** Every template, for the admin list view -- mirrors
     *  {@code TrustedSenderDomainRepository.findAllByOrderByMerchantNameAscDomainAsc}. */
    List<MerchantTemplate> findAllByOrderByMerchantNameAscMerchantDomainAsc();
}
