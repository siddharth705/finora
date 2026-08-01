package com.finora.config.fixtures;

import com.finora.service.SilentProductionFallback;

/**
 * Deliberately reproduces the second bug shape: implements {@code SilentProductionFallback} but
 * declares a config hint ProductionConfigValidator's source doesn't actually mention. Not
 * production code -- exists only so {@code SilentFallbackConfigValidationTest} can prove it
 * actually detects this. Do not "fix" by adding a matching check to ProductionConfigValidator;
 * that would defeat its purpose.
 */
public class NoOpFixtureWithUncoveredHint implements SilentProductionFallback {

    @Override
    public String requiredConfigHint() {
        return "SOME_CONFIG_KEY_THE_VALIDATOR_DOES_NOT_CHECK";
    }
}
