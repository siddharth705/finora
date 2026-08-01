package com.finora.config.fixtures;

/**
 * Deliberately reproduces the bug shape: a class named {@code NoOp*} that does NOT implement
 * {@code SilentProductionFallback}. Not production code -- exists only so
 * {@code SilentFallbackConfigValidationTest} can prove it actually detects this. Do not "fix" by
 * adding the interface; that would defeat its purpose.
 */
public class NoOpFixtureMissingInterface {
}
