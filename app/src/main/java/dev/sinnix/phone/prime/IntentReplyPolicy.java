package dev.sinnix.phone.prime;

/** Execution evidence, independently of HTTP reachability or duplicate delivery. */
public final class IntentReplyPolicy {
    public enum Decision { COMPLETED, RETRY, PARTIAL, HOLD }

    private IntentReplyPolicy() {}

    public static Decision decide(Object ok, Object outcome, Object kind, String expectedKind) {
        if (!(ok instanceof Boolean) || !expectedKind.equals(kind)) return Decision.HOLD;
        if (Boolean.TRUE.equals(ok) && "completed".equals(outcome)) return Decision.COMPLETED;
        if (Boolean.FALSE.equals(ok)) {
            if ("failed".equals(outcome)) return Decision.RETRY;
            if ("partial".equals(outcome)) return Decision.PARTIAL;
        }
        return Decision.HOLD;
    }
}
