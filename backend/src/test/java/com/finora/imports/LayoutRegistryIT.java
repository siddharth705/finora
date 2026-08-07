package com.finora.imports;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.RegisteredLayout;
import com.finora.repository.RegisteredLayoutRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * The layout registry's guarantees (V68), against a real Postgres.
 *
 * <p>Testcontainers rather than an in-memory database, and not as a formality: everything asserted
 * here is PostgreSQL behaviour that a substitute would either lack or fake. {@code ON CONFLICT DO
 * UPDATE} is the entire concurrency design, {@code LEAST}/{@code GREATEST}/{@code COALESCE} in the
 * conflict clause are what keep an out-of-order observation from corrupting a timestamp, and the
 * {@code CHECK} constraint is the only thing standing between the status column and a hand-written
 * production {@code UPDATE}. None of it is testable against a mock, and all of it fails silently.
 *
 * <p>The tests use a fresh random fingerprint each time rather than cleaning the table. The
 * container is shared across every IT class in the JVM and never reset, so a test that asserted on
 * the whole registry would be asserting on whatever else had run first.
 */
class LayoutRegistryIT extends AbstractIntegrationTest {

    @Autowired private RegisteredLayoutRepository repository;
    @Autowired private LayoutRegistryService registryService;
    @Autowired private LayoutIntelligenceService intelligenceService;
    @Autowired private DataSource dataSource;

    /** Same 13-character shape as a real fingerprint, distinct per test. */
    private String fingerprint() {
        return "FP-T-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private RegisteredLayout reload(String fingerprint) {
        return repository.findByFingerprint(fingerprint).orElseThrow();
    }

    // ------------------------------------------------------------------ observation

    @Test
    void aLayoutNobodyHasSeenBeforeBecomesARowTheFirstTimeItIsImported() {
        String fingerprint = fingerprint();
        Instant seenAt = Instant.now();

        repository.observe(fingerprint, "PDF", "PdfPreviewGenerator", seenAt);

        RegisteredLayout layout = reload(fingerprint);
        assertThat(layout.getStatus()).isEqualTo(RegisteredLayout.Status.OBSERVED);
        assertThat(layout.getSourceFormat()).isEqualTo("PDF");
        assertThat(layout.getParser()).isEqualTo("PdfPreviewGenerator");
        assertThat(layout.getObservationCount()).isEqualTo(1);
        // Unnamed, deliberately: a generated placeholder would be indistinguishable from a name an
        // operator actually chose, and "how many layouts have we identified" would stop being a
        // question the registry can answer.
        assertThat(layout.getName()).isNull();
    }

    @Test
    void seeingALayoutAgainAdvancesTheObservationWithoutCreatingASecondRow() {
        String fingerprint = fingerprint();
        Instant first = Instant.now().minus(Duration.ofDays(30));
        Instant later = Instant.now();

        repository.observe(fingerprint, "PDF", "PdfPreviewGenerator", first);
        repository.observe(fingerprint, "PDF", "PdfPreviewGenerator", later);

        RegisteredLayout layout = reload(fingerprint);
        assertThat(layout.getObservationCount()).isEqualTo(2);
        assertThat(layout.getFirstSeen()).isCloseTo(first, within(1, ChronoUnit.SECONDS));
        assertThat(layout.getLastSeen()).isCloseTo(later, within(1, ChronoUnit.SECONDS));
    }

    @Test
    void anObservationArrivingOutOfOrderCannotDragTheTimestampsBackwards() {
        // Two workers finishing in the wrong order, or a backdated import. Plain assignment would
        // let the later-arriving-but-earlier observation overwrite last_seen, and a layout that is
        // being used weekly would report as dormant.
        String fingerprint = fingerprint();
        Instant recent = Instant.now();
        Instant ancient = recent.minus(Duration.ofDays(400));

        repository.observe(fingerprint, "PDF", "PdfPreviewGenerator", recent);
        repository.observe(fingerprint, "PDF", "PdfPreviewGenerator", ancient);

        RegisteredLayout layout = reload(fingerprint);
        assertThat(layout.getFirstSeen()).isCloseTo(ancient, within(1, ChronoUnit.SECONDS));
        assertThat(layout.getLastSeen()).isCloseTo(recent, within(1, ChronoUnit.SECONDS));
    }

