package org.webrtc.audio;

/** Probe-only access to the explicitly patched Java sink; not shipped in the gateway app. */
public final class ExternalPlayout {
  private ExternalPlayout() {}

  public static void enable(JavaAudioDeviceModule module) {
    module.audioOutput.useExternalPlayout();
    module.setAudioRecordEnabled(false);
  }

  /** Runtime assertion in the isolated probe, not just a claim based on configuration. */
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
}
