import dev.sinnix.phone.core.EventLogReader;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public final class EventLogReaderTest {
    private static List<String> read(File file, int tail, int maxLine) throws Exception {
        List<String> lines = new ArrayList<>();
        EventLogReader.readTail(file, tail, maxLine, lines::add);
        return lines;
    }
    private static void equal(Object actual, Object expected) {
        if (!actual.equals(expected)) throw new AssertionError(actual + " != " + expected);
    }
    public static void main(String[] args) throws Exception {
        File file = File.createTempFile("event-log-reader", ".jsonl");
        try {
            Files.writeString(file.toPath(), "{\"text\":\"zażółć 水\"}\r\n\n{\"kind\":\"last\"}", StandardCharsets.UTF_8);
            equal(read(file, 4096, 1024), List.of("{\"text\":\"zażółć 水\"}", "{\"kind\":\"last\"}"));
            // Retired and future event kinds remain in the historical stream.
            List<String> history = List.of(
                "{\"kind\":\"ema_prompted\",\"ts\":\"2026-01-01T12:00:00Z\"}",
                "{\"kind\":\"ema_answer\",\"answer\":\"synthetic fixture\"}",
                "{\"kind\":\"future_signal\",\"value\":1}");
            Files.writeString(file.toPath(), String.join("\n", history) + "\n");
            equal(read(file, 4096, 1024), history);
            Files.writeString(file.toPath(), "old\nkept\nlast\n");
            equal(read(file, 10, 1024), List.of("kept", "last")); // exact boundary
            equal(read(file, 8, 1024), List.of("last")); // mid-line boundary
            Files.writeString(file.toPath(), "oversized\nok\n");
            equal(read(file, 4096, 3), List.of("ok"));
            Files.writeString(file.toPath(), "水水\nok\n", StandardCharsets.UTF_8);
            equal(read(file, 4096, 4), List.of("ok")); // byte limit, not character limit
            Files.write(file.toPath(), new byte[0]);
            equal(read(file, 4096, 1024), List.of());
            Files.writeString(file.toPath(), "ignored\n".repeat(600000) + "retained\n");
            equal(read(file, 12, 1024), List.of("retained"));
            System.out.println("Event log UTF-8, byte limits and tail boundaries passed");
        } finally {
            Files.deleteIfExists(file.toPath());
        }
    }
}
