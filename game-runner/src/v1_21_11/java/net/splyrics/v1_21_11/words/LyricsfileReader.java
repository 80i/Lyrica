package net.splyrics.v1_21_11.words;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads Lyricsfile 1.0, the YAML format LRCLIB serves: lines with {@code text}, {@code start_ms}, {@code end_ms} and
 * {@code words}, each word with {@code text}, {@code start_ms} and {@code end_ms}. Written from its public specification
 * (https://github.com/tranxuanthang/lyricsfile, CC0). A bad file gives no lines rather than an exception, and a bad line
 * is only that line lost, so whoever calls this can go back to what else there is.
 */
public final class LyricsfileReader {

  private static final String VERSION = "1.0";
  private static final long LONGEST_TIME_MS = 86_400_000L;
  private static final long LONGEST_UNTIMED_LINE_MS = 12_000L;
  private static final long LAST_UNTIMED_LINE_MS = 6_000L;

  private LyricsfileReader() {
  }

  /** {@code durationMs} (0 when unknown) keeps the last line inside the song. */
  public static TimedLyrics parse(String yaml, long durationMs) {
    if (yaml == null || yaml.isBlank()) {
      return new TimedLyrics(List.of());
    }

    try {
      // The specification forbids taking an unknown version for this one.
      if (!(SimpleYaml.parse(yaml) instanceof Map<?, ?> root) || !VERSION.equals(root.get("version"))
          || !(root.get("lines") instanceof List<?> lines)) {
        return new TimedLyrics(List.of());
      }

      List<Raw> raws = new ArrayList<>(lines.size());
      for (int i = 0; i < lines.size(); i++) {
        if (lines.get(i) instanceof Map<?, ?> line) {
          Raw raw = readLine(line);
          if (raw != null) {
            raws.add(raw);
          }
        }
      }

      raws.sort((a, b) -> Long.compare(a.startMs, b.startMs));
      return new TimedLyrics(finish(raws, durationMs));
    } catch (IllegalArgumentException failure) {
      return new TimedLyrics(List.of());
    }
  }

  private static Raw readLine(Map<?, ?> line) {
    String text = line.get("text") instanceof String written ? tidy(written) : "";
    long start = time(line.get("start_ms"));
    if (text.isEmpty() || start < 0L || LrcReader.isOnlyMusicMarks(text)) {
      return null;
    }

    long end = time(line.get("end_ms"));
    end = end > start ? end : -1L;
    long firstWord = line.get("words") instanceof List<?> words ? firstWordStart(words) : -1L;
    long lineStart = firstWord < 0L ? start : Math.min(start, firstWord);
    SungParts parts = line.get("words") instanceof List<?> words ? partsOf(text, words, lineStart, end) : null;
    return new Raw(parts == null ? start : lineStart, end, text, parts);
  }

  private static long firstWordStart(List<?> words) {
    return !words.isEmpty() && words.get(0) instanceof Map<?, ?> word ? time(word.get("start_ms")) : -1L;
  }

  /**
   * The words' text has to be the line's, apart from spaces, as the specification says it should be but cannot promise.
   * If it is not, the words would be timing some other text, and the line goes without them.
   */
  private static SungParts partsOf(String text, List<?> words, long lineStart, long lineEnd) {
    int count = words.size();
    if (count == 0) {
      return null;
    }

    long[] starts = new long[count];
    long[] ends = new long[count];
    int[] chars = new int[count];
    StringBuilder joined = new StringBuilder(text.length());
    for (int i = 0; i < count; i++) {
      if (!(words.get(i) instanceof Map<?, ?> word) || !(word.get("text") instanceof String written)) {
        return null;
      }

      long start = time(word.get("start_ms"));
      if (start < 0L) {
        return null;
      }

      int before = joined.length();
      appendWithoutSpaces(joined, written);
      long end = time(word.get("end_ms"));
      starts[i] = start - lineStart;
      ends[i] = end > start ? end - lineStart : -1L;
      chars[i] = joined.length() - before;
    }

    StringBuilder line = new StringBuilder(text.length());
    appendWithoutSpaces(line, text);
    if (!line.toString().contentEquals(joined)) {
      return null;
    }

    // A word with no end of its own gets a guessed one, and the line's end may be the next line's start, which says
    // nothing about when the last word stopped. So that end only ever shortens the guess.
    int last = count - 1;
    if (ends[last] < 0L && lineEnd >= 0L) {
      long lineLeft = lineEnd - lineStart;
      if (lineLeft > starts[last]) {
        ends[last] = Math.min(lineLeft, starts[last] + SungParts.GUESSED_LENGTH_MS);
      }
    }

    return SungParts.of(starts, ends, chars);
  }

  private static List<TimedLine> finish(List<Raw> raws, long durationMs) {
    List<TimedLine> lines = new ArrayList<>(raws.size());
    for (int i = 0; i < raws.size(); i++) {
      Raw raw = raws.get(i);
      long next = i + 1 < raws.size() ? raws.get(i + 1).startMs : Long.MAX_VALUE;
      long end;
      if (raw.parts != null) {
        long sung = raw.startMs + raw.parts.lastEnd();
        end = raw.endMs >= 0L ? Math.max(raw.endMs, sung) : Math.max(sung, Math.min(next, sung + TimedLine.LINGER_MS));
      } else if (raw.endMs >= 0L) {
        end = raw.endMs;
      } else {
        end = next == Long.MAX_VALUE ? raw.startMs + LAST_UNTIMED_LINE_MS
            : Math.min(next, raw.startMs + LONGEST_UNTIMED_LINE_MS);
      }

      if (durationMs > raw.startMs) {
        end = Math.min(end, durationMs);
      }

      lines.add(new TimedLine(raw.startMs, Math.max(raw.startMs + 1L, end), raw.text, raw.parts));
    }

    return lines;
  }

  private static String tidy(String text) {
    StringBuilder out = new StringBuilder(text.length());
    boolean space = false;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (isSpace(c)) {
        space = out.length() > 0;
      } else {
        if (space) {
          out.append(' ');
          space = false;
        }

        out.append(c);
      }
    }

    return out.toString();
  }

  private static void appendWithoutSpaces(StringBuilder into, String text) {
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (!isSpace(c)) {
        into.append(c);
      }
    }
  }

  private static boolean isSpace(char c) {
    return Character.isWhitespace(c) || Character.isSpaceChar(c);
  }

  /** Whole milliseconds, or -1: no time, or nothing a song could hold. */
  private static long time(Object value) {
    if (!(value instanceof String text) || text.isEmpty() || text.length() > 12) {
      return -1L;
    }

    long total = 0L;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c < '0' || c > '9') {
        return -1L;
      }

      total = total * 10L + (c - '0');
    }

    return total < LONGEST_TIME_MS ? total : -1L;
  }

  private record Raw(long startMs, long endMs, String text, SungParts parts) {

  }
}
