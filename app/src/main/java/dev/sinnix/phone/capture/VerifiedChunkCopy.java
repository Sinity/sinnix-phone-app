package dev.sinnix.phone.capture;

import java.io.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

public final class VerifiedChunkCopy {
    private VerifiedChunkCopy() {}

    public static synchronized void publish(File source, File target) throws IOException {
        if (target.exists()) {
            if (!Arrays.equals(hash(source), hash(target))) throw new IOException("conflicting chunk " + target.getName());
        } else {
            File pending = new File(target.getParentFile(), "." + target.getName() + ".publishing");
            try {
                try (InputStream input = new FileInputStream(source);
                     FileOutputStream output = new FileOutputStream(pending)) {
                    byte[] buffer = new byte[65536];
                    int n;
                    while ((n = input.read(buffer)) != -1) output.write(buffer, 0, n);
                    output.getFD().sync();
                }
                if (!Arrays.equals(hash(source), hash(pending))) throw new IOException("chunk copy checksum mismatch");
                if (!pending.renameTo(target)) throw new IOException("cannot publish " + target.getName());
            } finally {
                pending.delete();
            }
        }
        if (!source.delete()) throw new IOException("cannot retire private chunk " + source.getName());
    }

    private static byte[] hash(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new FileInputStream(file)) {
                byte[] buffer = new byte[65536];
                int n;
                while ((n = input.read(buffer)) != -1) digest.update(buffer, 0, n);
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
