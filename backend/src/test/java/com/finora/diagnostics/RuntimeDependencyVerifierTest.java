package com.finora.diagnostics;

import com.finora.diagnostics.RuntimeDependencyVerifier.Check;
import com.finora.diagnostics.RuntimeDependencyVerifier.Outcome;
import com.finora.diagnostics.RuntimeDependencyVerifier.Result;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Tests the CLASSIFIER, not the libraries. Whether pdfbox works is the packaged jar's business and
 * is settled by running it; what needs pinning here is that each outcome is reached for the right
 * reason -- two of the three are failures, and one of those exists only to stop this class going
 * quietly useless.
 */
class RuntimeDependencyVerifierTest {

    private final RuntimeDependencyVerifier verifier = new RuntimeDependencyVerifier(
            Optional.empty(),
            mock(com.finora.security.JwtService.class),
            mock(org.springframework.context.ApplicationContext.class));

    @Test
    void aProbeThatCompletes_isExecuted() {
        Result r = verifier.run(new Check("lib", () -> "did the thing"));

        assertThat(r.outcome()).isEqualTo(Outcome.EXECUTED);
        assertThat(r.detail()).isEqualTo("did the thing");
    }

    @Test
    void aProbeThatThrowsItsOwnException_isStillExecuted() {
        // The rule the whole design rests on: a library rejecting synthetic input has RUN, and a
        // linkage error would have been thrown before it got the chance to object. Treating this as
        // a failure would mean every check needed valid production credentials to pass, which is
        // exactly what makes this runnable in CI at all.
        Result r = verifier.run(new Check("lib", () -> {
            throw new IllegalArgumentException("that token is garbage");
        }));

        assertThat(r.outcome()).isEqualTo(Outcome.EXECUTED);
        assertThat(r.detail()).contains("IllegalArgumentException").contains("it ran");
    }

    @Test
    void aMissingClass_isReportedAsMissingClass_notAsTheLibraryRunning() {
        // NoClassDefFoundError is an Error, not an Exception. That distinction IS the production
        // incident: FirebaseConfig's catch (IOException) did not stop it, so it propagated and
        // killed the context. A catch here that only took Exception would classify the very defect
        // this class exists to find as a pass.
        Result r = verifier.run(new Check("lib", () -> {
            throw new NoClassDefFoundError("org/apache/hc/client5/http/nio/AsyncClientConnectionManager");
        }));

        assertThat(r.outcome()).isEqualTo(Outcome.MISSING_CLASS);
        assertThat(r.detail()).contains("AsyncClientConnectionManager");
    }

    @Test
    void noSuchMethodError_isAlsoAPackagingFault() {
        // The version-skew sibling of a missing class: the jar is present, but not the one the code
        // was compiled against. Same root cause, same fix, so it reports the same way.
        Result r = verifier.run(new Check("lib", () -> {
            throw new NoSuchMethodError("com.example.Api.method()");
        }));

        assertThat(r.outcome()).isEqualTo(Outcome.MISSING_CLASS);
    }

    @Test
    void aCheckThatCannotReachItsLibrary_failsRatherThanPassing() {
        // The property that stops this class becoming decoration. A probe whose preconditions are
        // absent proved nothing, and saying so has to break the build -- otherwise it keeps
        // reporting success over a library it stopped touching.
        Result r = verifier.run(new Check("firebase-admin", () -> {
            throw new RuntimeDependencyVerifier.VacuousCheckException("no FirebaseApp bean");
        }));

        assertThat(r.outcome()).isEqualTo(Outcome.NOT_EXECUTED);
        assertThat(r.outcome()).isNotEqualTo(Outcome.EXECUTED);
    }

    @Test
    void theRealLibraryProbes_actuallyExecute() {
        // Runs the committed probes for the libraries needing no external configuration. Fails if
        // someone adds a probe that cannot run, which would otherwise only surface in CI.
        List<Check> selfContained = verifier.checks().stream()
                .filter(c -> !c.dependency().equals("firebase-admin") && !c.dependency().equals("jjwt"))
                .toList();

        assertThat(selfContained).isNotEmpty();
        assertThat(selfContained).allSatisfy(c ->
                assertThat(verifier.run(c).outcome())
                        .as("%s must actually execute", c.dependency())
                        .isEqualTo(Outcome.EXECUTED));
    }

    @Test
    void firebaseReportsItselfVacuous_whenNoCredentialsAreConfigured() {
        // This verifier is constructed with Optional.empty(), i.e. the situation the CI smoke job
        // was in for four days. The firebase probe must say it proved nothing rather than pass.
        Check firebase = verifier.checks().stream()
                .filter(c -> c.dependency().equals("firebase-admin")).findFirst().orElseThrow();

        assertThat(verifier.run(firebase).outcome()).isEqualTo(Outcome.NOT_EXECUTED);
    }

    @Test
    void aLinkageFailureWrappedByTheLibrary_isStillAPackagingFault() {
        // Found by testing this class against a jar built without jjwt-impl. jjwt does not let a
        // NoClassDefFoundError escape -- it catches and rethrows its own UnknownClassException. An
        // earlier version of the classifier matched on the thrown type alone and graded that
        // "the library ran and objected", i.e. a false pass on the exact defect this exists to
        // catch. The chain is walked now.
        Result r = verifier.run(new Check("lib", () -> {
            throw new IllegalStateException("Unable to load class named [x.y.KeysBridge]",
                    new ClassNotFoundException("x.y.KeysBridge"));
        }));

        assertThat(r.outcome()).isEqualTo(Outcome.MISSING_CLASS);
        assertThat(r.detail()).contains("KeysBridge");
    }

    @Test
    void aSelfReferentialCauseChain_doesNotHang() {
        // Some libraries initialise a throwable as its own cause. Walking the chain naively spins.
        Result r = verifier.run(new Check("lib", () -> {
            throw new RuntimeException("boom") {
                @Override public synchronized Throwable getCause() { return this; }
            };
        }));

        assertThat(r.outcome()).isEqualTo(Outcome.EXECUTED);
    }

    @Test
    void everyDependencyIsNamedExactlyOnce() {
        assertThat(verifier.checks()).extracting(Check::dependency).doesNotHaveDuplicates();
    }
}
