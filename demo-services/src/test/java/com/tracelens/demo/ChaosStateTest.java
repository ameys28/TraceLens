package com.tracelens.demo;

import com.tracelens.demo.common.ChaosState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChaosStateTest {

    @Test
    void noChaosByDefault() {
        ChaosState chaos = new ChaosState();
        chaos.apply("op"); // must not throw
        assertThat(chaos.latencyMs()).isZero();
    }

    @Test
    void errorRateOfOneAlwaysFails() {
        ChaosState chaos = new ChaosState();
        chaos.set(0, 1.0);
        assertThatThrownBy(() -> chaos.apply("op")).isInstanceOf(ChaosState.ChaosException.class);
    }

    @Test
    void valuesAreClamped() {
        ChaosState chaos = new ChaosState();
        chaos.set(-5, 7.0);
        assertThat(chaos.latencyMs()).isZero();
        assertThat(chaos.errorRate()).isEqualTo(1.0);
    }

    @Test
    void latencyIsInjected() {
        ChaosState chaos = new ChaosState();
        chaos.set(50, 0);
        long start = System.nanoTime();
        chaos.apply("op");
        assertThat((System.nanoTime() - start) / 1_000_000).isGreaterThanOrEqualTo(50);
    }
}
