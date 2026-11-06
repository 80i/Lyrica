package net.lyrica.v1_21_11.words;

/**
 * One sung line. Lines may overlap, as in a duet.
 *
 * @param endMs      when the line leaves the stage, which can be a little after the singing is over, so that it can
 *                   still be read
 * @param parts      when its letters are sung, or null when a file only times whole lines
 * @param voice      which singer of a duet: 1, 2, and so on; 0 when there is one singer or everybody sings
 * @param background what is sung behind this line, with its own times, or null
 */
public record TimedLine(long startMs, long endMs, String text, SungParts parts, int voice, TimedLine background) {

  /** How long a line stays once sung, if the next one leaves it room. */
  static final long LINGER_MS = 1_500L;

  public TimedLine(long startMs, long endMs, String text, SungParts parts) {
    this(startMs, endMs, text, parts, 0, null);
  }

  public long windowMs() {
    return Math.max(1L, this.endMs - this.startMs);
  }

  /** When the first word comes. A line can start before that. */
  public long firstSungMs() {
    return this.parts == null ? this.startMs : this.startMs + this.parts.firstStart();
  }

  /** When all that this line holds has been sung, which breaks in the singing are counted from. */
  public long sungEndMs() {
    long own = this.parts == null ? this.endMs : this.startMs + this.parts.lastEnd();
    return this.background == null ? own : Math.max(own, this.background.sungEndMs());
  }
}
