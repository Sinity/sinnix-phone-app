package dev.sinnix.phone.core;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/** Read a bounded, UTF-8 tail without a filesystem call for every byte. */
public final class EventLogReader {
    private EventLogReader() {}

    public static void readTail(File file, int tailBytes, int maxLineBytes, Consumer<String> accept)
            throws IOException {
        if (tailBytes <= 0 || maxLineBytes <= 0) throw new IllegalArgumentException("positive limits required");
        byte[] data;
        boolean partial;
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            long length = input.length();
            long start = Math.max(0, length - tailBytes);
            partial = false;
            if (start > 0) {
                input.seek(start - 1);
                partial = input.read() != '\n';
            }
            input.seek(start);
            data = new byte[(int) (length - start)];
            input.readFully(data);
        }
        int start = 0;
        for (int end = 0; end <= data.length; end++) {
            if (end < data.length && data[end] != '\n') continue;
            int contentEnd = end;
            if (contentEnd > start && data[contentEnd - 1] == '\r') contentEnd--;
            int size = contentEnd - start;
            if (!partial && size > 0 && size <= maxLineBytes) {
                accept.accept(new String(data, start, size, StandardCharsets.UTF_8));
            }
            partial = false;
            start = end + 1;
        }
    }
}
