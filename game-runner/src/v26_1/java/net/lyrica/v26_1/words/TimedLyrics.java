package net.lyrica.v26_1.words;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** The lines of a song, sorted by start. Read every frame, so lookups are binary searches and never allocate. */
public final class TimedLyrics {

  private final TimedLine[] lines;
  private final boolean wordTimed;

  public TimedLyrics(List<TimedLine> lines) {
    List<TimedLine> sorted = new ArrayList<>(lines);
    sorted.sort(Comparator.comparingLong(TimedLine::startMs));
    this.lines = sorted.toArray(new TimedLine[0]);
    boolean any = false;
    for (int i = 0; i < this.lines.length; i++) {
      any |= this.lines[i].knowsWords();
    }

    this.wordTimed = any;
  }

  /**
   * The same lyrics with a guess at when each word is sung put on every line that has no word timings, so that the
   * karaoke follows the singing more closely than letters spread over the whole line would.
   */
  public TimedLyrics withGuessedWords() {
    List<TimedLine> guessed = new ArrayList<>(this.lines.length);
    for (TimedLine line : this.lines) {
      guessed.add(line.parts() != null ? line : new TimedLine(line.startMs(), line.endMs(), line.text(),
          SungParts.guess(line.text(), line.windowMs()), line.voice(), line.background()));
    }

    return new TimedLyrics(guessed);
  }

  /** Whether any line knows when its letters are sung. */
  public boolean wordTimed() {
    return this.wordTimed;
  }

  public boolean isEmpty() {
    return this.lines.length == 0;
  }

  public int size() {
    return this.lines.length;
  }

  public TimedLine get(int index) {
    return this.lines[index];
  }

  /** The index of the last line that has started by this time, whether or not it is still sung; -1 before the first. */
  public int indexAt(long ms) {
    int low = 0;
    int high = this.lines.length - 1;
    int found = -1;
    while (low <= high) {
      int middle = (low + high) >>> 1;
      if (this.lines[middle].startMs() <= ms) {
        found = middle;
        low = middle + 1;
      } else {
        high = middle - 1;
      }
    }

    return found;
  }

  /** The line being sung at this time, or null in a gap. */
  public TimedLine lineAt(long ms) {
    int index = this.indexAt(ms);
    if (index < 0) {
      return null;
    }

    TimedLine line = this.lines[index];
    return ms < line.endMs() ? line : null;
  }

  /** The first line that starts after this time, or null after the last. */
  public TimedLine next(long ms) {
    int index = this.indexAt(ms) + 1;
    return index < this.lines.length ? this.lines[index] : null;
  }

  /** Up to {@code count} lines that are over by this time, the oldest first. */
  public List<TimedLine> previous(long ms, int count) {
    int newest = this.indexAt(ms);
    if (newest >= 0 && ms < this.lines[newest].endMs()) {
      newest--;
    }

    if (newest < 0 || count <= 0) {
      return List.of();
    }

    int oldest = Math.max(0, newest - count + 1);
    List<TimedLine> result = new ArrayList<>(newest - oldest + 1);
    for (int i = oldest; i <= newest; i++) {
      result.add(this.lines[i]);
    }

    return result;
  }
}
