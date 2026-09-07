import dev.sinnix.phone.capture.CaptureIntegrity;

public final class CaptureIntegrityTest {
    private static void require(boolean condition) {
        if (!condition) throw new AssertionError("capture integrity regression");
    }
    public static void main(String[] args) {
        require(!CaptureIntegrity.complete(305000, 37931L));
        require(!CaptureIntegrity.complete(300000, 40640L));
        require(!CaptureIntegrity.complete(300000, null));
        require(!CaptureIntegrity.complete(300000, 0L));
        require(CaptureIntegrity.complete(300000, 299605L));
        require(CaptureIntegrity.complete(300000, 300048L));
        require(CaptureIntegrity.complete(15000, 15040L));
        require(!CaptureIntegrity.underproducing(20000, 0));
        require(!CaptureIntegrity.underproducing(60000, 720000));
        require(CaptureIntegrity.underproducing(120000, 499563));
        require(CaptureIntegrity.underproducing(60000, 0));
        System.out.println("Capture integrity regression checks passed");
    }
}
