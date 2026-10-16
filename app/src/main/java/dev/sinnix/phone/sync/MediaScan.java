package dev.sinnix.phone.sync;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;

/** Bounded oldest-first selection, independent of filesystem enumeration order. */
public final class MediaScan {
    public static final class Cursor implements Comparable<Cursor> {
        public final long modified;
        public final String path;
        public Cursor(long modified, String path) {
            this.modified = modified;
            this.path = path;
        }
        public int compareTo(Cursor other) {
            int time = Long.compare(modified, other.modified);
            return time != 0 ? time : path.compareTo(other.path);
        }
    }
    public static final class Entry implements Comparable<Entry> {
        public final File file;
        public final Cursor cursor;
        public final long length;
        Entry(File file, String relative) {
            this.file = file;
            this.cursor = new Cursor(file.lastModified(), relative);
            this.length = file.length();
        }
        public int compareTo(Entry other) { return cursor.compareTo(other.cursor); }
        public boolean unchanged() {
            return file.isFile() && file.lastModified() == cursor.modified && file.length() == length;
        }
    }
    public static List<Entry> oldest(File root, Cursor after, int cap, int maxDepth) throws IOException {
        if (cap <= 0) throw new IllegalArgumentException("positive capacity required");
        PriorityQueue<Entry> selected = new PriorityQueue<>(cap, Collections.reverseOrder());
        File canonicalRoot = root.getCanonicalFile();
        collect(canonicalRoot, canonicalRoot, after, cap, maxDepth, 0, selected);
        ArrayList<Entry> result = new ArrayList<>(selected);
        Collections.sort(result);
        return result;
    }
    private static void collect(File root, File dir, Cursor after, int cap, int maxDepth,
                                int depth, PriorityQueue<Entry> selected) throws IOException {
        if (depth >= maxDepth) return;
        File[] files = dir.listFiles();
        if (files == null) throw new IOException("cannot list media directory");
        for (File file : files) {
            // Do not follow directory links outside the selected lane or into a cycle.
            if (!file.getCanonicalFile().equals(file.getAbsoluteFile())) continue;
            if (file.isDirectory()) {
                collect(root, file, after, cap, maxDepth, depth + 1, selected);
            } else if (file.isFile() && !file.getName().startsWith(".")) {
                String relative = root.toPath().relativize(file.toPath()).toString();
                Entry entry = new Entry(file, relative);
                if (entry.cursor.compareTo(after) <= 0) continue;
                if (selected.size() < cap) selected.add(entry);
                else if (entry.compareTo(selected.peek()) < 0) {
                    selected.poll();
                    selected.add(entry);
                }
            }
        }
    }
    /** Name/size refusals are terminal; corruption, auth and overload are retryable. */
    public static boolean terminalRefusal(int code) { return code == 400 || code == 413; }
    private MediaScan() {}
}
