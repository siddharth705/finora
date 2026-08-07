package com.finora.architecture;

import com.finora.architecture.registry.GuardianRule;
import com.finora.architecture.registry.GuardianSelfTest;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import jakarta.persistence.Basic;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reusable architecture rule, not a point patch.
 *
 * <p>The bug this exists to prevent has already happened once, and it happened <em>next to</em> the
 * code that got it right. {@code StatementImport.fileContent} carries
 * {@code @Basic(fetch = FetchType.LAZY)} and a comment explaining why. {@code ImportSession}
 * stores the same uploaded file, for a shorter lifetime, through the same
 * {@code StoredStatement} interface — and had no fetch strategy at all, so JPA's default for a
 * basic {@code byte[]} applied: EAGER.
 *
 * <p>The consequence was not a slow query in one place. It was that every single load of an
 * {@code ImportSession} row — the expiry check, the resume list, the confirm lookup — carried the
 * entire uploaded statement, bounded only by the 10 MB multipart cap in {@code application.yml}.
 * {@code ImportSessionService.cleanupExpired} is the sharpest version: it selects a batch of
 * expired sessions in order to DELETE them, and was materialising each one's complete file bytes
 * into the JVM heap first — other users' bytes, on the request thread of whichever user happened
 * to trigger the sweep.
 *
 * <p>Fixing that one field does not stop the next entity repeating it. A binary column is exactly
 * the kind of field somebody adds without thinking about fetch strategy, because the annotation
 * that makes it lazy is invisible by its absence: the correct code and the broken code differ by a
 * line that is not there. This test makes the omission fail the build instead.
 *
 * <h2>Why {@code @Lob} is not the answer, and is separately forbidden</h2>
 * {@code StatementImport}'s own comment records it: on PostgreSQL, Hibernate maps
 * {@code @Lob byte[]} to the {@code oid} large-object type rather than {@code bytea}, which stores
 * the payload out-of-line in {@code pg_largeobject} and needs explicit unlinking to reclaim.
 * {@code @Basic(fetch = LAZY)} gets the deferred load without changing the column type, which is
 * why it is the shape this rule requires.
 */
class LazyBinaryColumnTest {

    private JavaClasses productionClasses() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.finora");
    }

    /** Every persistent {@code byte[]} field on an {@code @Entity}, transient/static excluded. */
    private List<JavaField> persistentBinaryFields(JavaClasses classes) {
        List<JavaField> found = new ArrayList<>();
        for (JavaClass type : classes) {
            if (!type.isAnnotatedWith(Entity.class)) continue;
            for (JavaField field : type.getFields()) {
                if (!field.getRawType().isArray()) continue;
                // byte[].class.getName() is the JVM binary name "[B", NOT "byte[]" -- ArchUnit
                // reports getName() in binary form and only getSimpleName() as "byte[]". Comparing
                // against the literal "byte[]" here matched nothing, so the rule below passed while
                // inspecting zero fields. That is exactly the failure ruleIsNotVacuous exists to
                // catch, and it caught it on the first run.
                if (!field.getRawType().getName().equals(byte[].class.getName())) continue;
                if (field.getModifiers().toString().contains("STATIC")) continue;
                if (field.isAnnotatedWith(jakarta.persistence.Transient.class)) continue;
                found.add(field);
            }
        }
        return found;
    }

    @GuardianRule(
            id = "FG-030",
            // CORRECTNESS, not a performance note: this is "framework misuse that is silently
            // wrong rather than merely untidy" exactly as that category defines it. The omission
            // has no visible symptom at the call site -- the code reads identically whether the
            // column is lazy or not, and only heap under load tells you which you got.
            category = GuardianRule.Category.CORRECTNESS,
            intent = "Every persistent byte[] column is @Basic(fetch = LAZY); an omitted fetch "
                    + "strategy silently loads the whole payload on every query of that entity.",
            source = "Bug 12: ImportSession.fileContent was eager, so cleanupExpired materialised "
                    + "other users' 10 MB statement uploads purely to delete them",
            introduced = "2026-08-07",
            owner = "architecture",
            verification = GuardianRule.Verification.SELF_TEST)
    @Test
    @DisplayName("every persistent byte[] column declares a LAZY fetch strategy")
    void binaryColumnsAreLazilyFetched() {
        List<String> offenders = new ArrayList<>();

        for (JavaField field : persistentBinaryFields(productionClasses())) {
            String where = field.getOwner().getSimpleName() + "." + field.getName();
            if (!field.isAnnotatedWith(Basic.class)) {
                offenders.add(where + " has no @Basic, so JPA's EAGER default applies");
                continue;
            }
            if (field.getAnnotationOfType(Basic.class).fetch() != FetchType.LAZY) {
                offenders.add(where + " is @Basic(fetch = EAGER)");
            }
        }

        assertThat(offenders)
                .as("A byte[] column with no fetch strategy is loaded on EVERY query of its "
                        + "entity, including queries that only need an id or a timestamp. Add "
                        + "@Basic(fetch = FetchType.LAZY) -- not @Lob, which changes the "
                        + "PostgreSQL column type to oid (see StatementImport.fileContent).")
                .isEmpty();
    }

    /**
     * Proves the rule is not vacuous.
     *
     * <p>A rule that scans for a field shape can pass because it found nothing to check — which is
     * indistinguishable, in a green build, from finding everything correct. This asserts the scan
     * actually reaches the two entities that hold statement bytes, so deleting or renaming them
     * fails here rather than quietly turning the rule above into a no-op.
     */
    @GuardianSelfTest(rule = "FG-030")
    @Test
    @DisplayName("the rule actually inspects the known binary columns")
    void ruleIsNotVacuous() {
        List<String> inspected = persistentBinaryFields(productionClasses()).stream()
                .map(f -> f.getOwner().getSimpleName() + "." + f.getName())
                .toList();

        assertThat(inspected)
                .as("if this scan finds nothing, the rule above passes without checking anything")
                .contains("StatementImport.fileContent", "ImportSession.fileContent");
    }
}
