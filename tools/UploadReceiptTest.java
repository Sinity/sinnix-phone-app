import dev.sinnix.phone.sync.UploadReceipt;

public final class UploadReceiptTest {
    private static void require(boolean value) {
        if (!value) throw new AssertionError("upload receipt mismatch");
    }
    public static void main(String[] args) {
        String sha = "a".repeat(64);
        require(UploadReceipt.matches(42, sha, 42, sha));
        require(UploadReceipt.matches(42, sha.toUpperCase(), 42, sha));
        require(!UploadReceipt.matches(41, sha, 42, sha));
        require(!UploadReceipt.matches(42, "b".repeat(64), 42, sha));
        require(!UploadReceipt.matches(-1, null, 42, sha));
        require(!UploadReceipt.matches(42, "", 42, sha));
        require(!UploadReceipt.matches(42, "a".repeat(63), 42, sha));
        System.out.println("Upload receipt length and digest checks passed");
    }
}
