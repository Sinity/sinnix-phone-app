import dev.sinnix.phone.capture.PcmFramer;
import java.util.ArrayList;
import java.util.List;

public final class PcmFramerTest {
    private static void require(boolean condition, String what) {
        if (!condition) throw new AssertionError("PCM framing regression: " + what);
    }

    public static void main(String[] args) throws Exception {
        // A counting signal cut into reads that never line up with the frame
        // size must come out as the same signal, frame after frame.
        PcmFramer framer = new PcmFramer(960);
        List<short[]> frames = new ArrayList<>();
        PcmFramer.Sink sink = samples -> frames.add(samples.clone());
        int[] reads = {1, 959, 960, 47999, 0, 13, 1921, 5000};
        short next = 0;
        int total = 0;
        for (int size : reads) {
            short[] block = new short[size + 7];
            for (int i = 0; i < size; i++) block[i] = next++;
            framer.add(block, size, sink);
            total += size;
        }
        require(frames.size() == total / 960, "frame count " + frames.size());
        require(framer.pending() == total % 960, "pending " + framer.pending());
        short expected = 0;
        for (short[] frame : frames) {
            require(frame.length == 960, "frame length");
            for (short sample : frame) require(sample == expected++, "sample order");
        }

        // The tail goes out once, padded with silence, and leaves nothing behind.
        int tail = framer.pending();
        framer.flush(sink);
        short[] last = frames.get(frames.size() - 1);
        for (int i = 0; i < 960; i++) {
            require(last[i] == (i < tail ? expected++ : 0), "flush padding at " + i);
        }
        require(framer.pending() == 0, "pending after flush");
        int before = frames.size();
        framer.flush(sink);
        require(frames.size() == before, "second flush emitted a frame");

        // A sink failure loses that frame only; the next samples start a new one.
        PcmFramer failing = new PcmFramer(4);
        try {
            failing.add(new short[] {1, 2, 3, 4, 5}, 5, samples -> { throw new IllegalStateException("codec gone"); });
            require(false, "sink failure was swallowed");
        } catch (IllegalStateException e) {
            require(failing.pending() == 0, "pending after sink failure");
        }
        System.out.println("PCM framing regression checks passed");
    }
}
