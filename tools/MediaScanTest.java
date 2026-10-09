import dev.sinnix.phone.sync.MediaScan;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;

public class MediaScanTest {
    static void check(boolean ok) { if (!ok) throw new AssertionError(); }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("media-scan-test");
        try {
            for (int i = 19; i >= 0; i--) {
                Files.write(root.resolve(String.format("file%02d", i)), new byte[]{1});
            }
            // Make the oldest files occur after the first capacity-sized slice
            // in this filesystem's actual enumeration, even if it sorts names.
            File[] enumerated = root.toFile().listFiles();
            check(enumerated != null && enumerated.length == 20);
            for (int i = 0; i < enumerated.length; i++)
                check(enumerated[i].setLastModified(10000 + (enumerated.length - i) * 1000));
            List<MediaScan.Entry> first = MediaScan.oldest(root.toFile(), new MediaScan.Cursor(-1, ""), 2, 3);
            check(first.get(0).cursor.path.equals(enumerated[19].getName()));
            check(first.get(1).cursor.path.equals(enumerated[18].getName()));
            for (File file : root.toFile().listFiles()) check(file.setLastModified(50000));
            MediaScan.Cursor cursor = new MediaScan.Cursor(-1, "");
            HashSet<String> seen = new HashSet<>();
            while (true) {
                List<MediaScan.Entry> batch = MediaScan.oldest(root.toFile(), cursor, 3, 3);
                if (batch.isEmpty()) break;
                for (MediaScan.Entry entry : batch) {
                    check(seen.add(entry.cursor.path));
                    cursor = entry.cursor;
                }
            }
            check(seen.size() == 20);
            // Recreating preferences starts from source history, not upload time.
            check(MediaScan.oldest(root.toFile(), new MediaScan.Cursor(-1, ""), 3, 3).size() == 3);
            MediaScan.Entry entry = MediaScan.oldest(root.toFile(), new MediaScan.Cursor(-1, ""), 1, 3).get(0);
            check(entry.unchanged());
            Files.write(entry.file.toPath(), new byte[]{1, 2});
            check(!entry.unchanged());
            Path alias = root.resolveSibling(root.getFileName() + "-alias");
            Files.createSymbolicLink(alias, root);
            try {
                check(MediaScan.oldest(alias.toFile(), new MediaScan.Cursor(-1, ""), 2, 4).size() == 2);
            } finally { Files.delete(alias); }
            Path nested = root.resolve("a/b/c/file");
            Files.createDirectories(nested.getParent());
            Files.write(nested, new byte[]{1});
            check(nested.toFile().setLastModified(1000));
            check(MediaScan.oldest(root.toFile(), new MediaScan.Cursor(-1, ""), 1, 4)
                .get(0).cursor.path.equals("a/b/c/file"));
            check(MediaScan.terminalRefusal(400));
            check(MediaScan.terminalRefusal(413));
            for (int code : new int[]{401, 403, 404, 409, 422, 429, 500})
                check(!MediaScan.terminalRefusal(code));
            boolean refused = false;
            try { MediaScan.oldest(entry.file, new MediaScan.Cursor(-1, ""), 2, 3); }
            catch (java.io.IOException expected) { refused = true; }
            check(refused);
            System.out.println("MediaScan: ordering, bounded ties, reseed, mutation and listing failure passed");
        } finally {
            try (var walk = Files.walk(root)) {
                for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
