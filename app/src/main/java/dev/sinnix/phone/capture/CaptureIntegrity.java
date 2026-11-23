package dev.sinnix.phone.capture;

public final class CaptureIntegrity {
    private CaptureIntegrity() {}

    // Allow encoder padding and stop latency, not multi-second gaps.
    public static boolean complete(long elapsedMs, Long mediaMs) {
        return mediaMs != null && mediaMs > 0 && Math.abs(elapsedMs - mediaMs) <= 2000;
    }

    // A stalled encoder, not a quiet room: variable-rate Opus spends less
    // than its nominal rate on silence, so the floor is 1 kbps. Duration
    // verifies coverage.
    public static boolean underproducing(long elapsedMs, long bytes) {
        return elapsedMs >= 60000 && bytes < (elapsedMs - 10000) / 8;
    }
}
