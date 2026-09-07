import dev.sinnix.phone.capture.VerifiedChunkCopy;
import java.io.*;
import java.nio.file.*;
import java.util.Arrays;

public final class VerifiedChunkCopyTest {
    private static void require(boolean condition) {
        if (!condition) throw new AssertionError("chunk preservation regression");
    }
    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("chunk-copy-test");
        File source = dir.resolve("source").toFile();
        File target = dir.resolve("target").toFile();
        byte[] bytes = new byte[200000];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte)i;
        Files.write(source.toPath(), bytes);
        VerifiedChunkCopy.publish(source, target);
        require(!source.exists() && Arrays.equals(bytes, Files.readAllBytes(target.toPath())));
        Files.write(source.toPath(), bytes);
        VerifiedChunkCopy.publish(source, target);
        require(!source.exists());
        Files.write(source.toPath(), new byte[]{1, 2, 3});
        try {
            VerifiedChunkCopy.publish(source, target);
            throw new AssertionError("conflicting copy was accepted");
        } catch (IOException expected) {
            require(source.exists() && Arrays.equals(bytes, Files.readAllBytes(target.toPath())));
        }
        try {
            VerifiedChunkCopy.publish(source, dir.resolve("missing/target").toFile());
            throw new AssertionError("failed publication was accepted");
        } catch (IOException expected) { require(source.exists()); }
        Files.delete(source.toPath());
        Files.delete(target.toPath());
        Files.delete(dir);
        System.out.println("Chunk publication preservation checks passed");
    }
}
