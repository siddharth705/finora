package com.finora.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

/**
 * One shared {@code com.finora} classpath scan for every architecture test, instead of each of the
 * dozen-plus classes in this package running its own. The scan is deterministic for a given
 * compiled classpath and {@link JavaClasses} is an immutable snapshot -- safe to compute once,
 * cache, and read concurrently from every class in this package's already-parallel unit-test
 * execution (see {@code junit-platform.properties}: {@code *Test.java} classes here run
 * concurrently, not isolated). Measured at ~220s spent on this exact redundant scan, repeated
 * across 11 classes, out of a ~1170s total backend suite -- this is that scan done once instead.
 */
public final class ProductionClasses {

    public static final JavaClasses INSTANCE = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.finora");

    private ProductionClasses() {
    }
}
