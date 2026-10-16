package dev.sinnix.phone.sync;

/** Upload success binds to the retained length and full payload digest. */
public final class UploadReceipt {
    private UploadReceipt() {}

    public static boolean matches(long retainedBytes, String retainedSha,
                                  int sentBytes, String sentSha) {
        return retainedBytes == sentBytes && retainedSha != null && sentSha != null
            && retainedSha.matches("[0-9a-fA-F]{64}")
            && sentSha.matches("[0-9a-fA-F]{64}")
            && retainedSha.equalsIgnoreCase(sentSha);
    }
}
