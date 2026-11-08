package net.lyrica.v26_2.words;

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
  // For lyrics that only time lines: about how long a syllable is sung, and how much of the line's time it may take.
  private static final long GUESS_SYLLABLE_MS = 230L;
  private static final long GUESS_LEAST_MS = 400L;
  private static final float GUESS_MOST_SHARE = 0.88F;
  private static final float GUESS_WORD_SHARE = 0.92F;
  // Latin vowels, with accents, for counting syllables.
  private static final String VOWELS = "aeiouy\u00e0\u00e1\u00e2\u00e3\u00e4\u00e5\u00e6\u00e8\u00e9\u00ea\u00eb\u00ec\u00ed\u00ee\u00ef\u00f2\u00f3\u00f4\u00f5\u00f6\u00f9\u00fa\u00fb\u00fc\u00fd\u0153";

  private final long[] starts;
  private final long[] ends;
  // One more than there are stretches: the chars before each, then all of them.
  private final int[] charsBefore;
  private final boolean guessed;

  private SungParts(long[] starts, long[] ends, int[] charsBefore, boolean guessed) {
    this.starts = starts;
    this.ends = ends;
    this.charsBefore = charsBefore;
    this.guessed = guessed;
  }

  /**
   * A guess at when the words of a line are sung, for lyrics that only say when lines start: a word per stretch, each
   * as long as it has syllables, sung at an even pace from the line's start, and over well before the next line.
   *
   * @return null when the line has no words
   */
  public static SungParts guess(String text, long windowMs) {
    String[] words = text == null ? new String[0] : text.strip().split("\\s+");
    int[] chars = new int[words.length];
    int[] syllables = new int[words.length];
    int total = 0;
    for (int i = 0; i < words.length; i++) {
      chars[i] = words[i].length();
      syllables[i] = chars[i] == 0 ? 0 : syllablesOf(words[i]);
      total += syllables[i];
    }

    if (total == 0 || windowMs <= 0L) {
      return null;
    }

    long most = (long) (windowMs * GUESS_MOST_SHARE);
    long sung = Math.min(most, Math.max(GUESS_LEAST_MS, total * GUESS_SYLLABLE_MS));
    float each = sung / (float) total;
    long[] starts = new long[words.length];
    long[] ends = new long[words.length];
    float at = 0.0F;
    for (int i = 0; i < words.length; i++) {
      float length = syllables[i] * each;
      starts[i] = Math.round(at);
      ends[i] = Math.round(at + Math.max(1.0F, length * GUESS_WORD_SHARE));
      at += length;
    }

    SungParts made = of(starts, ends, chars);
    return made == null ? null : new SungParts(made.starts, made.ends, made.charsBefore, true);
  }

  /** Runs of vowels in Latin script; every other letter, as in Japanese or Chinese, counts as one. At least one. */
  private static int syllablesOf(String word) {
    int count = 0;
    boolean inVowel = false;
    boolean anyLatin = false;
    for (int i = 0; i < word.length(); i++) {
      char c = Character.toLowerCase(word.charAt(i));
      if (c < 0x250) {
        if (Character.isLetter(c)) {
          anyLatin = true;
          boolean vowel = VOWELS.indexOf(c) >= 0;
          count += vowel && !inVowel ? 1 : 0;
          inVowel = vowel;
        } else {
          inVowel = false;
        }
      } else if (Character.isLetter(c)) {
        count++;
        inVowel = false;
      }
    }

    // A silent e at the end of a longer word is not sung on its own.
    if (anyLatin && count > 1 && word.length() > 3 && Character.toLowerCase(word.charAt(word.length() - 1)) == 'e'
        && "aeiouy".indexOf(Character.toLowerCase(word.charAt(word.length() - 2))) < 0) {
      count--;
    }

    return Math.max(1, count);
  }

  /** Whether these times are only a guess made from the line's text, rather than read from the lyrics. */
  public boolean guessed() {
    return this.guessed;
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

    return new SungParts(Arrays.copyOf(keptStarts, kept), Arrays.copyOf(keptEnds, kept), Arrays.copyOf(before, kept + 1),
        false);
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
