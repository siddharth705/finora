package com.finora.imports.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * The one place that decides where a statement's bytes go and where they come back from.
 *
 * Phase 2 of docs/engineering/statement-storage-migration.md. Every caller that used to touch
 * {@code getFileContent()} directly goes through here instead, so the migration's two-state
 * reality — some rows addressed, some still legacy — is handled once rather than re-decided at
 * each of the five call sites.
 *
 * <h2>Storage is optional, and its absence is the safe default</h2>
 * {@code StatementStorage} is injected as an {@link Optional}: with
 * {@code app.statement-storage.provider} unset there is no bean, {@link #store} returns empty, and
 * behaviour is byte-for-byte what it was before this class existed. That is what makes rolling the
 * migration back a config change rather than a deploy.
 *
 * <h2>Dual write, deliberately</h2>
 * A new upload writes its bytes to object storage AND still fills {@code file_content}. That
 * duplication is temporary and intentional: until Phase 3 has backfilled and Phase 4 has dropped
 * the column, the database copy is the thing that makes an object-storage problem recoverable
 * instead of terminal.
 *
 * <h2>Ordering</h2>
 * {@link #store} is called before the row is persisted, so the only possible partial failure is an
 * object with no row — reclaimable by the future sweep. The reverse, a row pointing at an object
 * that was never written, cannot occur, and is the one outcome this design treats as unrecoverable
 * (§5.1 of the migration doc).
 */
@Service
public class StatementContentService {

    private static final Logger log = LoggerFactory.getLogger(StatementContentService.class);

    private final Optional<StatementStorage> storage;

    /**
     * Bug fix: a provider name that matches no implementation used to be indistinguishable from no
     * provider at all. {@code FilesystemStatementStorage} is selected by
     * {@code @ConditionalOnProperty(havingValue = "filesystem")}, so
     * {@code STATEMENT_STORAGE_PROVIDER=r2} -- a typo, or an operator reasonably expecting the R2
     * implementation that has not landed yet -- produced no bean, an empty Optional here, and an
     * INFO line saying storage was simply not configured. The deployment then kept writing every
     * statement to the database while the operator believed the migration was running, with nothing
     * failing and nothing warning.
     *
     * <p>That is exactly the silent-degradation-on-missing-config class this codebase already
     * decided must fail loudly rather than ship quietly -- see {@code SilentProductionFallback} and
     * {@code ProductionConfigValidator}. Unset stays a first-class, supported choice and behaves
     * precisely as before; only a value that was actually typed and matched nothing is rejected,
     * because that is never what anyone intended in any profile.
     */
    public StatementContentService(Optional<StatementStorage> storage,
                                    @Value("${app.statement-storage.provider:}") String configuredProvider) {
        this.storage = storage;
        if (storage.isEmpty() && configuredProvider != null && !configuredProvider.isBlank()) {
            throw new IllegalStateException(
                    "app.statement-storage.provider is set to \"" + configuredProvider + "\", which matches no "
                    + "StatementStorage implementation -- statements would silently keep going to the database. "
                    + "Supported values: \"filesystem\", or leave it unset to keep statement bytes in the database.");
        }
        if (storage.isEmpty()) {
            log.info("No statement storage provider configured -- statement bytes stay in the database "
                    + "(set app.statement-storage.provider to enable object storage)");
        }
    }

    /**
     * Stores the bytes, returning the address to record on the row.
     *
     * Empty when no provider is configured, which the caller records as null columns — a legacy
     * row, read from {@code file_content} exactly as before.
     *
     * Deliberately propagates {@link StatementStorageException} rather than degrading to empty on
     * failure. Swallowing it would persist a row claiming database-only storage while the object
     * may or may not exist, which is precisely the ambiguity the ordering above exists to prevent.
     */
    public Optional<ContentAddress> store(byte[] content) {
        return storage.map(s -> s.store(content));
    }

    /**
     * Reads a statement's bytes, from wherever that particular row keeps them.
     *
     * Prefers object storage when the row carries an address; falls back to the database column
     * otherwise. Both states are normal during the migration — see {@link StoredStatement}.
     */
    public byte[] read(StoredStatement row) {
        if (row.getContentHash() != null && row.getObjectKey() != null && storage.isPresent()) {
            byte[] content = storage.get().retrieve(new ContentAddress(row.getContentHash(), row.getObjectKey()));
            // Verified here rather than inside each StatementStorage implementation: this is the one
            // path every read goes through, so a future R2/S3 provider inherits the guarantee instead
            // of having to remember to re-implement it. A provider CAN also check internally; it
            // cannot be relied on to.
            ContentAddress.requireMatches(content, row.getContentHash(), "statement " + row.getContentHash());
            return content;
        }

        byte[] legacy = row.getFileContent();
        if (legacy == null) {
            // An addressed row with no reachable storage, or a row with neither. Both mean the
            // bytes cannot be produced, and failing loudly beats handing back an empty array that
            // would surface downstream as an unparseable statement.
            throw new StatementStorageException(row.getContentHash() == null
                    ? "Statement row has neither stored content nor a content address"
                    : "Statement " + row.getContentHash() + " is in object storage, but no storage provider is configured");
        }
        return legacy;
    }
}
