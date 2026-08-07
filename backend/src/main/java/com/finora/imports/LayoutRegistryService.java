package com.finora.imports;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.dto.ImportDto.FinancialDocumentMetadata;
import com.finora.entity.RegisteredLayout;
import com.finora.exception.ApiException;
import com.finora.repository.RegisteredLayoutRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;

/**
 * The layout registry's write side: turns "an import happened" into a durable row for the layout it
 * used.
 *
 * <p>Milestone 2 item 2. Without this the registry would be whatever V68's backfill found on the
 * day it ran, and every layout encountered afterwards -- which is every layout the rest of the
 * milestone is about -- would never get a row.
 *
 * <h2>Only a confirmed import registers a layout</h2>
 *
 * <p>Three code paths compute a fingerprint and only one of them calls this. Staging does not:
 * Milestone 1's rule is that staging writes nothing, and a user who uploads a file and abandons the
 * review has not shown Finora a layout it handles -- registering there is Bug 36 (abandoned staging
 * leaving permanent rows) with merchants swapped for layouts. Analysis sessions do not either: an
 * operator running Layout Studio on a specimen is deliberately not an import, and counting those
 * would make "how many layouts do we see in production" stop meaning that. Closing that loop -- an
 * operator registering a layout they analysed -- is item 7's job and needs an explicit action, not
 * a side effect.
 *
 * <h2>After the commit, never inside it</h2>
 *
 * <p>{@link #observe} defers to {@code afterCommit}, the same discipline
 * {@code MerchantLearningEventPublisher} uses. The reasons are on
 * {@code RegisteredLayoutRepository.observe}; the consequence here is that an import that rolls
 * back registers nothing, so {@code observation_count} counts imports that actually happened.
 *
 * <p>An observation that is lost -- process death between commit and callback, a database blip --
 * is not recovered, and deliberately has no queue or poller behind it. Losing one is
 * self-healing: the next import of the same layout writes the row, and the only cost in between is
 * a first_seen a few minutes late on a layout nobody has looked at yet. That is not comparable to
 * losing a learning event, which is why that path has a durable queue and this one does not.
 */
@Service
public class LayoutRegistryService {

    private static final Logger log = LoggerFactory.getLogger(LayoutRegistryService.class);

    private final RegisteredLayoutRepository repository;
    private final ObjectMapper objectMapper;

    public LayoutRegistryService(RegisteredLayoutRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * Registers, or re-observes, the layout a confirmed import used.
     *
     * <p>Silently does nothing without a fingerprint. That is the common case for the direct-file
     * confirm path and for a document that failed before it had any structure to hash, and neither
     * is an error worth a log line on every import.
     *
     * @param layoutMetadataJson the import's {@code FinancialDocumentMetadata}, read only for its
     *                           parser name; null and unparseable both leave the registry's
     *                           existing parser alone rather than blanking it
     */
    public void observe(String fingerprint, String sourceFormat, String layoutMetadataJson) {
        if (fingerprint == null || fingerprint.isBlank()) return;

        // Captured now, on the import's own thread, rather than read inside the callback: the point
        // being recorded is when the import happened, not when the registry got round to it.
        Instant seenAt = Instant.now();
        String parser = parserOf(layoutMetadataJson);

        afterCommit(() -> repository.observe(fingerprint, sourceFormat, parser, seenAt));
    }

    /**
     * Every registered layout, whatever its status.
     *
     * <p>Unpaged, because this table holds one row per distinct document structure -- bounded by
     * how many layouts exist rather than by how much anybody imports.
     */
    public List<RegisteredLayout> all() {
        return repository.findAll();
    }

    // ------------------------------------------------------------------ curation
    //
    // The two curated columns, each written by its own transactional method. The screen that calls
    // them is Milestone 2 item 7; what belongs to the persistence model, and therefore here, is
    // that a curation is a load-and-mutate inside a transaction rather than a save of a detached
    // instance. Combined with @DynamicUpdate on the entity, that makes the UPDATE name only the
    // column that changed -- so an operator naming a layout cannot roll back an observation that
    // arrived while they were typing. Saving a detached copy would write every column from a stale
    // snapshot and lose that observation silently.

    /** Names a layout, or clears its name when given null or blank. */
    @Transactional
    public RegisteredLayout rename(String fingerprint, String name) {
        RegisteredLayout layout = require(fingerprint);
        layout.rename(name);
        return layout;
    }

    /** Moves a layout to a curated status -- including SUPPORTED, the list criterion 4 needs. */
    @Transactional
    public RegisteredLayout moveTo(String fingerprint, RegisteredLayout.Status status) {
        RegisteredLayout layout = require(fingerprint);
        layout.moveTo(status);
        return layout;
    }

    /**
     * A layout cannot be curated into existence.
     *
     * <p>404 rather than creating the row: a fingerprint that has never been imported is either a
     * typo or a layout from somewhere this deployment has never seen, and inventing a registry
     * entry for it would put a layout in the "we have encountered this" list that nothing ever
     * encountered.
     */
    private RegisteredLayout require(String fingerprint) {
        return repository.findByFingerprint(fingerprint).orElseThrow(() -> new ApiException(
                HttpStatus.NOT_FOUND, "No layout is registered under fingerprint " + fingerprint));
    }

    private void afterCommit(Runnable work) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // No transaction to wait for -- nothing can roll back underneath us, so run it now.
            runSafely(work);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                runSafely(work);
            }
        });
    }

    /**
     * Runs the write, swallowing any failure.
     *
     * <p>By the time this executes the user's import is committed and their transactions exist. An
     * exception thrown out of {@code afterCommit} propagates to the caller, which would report a
     * failure for an import that entirely succeeded -- telemetry breaking the thing it observes.
     * Logged at warn because a registry that stops recording is worth noticing, and at warn rather
     * than error because nothing a user can see is affected.
     */
    private void runSafely(Runnable work) {
        try {
            work.run();
        } catch (RuntimeException e) {
            log.warn("Could not record a layout observation; the next import of this layout will "
                    + "record it instead.", e);
        }
    }

    /**
     * Pulls the parser name out of the stored metadata.
     *
     * <p>Returns null on anything unreadable instead of throwing. One malformed metadata blob must
     * not cost a layout its registration -- and the upsert's COALESCE means a null here leaves a
     * previously observed parser in place rather than erasing it.
     */
    private String parserOf(String layoutMetadataJson) {
        if (layoutMetadataJson == null || layoutMetadataJson.isBlank()) return null;
        try {
            FinancialDocumentMetadata metadata =
                    objectMapper.readValue(layoutMetadataJson, FinancialDocumentMetadata.class);
            return metadata == null ? null : metadata.parser();
        } catch (Exception e) {
            log.warn("Unreadable layout metadata while registering a layout; recording it without "
                    + "a parser name.", e);
            return null;
        }
    }
}
