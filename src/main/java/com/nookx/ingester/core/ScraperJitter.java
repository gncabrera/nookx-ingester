package com.nookx.ingester.core;

import java.util.concurrent.ThreadLocalRandom;

public final class ScraperJitter {

    private ScraperJitter() {
        throw new UnsupportedOperationException("This class should never be instantiated");
    }

    public static long pickJitter(final long maxJitterMs) {
        if (maxJitterMs <= 0) {
            return 0L;
        }
        return ThreadLocalRandom.current().nextLong(0, maxJitterMs + 1);
    }
}
