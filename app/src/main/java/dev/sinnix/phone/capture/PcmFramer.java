package dev.sinnix.phone.capture;

/**
 * Cuts PCM that arrives in arbitrary pieces into fixed-size frames.
 *
 * The microphone is read about once a second and the Opus encoder takes
 * exactly 20 ms at a time, so every read ends mid-frame. The partial frame is
 * carried to the next read, and across a change of output file, so no sample
 * is dropped or repeated at either boundary.
 */
public final class PcmFramer {
    public interface Sink {
        void frame(short[] samples) throws Exception;
    }

    private final short[] frame;
    private int filled;

    public PcmFramer(int frameSamples) {
        if (frameSamples <= 0) throw new IllegalArgumentException("frame size must be positive");
        frame = new short[frameSamples];
    }

    /** Hands every frame completed by these samples to the sink, in order. */
    public void add(short[] samples, int count, Sink sink) throws Exception {
        int offset = 0;
        while (offset < count) {
            int n = Math.min(frame.length - filled, count - offset);
            System.arraycopy(samples, offset, frame, filled, n);
            filled += n;
            offset += n;
            if (filled == frame.length) {
                // Reset first: the samples are already in the frame, and a sink
                // that throws must not make the next call append past its end.
                filled = 0;
                sink.frame(frame);
            }
        }
    }

    /** Samples held back because they do not yet fill a frame. */
    public int pending() {
        return filled;
    }

    /** Ends the stream: a partial frame goes out padded with silence. */
    public void flush(Sink sink) throws Exception {
        if (filled == 0) return;
        java.util.Arrays.fill(frame, filled, frame.length, (short) 0);
        filled = 0;
        sink.frame(frame);
    }
}
