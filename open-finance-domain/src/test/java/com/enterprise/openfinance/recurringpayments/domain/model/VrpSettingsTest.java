package com.enterprise.openfinance.recurringpayments.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VrpSettingsTest {

    @Test
    void shouldCreateValidSettings() {
        VrpSettings settings = new VrpSettings(Duration.ofHours(24), Duration.ofSeconds(30));

        assertThat(settings.idempotencyTtl()).isEqualTo(Duration.ofHours(24));
        assertThat(settings.cacheTtl()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void shouldRejectInvalidSettings() {
        assertThatThrownBy(() -> new VrpSettings(Duration.ZERO, Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("idempotencyTtl");

        assertThatThrownBy(() -> new VrpSettings(Duration.ofHours(24), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cacheTtl");
    }
}
