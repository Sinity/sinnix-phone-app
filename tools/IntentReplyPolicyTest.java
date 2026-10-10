import dev.sinnix.phone.prime.IntentReplyPolicy;
import static dev.sinnix.phone.prime.IntentReplyPolicy.Decision.*;

public final class IntentReplyPolicyTest {
    private static void expect(Object ok, Object outcome, Object kind,
                               IntentReplyPolicy.Decision expected) {
        if (IntentReplyPolicy.decide(ok, outcome, kind, "steering_ritual") != expected) {
            throw new AssertionError("unsafe intent acknowledgement or retry classification");
        }
    }

    public static void main(String[] args) {
        expect(true, "completed", "steering_ritual", COMPLETED);
        expect(false, "failed", "steering_ritual", RETRY);
        expect(false, "partial", "steering_ritual", PARTIAL);
        for (String outcome : new String[]{"indeterminate", "in_flight", "conflict", "refused", "unknown"}) {
            expect(false, outcome, "steering_ritual", HOLD);
            expect(true, outcome, "steering_ritual", HOLD);
        }
        expect(false, "completed", "steering_ritual", HOLD);
        expect(true, "failed", "steering_ritual", HOLD);
        expect("true", "completed", "steering_ritual", HOLD);
        expect(null, "completed", "steering_ritual", HOLD);
        expect(true, null, "steering_ritual", HOLD);
        expect(true, "completed", "other", HOLD);
        expect(true, "completed", null, HOLD);
        System.out.println("Intent outcome acknowledgement and retry checks passed");
    }
}
