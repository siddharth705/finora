package com.finora.service;

import com.finora.entity.Merchant;
import com.finora.repository.MerchantRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Seeds every new user's merchant table with a curated set of well-known brands, APPROVED rather
 * than {@code TEMPORARY}-guessed on first sight. Phase 1 of the reconciliation roadmap
 * (docs/proposals/reconciliation-evolution-roadmap-proposal.md), called from {@code AuthService}
 * alongside {@code seedDefaultCategories} -- same pattern, same reason: {@code Merchant.userId} is
 * {@code NOT NULL} (unlike the genuinely global {@code MerchantTemplate} table Gmail parsing
 * uses), so there is no way to seed this platform-wide with a migration; it has to happen per user,
 * at the one point a user id exists to attach it to.
 *
 * <h2>Why this is the right lever, and "seed some aliases" (the roadmap's original wording) was not</h2>
 *
 * <p>{@code MerchantAlias.normalizedAlias} is matched by {@code MerchantNormalizationEngine} as an
 * <b>exact</b> string -- the full normalized description, reference number and all. A raw bank
 * narration ("SWIGGY*ORDR9182 BLR") carries a per-transaction reference, so no alias seeded in
 * advance could ever exact-match a real one; the roadmap's "curated alias set" was imprecise about
 * what actually helps.
 *
 * <p>What the engine already does on an alias miss is fall through to first-significant-token
 * matching against the user's <em>existing merchant table</em> ({@code
 * merchantsByFirstToken}, keyed on each merchant's own {@code canonicalName}). A pre-seeded
 * {@code Merchant} row named "Swiggy" is exactly what that fallback needs: "SWIGGY*ORDR9182 BLR"
 * normalizes to a first token of "swiggy", matches the seeded merchant, and the engine's own
 * {@code addAlias} records the real alias from there on -- no fabricated alias row required.
 *
 * <p>Deliberately no {@code defaultCategoryId} field or seeding here either, despite the roadmap
 * naming one: every merchant below already has real category coverage through {@code
 * CategoryRules}' keyword table (verified against it while implementing this, not assumed), so a
 * second, redundant default-category mechanism would be speculative code solving a gap that
 * doesn't exist for this list. Revisit only for a merchant added here that {@code CategoryRules}
 * doesn't already cover.
 */
@Service
public class MerchantSeedService {

    // Canonical names chosen to match CategoryRules' own keyword casing/spelling where a rule
    // exists for the brand, so a seeded merchant's first-significant-token and the keyword
    // table's category match are reading the same brand identity, not two independent guesses.
    private static final List<String> CURATED_MERCHANTS = List.of(
            // Dining / Groceries
            "Swiggy", "Zomato", "Blinkit", "Zepto", "BigBasket", "DMart",
            // Transport / Travel
            "Uber", "Ola", "Rapido", "IRCTC", "MakeMyTrip", "Goibibo", "Yatra",
            "Airbnb", "OYO", "IndiGo", "SpiceJet", "Vistara",
            // Shopping
            "Amazon", "Flipkart", "Myntra", "Ajio", "Nykaa", "Decathlon",
            // Entertainment / Subscriptions
            "Netflix", "Spotify", "BookMyShow", "Apple", "Google",
            // Investments
            "Zerodha", "Groww", "Upstox",
            // Health
            "Apollo", "Netmeds"
    );

    private final MerchantRepository merchantRepository;

    public MerchantSeedService(MerchantRepository merchantRepository) {
        this.merchantRepository = merchantRepository;
    }

    /** One saveAll() for the whole curated list -- same per-signup latency discipline as
     *  AuthService.seedDefaultCategories, not one insert per merchant. */
    public void seedCuratedMerchants(UUID userId) {
        List<Merchant> merchants = new ArrayList<>(CURATED_MERCHANTS.size());
        for (String name : CURATED_MERCHANTS) {
            Merchant m = new Merchant();
            m.setUserId(userId);
            m.setCanonicalName(name);
            m.setLifecycleStatus(Merchant.Lifecycle.APPROVED);
            merchants.add(m);
        }
        merchantRepository.saveAll(merchants);
    }
}
