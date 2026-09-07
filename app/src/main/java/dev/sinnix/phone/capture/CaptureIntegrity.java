package dev.sinnix.phone.capture;

public final class CaptureIntegrity {
    private CaptureIntegrity() {}

    // Allow AAC padding and stop latency, not multi-second gaps.
    public static boolean complete(long elapsedMs, Long mediaMs) {
        return mediaMs != null && mediaMs > 0 && Math.abs(elapsedMs - mediaMs) <= 2000;
    }

    // Conservative recovery trigger for 96 kbps AAC. Duration verifies coverage.
    public static boolean underproducing(long elapsedMs, long bytes) {
        return elapsedMs >= 60000 && bytes < (elapsedMs - 10000) * 6;
    }
}
