package com.finora.health;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StorageHealthProviderTest {

    @Test
    void check_reportsUp_whenR2Configured() {
        StorageHealthProvider provider = new StorageHealthProvider("r2");

        HealthCheckResult result = provider.check();

        assertThat(result.status()).isEqualTo(HealthStatus.UP);
        assertThat(result.detail()).contains("r2");
    }

    @Test
    void check_reportsUp_whenFilesystemConfigured() {
        StorageHealthProvider provider = new StorageHealthProvider("filesystem");

        HealthCheckResult result = provider.check();

        assertThat(result.status()).isEqualTo(HealthStatus.UP);
        assertThat(result.detail()).contains("filesystem");
    }

    @Test
    void check_reportsDown_whenUnset() {
        StorageHealthProvider provider = new StorageHealthProvider("");

        HealthCheckResult result = provider.check();

        assertThat(result.status()).isEqualTo(HealthStatus.DOWN);
    }
}