    @Test
    void anImportWithUnreadableMetadataDoesNotErasePreviouslyObservedDetail() {
        // LayoutRegistryService passes a null parser when the metadata JSON will not parse. The
        // COALESCE in the conflict clause is what keeps one malformed blob from blanking a column
        // every previous import agreed on.
        String fingerprint = fingerprint();
        repository.observe(fingerprint, "CSV", "CsvParser", Instant.now());

        repository.observe(fingerprint, null, null, Instant.now());

        RegisteredLayout layout = reload(fingerprint);
        assertThat(layout.getParser()).isEqualTo("CsvParser");
        assertThat(layout.getSourceFormat()).isEqualTo("CSV");
    }

    @Test
    void concurrentFirstSightingsOfTheSameLayoutProduceOneRowAndLoseNothing() {
        // The property the whole design rests on. Read-then-insert would have every one of these
        // threads read "absent", and all but one would hit fingerprint UNIQUE -- which, on the path
        // this is called from, is a user's import failing because somebody else was importing a
        // statement from the same bank at the same moment.
        String fingerprint = fingerprint();
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Integer>> observations = java.util.Collections.nCopies(threads,
                    () -> repository.observe(fingerprint, "PDF", "PdfPreviewGenerator", Instant.now()));
            pool.invokeAll(observations).forEach(future -> {
                try {
                    future.get(30, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new AssertionError("An observation failed instead of merging: " + e, e);
                }
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        } finally {
            pool.shutdownNow();
        }

        assertThat(reload(fingerprint).getObservationCount()).isEqualTo(threads);
    }

    // ------------------------------------------------------------------ curation

    @Test
    void curationSurvivesEveryLaterImportOfTheLayout() {
        // The single most important invariant in this table. If an observation reset name or
        // status, an operator's work would be undone by the next import of the layout they had just
        // approved -- silently, and worse the more successful the layout is.
        String fingerprint = fingerprint();
        repository.observe(fingerprint, "PDF", "PdfPreviewGenerator", Instant.now());
        registryService.rename(fingerprint, "HDFC Savings (PDF)");
        registryService.moveTo(fingerprint, RegisteredLayout.Status.SUPPORTED);

        repository.observe(fingerprint, "PDF", "PdfPreviewGenerator", Instant.now());

        RegisteredLayout after = reload(fingerprint);
        assertThat(after.getName()).isEqualTo("HDFC Savings (PDF)");
        assertThat(after.getStatus()).isEqualTo(RegisteredLayout.Status.SUPPORTED);
        // ...while the observed half still moved, which is what makes the split meaningful rather
        // than merely the write being skipped.
        assertThat(after.getObservationCount()).isEqualTo(2);
    }

    @Test
    void aBlankNameIsStoredAsUnnamedRatherThanAsAnEmptyString() {
        String fingerprint = fingerprint();
        repository.observe(fingerprint, "PDF", "PdfPreviewGenerator", Instant.now());
        registryService.rename(fingerprint, "HDFC Savings (PDF)");

        registryService.rename(fingerprint, "   ");

        // "" and "never named" are the same state to every reader; storing both means every caller
        // has to remember to check for both, and one eventually will not.
        assertThat(reload(fingerprint).getName()).isNull();
    }

    @Test
    void curatingAlayoutThatWasNeverImportedIsRefusedRatherThanCreatingIt() {
        // Registering on curation would put a layout in the "we have encountered this" list that
        // nothing ever encountered -- which is the one thing this table is the authority on.
        assertThatThrownBy(() -> registryService.rename(fingerprint(), "Wishful Thinking"))
                .isInstanceOf(com.finora.exception.ApiException.class)
                .hasMessageContaining("No layout is registered");
    }

    @Test
    void namingALayoutDoesNotRollBackAnObservationThatArrivedWhileItWasBeingNamed() {
        // @DynamicUpdate is what makes this hold. Hibernate's default UPDATE writes every column,
        // so a curation would write observation_count and last_seen back as they were when the row
        // was read -- silently discarding anything that landed in between.
        String fingerprint = fingerprint();
        repository.observe(fingerprint, "PDF", "PdfPreviewGenerator", Instant.now());
        long countBefore = reload(fingerprint).getObservationCount();

        registryService.rename(fingerprint, "HDFC Savings (PDF)");
        repository.observe(fingerprint, "PDF", "PdfPreviewGenerator", Instant.now());
        registryService.moveTo(fingerprint, RegisteredLayout.Status.SUPPORTED);

        RegisteredLayout after = reload(fingerprint);
        assertThat(after.getObservationCount()).isEqualTo(countBefore + 1);
        assertThat(after.getName()).isEqualTo("HDFC Savings (PDF)");
        assertThat(after.getStatus()).isEqualTo(RegisteredLayout.Status.SUPPORTED);
    }

    @Test
    void theDatabaseRefusesAStatusTheApplicationDoesNotDefine() {
        // The enum guards the Java path only. This constraint is what stands between the status
        // column and a hand-written production UPDATE -- and status is the column the corpus gate
        // will read to decide which layouts Finora claims to support.
        String fingerprint = fingerprint();
        repository.observe(fingerprint, "PDF", "PdfPreviewGenerator", Instant.now());

        assertThatThrownBy(() -> {
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE layout_registry SET status = 'PROBABLY_FINE' "
                        + "WHERE fingerprint = '" + fingerprint + "'");
            }
        }).hasMessageContaining("layout_registry_status_valid");
    }

