package com.finora.repository;

import com.finora.entity.RegisteredLayout;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * The layout registry (V68).
 *
 * <p>Small by nature: one row per distinct document structure, bounded by how many layouts exist
 * rather than by traffic. Everything here reads or writes the whole row; there is no pagination and
 * no projection, because there is nothing to paginate.
 */
public interface RegisteredLayoutRepository extends JpaRepository<RegisteredLayout, UUID> {

    Optional<RegisteredLayout> findByFingerprint(String fingerprint);

    /** Batch form of {@link #findByFingerprint}, for resolving many fingerprints to their curated
     *  names in one query rather than one per fingerprint -- the registry stays small, but a
     *  caller iterating a whole failure-analytics window still shouldn't pay N+1 for it. */
    java.util.List<RegisteredLayout> findByFingerprintIn(java.util.Collection<String> fingerprints);

    /**
     * Records that a confirmed import produced this layout: inserts the row, or advances the
     * observed columns of the row that is already there.
     *
     * <h2>One statement, because two would be a race</h2>
     *
     * <p>The obvious implementation -- find, then insert or update -- is wrong here in a way that
     * costs a user their import. Two imports of a layout nobody has seen before both read "absent"
     * and both insert; the second violates {@code fingerprint UNIQUE} and, since this runs inside
     * the import's own work, rolls that import back. That is the exact shape of
     * {@code TransactionService.bulkRecategorize}'s outstanding defect (Milestone 2 item 3) and the
     * one Bug 02 already cost this codebase once. {@code ON CONFLICT DO UPDATE} makes the collision
     * a normal outcome instead of an exception, decided by the database rather than by two
     * application reads that cannot see each other.
     *
     * <h2>The SET clause names only observed columns, deliberately</h2>
     *
     * <p>{@code name} and {@code status} are absent from {@code DO UPDATE} and must stay absent. An
     * observation that reset status would drop a layout an operator had marked SUPPORTED back to
     * OBSERVED the next time anyone imported it -- the supported list would erode by being used,
     * and nothing would report that it had.
     *
     * <p>{@code LEAST}/{@code GREATEST} rather than plain assignment so an out-of-order observation
     * -- a backdated import, or two workers finishing in the wrong order -- cannot drag
     * {@code first_seen} forward or {@code last_seen} backward. {@code COALESCE} on the two
     * descriptive columns so an import whose metadata is missing a parser name leaves the known one
     * alone instead of blanking it.
     *
     * <h2>Always its own transaction</h2>
     *
     * <p>{@code REQUIRES_NEW} rather than joining the caller's, and declared here on the repository
     * so no call site can opt out of it by forgetting. Two reasons, both about the import rather
     * than about this row. A registry write must never be able to fail an import -- Milestone 1's
     * founding rule is that learning cannot cost an import, and this is learning by another name.
     * And the upsert takes a row lock on the single hottest row in this table: inside a caller's
     * transaction that lock would be held until the caller commits, so every concurrent import of
     * the commonest bank layout would queue behind whichever one started first, for as long as its
     * remaining work took. Held for one statement instead, it costs nothing.
     *
     * @return 1 always -- an insert and an on-conflict update both report one affected row. The
     *         count cannot distinguish "new layout" from "seen again"; callers that need to know
     *         should read the row.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Modifying
    @Query(value = """
           INSERT INTO layout_registry (id, fingerprint, source_format, parser, status,
                                        first_seen, last_seen, observation_count,
                                        created_at, updated_at)
           VALUES (gen_random_uuid(), :fingerprint, :sourceFormat, :parser, 'OBSERVED',
                   :seenAt, :seenAt, 1, now(), now())
           ON CONFLICT (fingerprint) DO UPDATE SET
               source_format     = COALESCE(EXCLUDED.source_format, layout_registry.source_format),
               parser            = COALESCE(EXCLUDED.parser, layout_registry.parser),
               first_seen        = LEAST(layout_registry.first_seen, EXCLUDED.first_seen),
               last_seen         = GREATEST(layout_registry.last_seen, EXCLUDED.last_seen),
               observation_count = layout_registry.observation_count + 1,
               updated_at        = now()
           """, nativeQuery = true)
    int observe(@Param("fingerprint") String fingerprint,
                @Param("sourceFormat") String sourceFormat,
                @Param("parser") String parser,
                @Param("seenAt") Instant seenAt);
}
