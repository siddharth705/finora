package com.finora.repository;

import com.finora.entity.Merchant;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MerchantRepository extends JpaRepository<Merchant, UUID> {
    List<Merchant> findByUserId(UUID userId);

    /** ImportService needs only the SIZE of the merchant table before and after an import, to
     *  report how many were newly learned. It was calling findByUserId(userId).size(), which loads
     *  and hydrates every merchant twice per import to produce a number the database can return
     *  directly. */
    long countByUserId(UUID userId);

    // Ownership-scoped lookup -- MerchantService uses this (never a bare findById) so a merchant
    // id belonging to another user can't be read, renamed, or merged just by guessing/enumerating
    // UUIDs. Same pattern as CurrentUser-scoped queries elsewhere in the codebase.
    Optional<Merchant> findByIdAndUserId(UUID id, UUID userId);

    /**
     * Backs the admin Merchant Intelligence page's platform-wide catalog (AdminMerchantStatsService)
     * -- every other query on this repository is deliberately userId-scoped (see the two above),
     * this is the one intentional exception. Groups every user's own Merchant rows by
     * canonicalName: userCount is how many distinct accounts have independently created a
     * merchant entry with this exact name (each user's Merchant row is private to them, per the
     * class comment on Merchant.java -- there's no shared/canonical merchant table today), and
     * rowCount is the total number of those per-user rows. Ordered by rowCount so the platform's
     * most common merchants surface first, which is the signal an admin scanning this page for
     * "what would benefit from a shared canonical entry" actually wants.
     */
    @Query("""
        SELECT m.canonicalName, COUNT(DISTINCT m.userId), COUNT(m)
        FROM Merchant m
        GROUP BY m.canonicalName
        ORDER BY COUNT(m) DESC
        """)
    List<Object[]> platformMerchantCounts();

    /** Global Search (AdminSearchService) -- distinct canonical merchant names matching the
     *  query. Distinct because the same canonicalName commonly exists as separate rows across
     *  many users' own private Merchant tables (see platformMerchantCounts' doc comment above) --
     *  a search result should show one row per name that exists somewhere on the platform, not
     *  one per user who happens to have created it. */
    @Query("""
        SELECT DISTINCT m.canonicalName FROM Merchant m
        WHERE LOWER(m.canonicalName) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')) ESCAPE '\\'
        ORDER BY m.canonicalName ASC
        """)
    List<String> searchDistinctCanonicalNames(@Param("q") String q, Pageable pageable);
}
