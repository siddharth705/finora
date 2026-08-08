package com.finora.diagnostics;

import com.finora.security.JwtService;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.opencsv.CSVReader;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Proves that each critical third-party library is actually EXECUTED by the packaged jar, not
 * merely that the application started.
 *
 * <p>Runs only when the jar is launched with {@code --verify-runtime-dependencies}, then reports
 * and exits. It is never reachable over HTTP and adds no endpoint, so there is no surface to keep
 * gated in production.
 *
 * <h2>Why this exists</h2>
 *
 * On 2026-08-08 production could not start: {@code httpclient5} was declared
 * {@code <scope>test</scope>}, which Maven resolves as overriding the compile-scoped copy
 * firebase-admin needs, so the fat jar shipped without it and every boot died on
 * {@code NoClassDefFoundError}. CI could not see it because the backend job runs {@code mvn test},
 * where the artifact is present regardless of scope, while the image is built by
 * {@code mvn package}, where it was not.
 *
 * <p>A CI step now boots the packaged jar and fails on a missing class, which closes the STARTUP
 * case. This class closes the one after it. A library whose classes load lazily can start clean and
 * then fail on the first real call — the same defect one layer later, and the layer a user actually
 * reaches. Booting proves the beans wired; only calling proves the classpath is complete for the
 * code path behind them.
 *
 * <h2>The rule each check follows</h2>
 *
 * A check passes when the library's own code RAN. It does not have to succeed. A linkage error is
 * thrown during class resolution, before any credential check, network call or validation, so a
 * call that legitimately fails still proves the classpath — {@code verifyIdToken} rejecting a
 * garbage token is a PASS, because reaching the rejection means every class behind it resolved.
 *
 * <p>The third outcome is the one that matters most, and it is why {@link Outcome} has three values
 * rather than two. A check that quietly stops reaching its library would otherwise keep passing
 * while testing nothing — precisely the failure this codebase has been bitten by before, where a
 * suite stayed green over a component it had stopped covering. {@code NOT_EXECUTED} is therefore a
 * FAILURE, not a skip: if Firebase is unconfigured, {@code verifyAndGetPhoneNumber} short-circuits
 * before the SDK is touched, and a guard that reported "fine" there would be lying. A check must be
 * able to say it has become meaningless, and that must break the build.
 *
 * <h2>Adding a dependency</h2>
 *
 * Add one entry to {@link #checks()}. The bar for inclusion is not "we depend on it" but "a missing
 * class here would reach a user": something whose classes load lazily, or that arrives through a
 * scope or a transitive path that could be broken without the compiler noticing.
 */
@Component
public class RuntimeDependencyVerifier implements ApplicationRunner {

    /** The flag that turns this on. Absent, the bean does nothing and the app runs normally. */
    public static final String FLAG = "verify-runtime-dependencies";

    private static final Logger log = LoggerFactory.getLogger(RuntimeDependencyVerifier.class);

    /**
     * What a check learned. Only {@link #EXECUTED} passes.
     *
     * <p>{@code NOT_EXECUTED} exists so a check can report that it has stopped testing what it
     * claims to test. Folding it into a pass would make this whole class capable of going quietly
     * useless; folding it into {@code MISSING_CLASS} would send someone hunting a packaging bug
     * that is not there.
     */
    public enum Outcome {
        /** The library's code ran. Its own success or failure is irrelevant. */
        EXECUTED,
        /** A class the library needs is not in the jar. This is the packaging defect. */
        MISSING_CLASS,
        /** The call short-circuited before reaching the library, so this check proved nothing. */
        NOT_EXECUTED
    }

    public record Result(String dependency, Outcome outcome, String detail) {}

    /** One dependency's proof-of-execution. Throwing is normal: see {@link #run(Check)}. */
    @FunctionalInterface
    interface Probe {
        /** @return a short description of what ran, when the library completed without throwing. */
        String execute() throws Exception;
    }

    record Check(String dependency, Probe probe) {}

    private final Optional<FirebaseApp> firebaseApp;
    private final JwtService jwtService;
    private final ApplicationContext context;

    public RuntimeDependencyVerifier(Optional<FirebaseApp> firebaseApp, JwtService jwtService,
                                      ApplicationContext context) {
        this.firebaseApp = firebaseApp;
        this.jwtService = jwtService;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption(FLAG) && !args.getNonOptionArgs().contains("--" + FLAG)) {
            return;
        }

        List<Result> results = new ArrayList<>();
        for (Check check : checks()) {
            results.add(run(check));
        }

        report(results);
        boolean allExecuted = results.stream().allMatch(r -> r.outcome() == Outcome.EXECUTED);
        System.exit(SpringApplication.exit(context, () -> allExecuted ? 0 : 1));
    }

    /**
     * Runs one probe and classifies what happened.
     *
     * <p>Catching {@link Throwable} is deliberate and is the point of the method: a
     * {@code NoClassDefFoundError} is an {@link Error}, not an {@link Exception}, which is exactly
     * why the production incident sailed past {@code FirebaseConfig}'s own
     * {@code catch (IOException)} and killed the context. Anything that is not a linkage failure is
     * the library running and objecting to synthetic input, which is a pass.
     */
    Result run(Check check) {
        try {
            return new Result(check.dependency(), Outcome.EXECUTED, check.probe().execute());
        } catch (VacuousCheckException e) {
            return new Result(check.dependency(), Outcome.NOT_EXECUTED, e.getMessage());
        } catch (Throwable t) {
            Throwable linkage = linkageFailureIn(t);
            if (linkage != null) {
                return new Result(check.dependency(), Outcome.MISSING_CLASS,
                        linkage.getClass().getSimpleName() + ": " + linkage.getMessage());
            }
            return new Result(check.dependency(), Outcome.EXECUTED,
                    "threw " + t.getClass().getSimpleName() + ", which means it ran");
        }
    }

    /**
     * The linkage failure anywhere in {@code t}'s cause chain, or null if there is none.
     *
     * <p>Walks the chain rather than catching the error type directly, because libraries routinely
     * WRAP a missing class in an exception of their own. Found by testing this class against a jar
     * built without jjwt-impl: jjwt reports
     * {@code UnknownClassException: Unable to load class named [io.jsonwebtoken.impl.security.KeysBridge]}
     * rather than letting a NoClassDefFoundError escape. Matching on the thrown type alone graded
     * that as "the library ran and objected", which is a FALSE PASS on exactly the defect this
     * class exists to catch.
     *
     * <p>Known residual gap, stated rather than papered over: a library that neither throws nor
     * wraps a linkage error -- one that catches it internally and returns a value, or reports it
     * only in a message -- is invisible here. Nothing in the type system can find that, and the
     * honest mitigation is the layer below: the CI step that boots the packaged jar catches a
     * missing dependency whose absence breaks startup, which is how the jjwt case above was
     * actually caught.
     */
    private Throwable linkageFailureIn(Throwable t) {
        for (Throwable cause = t; cause != null; cause = cause.getCause()) {
            if (cause instanceof NoClassDefFoundError
                    || cause instanceof ClassNotFoundException
                    || cause instanceof NoSuchMethodError
                    || cause instanceof UnsatisfiedLinkError) {
                return cause;
            }
            if (cause.getCause() == cause) {
                break; // self-referential cause; some libraries do this
            }
        }
        return null;
    }

    /** Thrown by a probe that could not reach its library, so its result would be meaningless. */
    static class VacuousCheckException extends RuntimeException {
        VacuousCheckException(String message) {
            super(message);
        }
    }

    List<Check> checks() {
        return List.of(
                // The library from the incident. Initialization alone is not enough: verifyIdToken
                // resolves transport and token-verification classes that FirebaseOptions.build()
                // never touches, which is the gap between "the app started" and "phone verification
                // works". A garbage token is correct input here -- it is rejected AFTER the classes
                // load, so rejection is the proof.
                new Check("firebase-admin", () -> {
                    FirebaseApp app = firebaseApp.orElseThrow(() -> new VacuousCheckException(
                            "no FirebaseApp bean, so verifyIdToken is never reached -- set "
                                    + "GOOGLE_APPLICATION_CREDENTIALS before running this check"));
                    FirebaseAuth.getInstance(app).verifyIdToken("not-a-real-token");
                    return "verifyIdToken accepted a token it should have rejected";
                }),

                // jjwt-impl and jjwt-jackson are <scope>runtime</scope> in the pom, which is the
                // same shape as the httpclient5 defect: the compiler never references them, so
                // nothing fails at build time, and jjwt-api alone compiles and starts fine. A break
                // here surfaces on the first token operation -- i.e. on a user's first login, not
                // on deploy.
                new Check("jjwt", () -> {
                    String token = jwtService.generateToken(
                            UUID.randomUUID(), "runtime-check@example.invalid", UUID.randomUUID(), "SCOPE_USER");
                    return "signed a token of " + token.length() + " chars";
                }),

                // PDFBox loads font, codec and parser classes on demand rather than at startup, so
                // the statement import path can be broken in a jar that boots perfectly. Writing
                // then reading exercises both directions.
                new Check("pdfbox", () -> {
                    byte[] pdf;
                    try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                        doc.addPage(new PDPage());
                        doc.save(out);
                        pdf = out.toByteArray();
                    }
                    try (PDDocument reloaded = Loader.loadPDF(pdf)) {
                        return "wrote and re-read a " + pdf.length + "-byte document, "
                                + reloaded.getNumberOfPages() + " page(s)";
                    }
                }),

                // The other half of statement import.
                new Check("opencsv", () -> {
                    try (CSVReader reader = new CSVReader(new StringReader("date,amount\n01/07/2026,100.00\n"))) {
                        return "parsed " + reader.readAll().size() + " rows";
                    }
                })
        );
    }

    private void report(List<Result> results) {
        log.info("Runtime dependency verification: proving each library EXECUTES, not that the app starts.");
        for (Result r : results) {
            String line = String.format("  %-16s %-14s %s", r.dependency(), r.outcome(), r.detail());
            if (r.outcome() == Outcome.EXECUTED) {
                log.info(line);
            } else {
                log.error(line);
            }
        }
        results.stream().filter(r -> r.outcome() == Outcome.MISSING_CLASS).findAny().ifPresent(r ->
                log.error("A class this jar needs at runtime is not in it. Check backend/pom.xml for a "
                        + "<scope> that excludes {} from the packaged artifact.", r.dependency()));
        results.stream().filter(r -> r.outcome() == Outcome.NOT_EXECUTED).findAny().ifPresent(r ->
                log.error("A check could not reach its library, so it proved nothing. Treat this as a "
                        + "failure of the CHECK, not of {} -- fix the check's preconditions or it will "
                        + "keep passing while testing nothing.", r.dependency()));
    }
}
