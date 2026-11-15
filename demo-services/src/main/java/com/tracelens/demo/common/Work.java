package com.tracelens.demo.common;

import java.util.concurrent.ThreadLocalRandom;

/** Simulates work (DB queries, external calls) with a random duration. */
public final class Work {
    private Work() {}

    public static void sleep(int minMs, int maxMs) {
        int ms = minMs >= maxMs ? minMs : ThreadLocalRandom.current().nextInt(minMs, maxMs + 1);
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static boolean chance(double probability) {
        return ThreadLocalRandom.current().nextDouble() < probability;
    }
}
