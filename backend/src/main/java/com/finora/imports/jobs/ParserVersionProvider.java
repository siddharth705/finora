package com.finora.imports.jobs;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The one source of "what parser build is running right now."
 *
 * <p>Railway injects RAILWAY_GIT_COMMIT_SHA on every deploy, the same source {@code sentry.release}
 * already uses, so this needs no dashboard configuration. Blank locally and in tests, which is
 * honest -- there is no deploy to name.
 *
 * <p>Deliberately a different source from {@code BuildVersionResolver.currentCommit()}: that one
 * prefers Spring Boot's {@code GitProperties} (typically unavailable in this Docker build, per its
 * own doc) and falls back to {@code app.build.commit}, which is not wired to
 * {@code RAILWAY_GIT_COMMIT_SHA} anywhere in this codebase -- the two can disagree.
 * {@code HeldStatement.parserVersion} is stamped from THIS class, via {@link ImportJobWorker}, so
 * anything comparing against it (a parser re-run) has to read the same source back.
 *
 * <p>Extracted from {@code ImportJobWorker}'s own private field (Plan 3 of the Held Statement
 * Review System) specifically so a second caller never has to duplicate the expression -- a
 * duplicated {@code @Value} default is exactly the kind of "the same fact stated twice, and the
 * copies drift" bug this codebase has hit before ({@code @PreAuthorize} class-level-vs-method-level,
 * {@code readOnly} on a write-performing method) in different shapes.
 */
@Component
public class ParserVersionProvider {

    @Value("${app.parser-version:${RAILWAY_GIT_COMMIT_SHA:}}")
    private String version;

    public String current() {
        return version;
    }
}
