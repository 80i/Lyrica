package net.splyrics.v1_21_11.words;

import java.util.Arrays;

/**
 * When a line's letters are sung: its letters cut into stretches, each a word, a syllable or a single character, with
 * its own start and end in milliseconds from the line's start. A stretch is counted in the chars of the line's text
 * without its whitespace, so it does not have to end where a word does: Japanese written without spaces has a stretch
 * per character, and a word may have one per syllable. Inside a stretch the letters follow each other evenly.
 */
public final class SungParts {

  /** How long the last of a run of words is taken to last when a file says only where words start. */
  public static final long GUESSED_LENGTH_MS = 1_600L;

  private final long[] starts;
  private final long[] ends;
  // One more than there are stretches: the chars before each, then all of them.
  private final int[] charsBefore;

  private SungParts(long[] starts, long[] ends, int[] charsBefore) {
    this.starts = starts;
    this.ends = ends;
    this.charsBefore = charsBefore;
  }

  /**
   * Stretches in the order of the text. Whatever a bad file has here comes out usable: stretches without chars go,
   * times never run backward, and an end below 0 means unknown and becomes the next start, at most a guessed length on.
   *
   * @return null when no stretch has any chars
   */
  public static SungParts of(long[] starts, long[] ends, int[] chars) {
    long[] keptStarts = new long[chars.length];
    long[] keptEnds = new long[chars.length];
    int[] before = new int[chars.length + 1];
    int kept = 0;
    long latest = 0L;
    for (int i = 0; i < chars.length; i++) {
      if (chars[i] <= 0) {
        continue;
      }

      latest = Math.max(latest, starts[i]);
      keptStarts[kept] = latest;
      keptEnds[kept] = ends[i];
      before[kept + 1] = before[kept] + chars[i];
      kept++;
    }

    if (kept == 0) {
      return null;
    }

    for (int i = 0; i < kept; i++) {
      long guess = keptStarts[i] + GUESSED_LENGTH_MS;
      long next = i + 1 < kept ? keptStarts[i + 1] : Long.MAX_VALUE;
      keptEnds[i] = keptEnds[i] < 0L ? Math.min(guess, next) : keptEnds[i];
      keptEnds[i] = Math.max(keptEnds[i], keptStarts[i] + 1L);
    }

    return new SungParts(Arrays.copyOf(keptStarts, kept), Arrays.copyOf(keptEnds, kept), Arrays.copyOf(before, kept + 1));
  }

  public int count() {
    return this.starts.length;
  }

  public long start(int part) {
    return this.starts[part];
  }

  public long end(int part) {
    return this.ends[part];
  }

  public int chars(int part) {
    return this.charsBefore[part + 1] - this.charsBefore[part];
  }

  public long firstStart() {
    return this.starts[0];
  }

  /** When the singing is over. The ends need not grow, as a held word can outlast the next ones in a bad file. */
  public long lastEnd() {
    long latest = 0L;
    for (int i = 0; i < this.ends.length; i++) {
      latest = Math.max(latest, this.ends[i]);
    }

    return latest;
  }

  /** When the letter that begins this many chars into the line starts. Past the end, the end. */
  public float startAt(float chars) {
    int last = this.starts.length - 1;
    for (int i = 0; i <= last; i++) {
      if (chars < this.charsBefore[i + 1]) {
        return this.timeInside(i, chars);
      }
    }

    return this.ends[last];
  }

  /** When the letter that ends this many chars into the line is over. Before the start, the start. */
  public float endAt(float chars) {
    int last = this.starts.length - 1;
    for (int i = 0; i <= last; i++) {
      if (chars <= this.charsBefore[i + 1]) {
        return this.timeInside(i, chars);
      }
    }

    return this.ends[last];
  }

  private float timeInside(int part, float chars) {
    float through = (chars - this.charsBefore[part]) / (this.charsBefore[part + 1] - this.charsBefore[part]);
    return this.starts[part] + (this.ends[part] - this.starts[part]) * Math.max(0.0F, Math.min(1.0F, through));
  }
}
