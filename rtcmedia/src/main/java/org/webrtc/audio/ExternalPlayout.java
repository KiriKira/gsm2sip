package org.webrtc.audio;

/** Pinned SDK external PCM access to the explicitly patched Java sink. */
public final class ExternalPlayout {
  private ExternalPlayout() {}

  public static void enable(JavaAudioDeviceModule module) {
    module.audioOutput.useExternalPlayout();
    module.setAudioRecordEnabled(false);
  }

  /** Runtime assertion, rather than only a claim based on configuration. */
  public static boolean isExclusive(JavaAudioDeviceModule module) {
    try {
      java.lang.reflect.Field record = WebRtcAudioRecord.class.getDeclaredField("audioRecord");
      java.lang.reflect.Field enabled = WebRtcAudioRecord.class.getDeclaredField("useAudioRecord");
      record.setAccessible(true);
      enabled.setAccessible(true);
      return module.audioOutput.isExternalPlayoutActive()
          && record.get(module.audioInput) == null && !enabled.getBoolean(module.audioInput);
    } catch (ReflectiveOperationException failure) {
      throw new IllegalStateException("Pinned SDK capture fields changed", failure);
    }
  }

  /** Pace before pulling PCM. A late frame never causes a second immediate pull. */
  public static final class FramePacer {
    private static final long PERIOD_NS = 10_000_000L;
    private final java.util.function.LongSupplier clock;
    private final java.util.function.LongConsumer park;
    private boolean initialized;
    private long nextFrame;

    public FramePacer() {
      this(System::nanoTime, java.util.concurrent.locks.LockSupport::parkNanos);
    }

    public FramePacer(java.util.function.LongSupplier clock, java.util.function.LongConsumer park) {
      this.clock = clock;
      this.park = park;
    }

    public void awaitNextFrame() {
      long deadline = initialized ? nextFrame : clock.getAsLong();
      initialized = true;
      long remaining = deadline - clock.getAsLong();
      while (remaining > 0 && !Thread.currentThread().isInterrupted()) {
        park.accept(remaining);
        remaining = deadline - clock.getAsLong();
      }
      long deliveredAt = clock.getAsLong();
      nextFrame = deliveredAt - deadline >= PERIOD_NS
          ? deliveredAt + PERIOD_NS : deadline + PERIOD_NS;
    }
  }
}