    // ------------------------------------------------------------------ what the intelligence layer sees

    @Test
    void aRegisteredLayoutIsVisibleToTheIntelligenceLayerBeforeAnyImportSurvivesToBeAggregated() {
        // The end-to-end shape of "reads from something curated rather than inferring from
        // aggregates": this layout has no statement_imports rows at all -- the state a layout
        // reaches once every statement that produced it has been deleted -- and it is still
        // reported, named, with the first/last-seen the registry kept.
        String fingerprint = fingerprint();
        Instant firstSeen = Instant.now().minus(200, ChronoUnit.DAYS);
        repository.observe(fingerprint, "CSV", "CsvParser", firstSeen);
        registryService.rename(fingerprint, "PNB Current (CSV)");
        registryService.moveTo(fingerprint, RegisteredLayout.Status.SUPPORTED);

        LayoutIntelligenceService.LayoutSummary summary = intelligenceService.layoutOverview().stream()
                .filter(s -> fingerprint.equals(s.fingerprint()))
                .findFirst().orElseThrow();

        assertThat(summary.name()).isEqualTo("PNB Current (CSV)");
        assertThat(summary.status()).isEqualTo("SUPPORTED");
        assertThat(summary.parser()).isEqualTo("CsvParser");
        assertThat(summary.usageCount()).isZero();
        assertThat(summary.firstSeen()).isCloseTo(firstSeen, within(1, ChronoUnit.SECONDS));
    }

    @Test
    void observingWithoutAFingerprintRegistersNothing() {
        // Every import that failed before it had any structure to hash, and the whole direct-file
        // confirm path. A phantom row keyed on null or "" would be counted as a layout forever.
        long before = repository.count();

        registryService.observe(null, "PDF", null);
        registryService.observe("  ", "PDF", null);

        assertThat(repository.count()).isEqualTo(before);
    }
}
