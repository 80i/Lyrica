package net.lyrica.v26_1.song;

import java.util.Locale;

/**
 * One reading of what plays on the PC. {@code artPng}, when present, is always a PNG and is shared between readings of
 * the same song, so comparing it by reference tells whether the cover changed.
 */
public record NowPlaying(
    String title,
    String artist,
    String album,
    long durationMs,
    long positionMs,
    long sampledAtMs,
    boolean playing,
    String sourceApp,
    byte[] artPng
) {

  /** The position right now, running on from the sample while playing, shifted and kept inside the song. */
  public long positionNow(long extraOffsetMs) {
    long at = this.positionMs + extraOffsetMs;
    if (this.playing) {
      at += System.currentTimeMillis() - this.sampledAtMs;
    }

    if (this.durationMs > 0L && at > this.durationMs) {
      at = this.durationMs;
    }

    return Math.max(0L, at);
  }

  /** A stable lower-case identity of artist and title. */
  public String key() {
    return (clean(this.artist) + " - " + clean(this.title)).toLowerCase(Locale.ROOT);
  }

  private static String clean(String text) {
    return text == null ? "" : text.strip();
  }
}
