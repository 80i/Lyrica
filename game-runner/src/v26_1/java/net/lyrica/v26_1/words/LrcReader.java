package net.lyrica.v26_1.words;

import java.util.ArrayList;
import java.util.List;

/** Reads LRC text: several timestamps on a line, {@code [offset:]} and enhanced {@code <mm:ss.xx>} word timings. */
public final class LrcReader {

  /** A line followed by a long silence does not stand through all of it. */
  private static final long LONGEST_LINE_MS = 12_000L;
  private static final long LAST_LINE_MS = 6_000L;

  // What a stretch of letters holds while it is being read.
  private static final int START = 0;
  private static final int END = 1;
  private static final int CHARS = 2;

  private LrcReader() {
  }

  public static TimedLyrics parse(String lrc) {
    return parse(lrc, 0L);
  }

  /** {@code durationMs} (0 when unknown) keeps the last line inside the song. */
  public static TimedLyrics parse(String lrc, long durationMs) {
    if (lrc == null) {
      return new TimedLyrics(List.of());
    }

    long offset = findOffset(lrc);
    List<Raw> raws = new ArrayList<>();
    int from = lrc.startsWith("﻿") ? 1 : 0;
    while (from <= lrc.length()) {
      int end = lrc.indexOf('\n', from);
      if (end < 0) {
        end = lrc.length();
      }

      readLine(lrc.substring(from, end).strip(), offset, raws);
      from = end + 1;
    }

    raws.sort((a, b) -> Long.compare(a.startMs, b.startMs));
    List<TimedLine> lines = new ArrayList<>();
    for (int i = 0; i < raws.size(); i++) {
      Raw raw = raws.get(i);
      if (raw.text.isEmpty()) {
        continue;
      }

      long next = i + 1 < raws.size() ? raws.get(i + 1).startMs : Long.MAX_VALUE;
      long end;
      if (raw.parts != null) {
        // With word timings it is known when the singing stops, so the line does not stand through what comes after.
        end = Math.min(next, raw.startMs + raw.parts.lastEnd() + TimedLine.LINGER_MS);
      } else {
        end = next == Long.MAX_VALUE ? raw.startMs + LAST_LINE_MS : Math.min(next, raw.startMs + LONGEST_LINE_MS);
      }

      if (durationMs > raw.startMs) {
        end = Math.min(end, durationMs);
      }

      lines.add(new TimedLine(raw.startMs, end, raw.text, raw.parts));
    }

    return new TimedLyrics(lines);
  }

  private static long findOffset(String lrc) {
    int tag = lrc.indexOf("[offset:");
    if (tag < 0) {
      return 0L;
    }

    int close = lrc.indexOf(']', tag);
    if (close < 0) {
      return 0L;
    }

    try {
      String value = lrc.substring(tag + 8, close).strip();
      return Long.parseLong(value.startsWith("+") ? value.substring(1) : value);
    } catch (NumberFormatException e) {
      return 0L;
    }
  }

  private static void readLine(String line, long offset, List<Raw> into) {
    List<Long> starts = new ArrayList<>(2);
    int at = 0;
    while (at < line.length() && line.charAt(at) == '[') {
      int close = line.indexOf(']', at);
      if (close < 0) {
        break;
      }

      long time = parseTime(line, at + 1, close);
      if (time < 0L) {
        // A metadata tag such as [ar:...] holds the whole line.
        if (starts.isEmpty()) {
          return;
        }
        break;
      }

      starts.add(Math.max(0L, time - offset));
      at = close + 1;
    }

    if (starts.isEmpty()) {
      return;
    }

    String body = line.substring(at);
    for (int i = 0; i < starts.size(); i++) {
      into.add(readBody(body, starts.get(i), offset, starts.get(0)));
    }
  }

  /**
   * A line sung several times has its word timings written for the first time, and they count from there for all of
   * them. A time tag starts a stretch of letters, which may be a word or a syllable. A tag with no letters after it says
   * when the stretch before it ended.
   */
  private static Raw readBody(String body, long start, long offset, long firstStart) {
    StringBuilder text = new StringBuilder(body.length());
    List<long[]> stretches = new ArrayList<>();
    long[] open = null;
    boolean anyTag = false;
    boolean inWord = false;
    int i = 0;
    while (i < body.length()) {
      char c = body.charAt(i);
      if (c == '<') {
        int close = body.indexOf('>', i);
        long time = close < 0 ? -1L : parseTime(body, i + 1, close);
        if (time >= 0L) {
          long at = Math.max(0L, Math.max(0L, time - offset) - firstStart);
          anyTag = true;
          if (open != null && open[CHARS] == 0L) {
            // Nothing was sung since the tag before, so that one ended a stretch and this one is the next start.
            open[START] = at;
          } else {
            if (open != null) {
              stretches.add(open);
            }

            open = new long[] {at, -1L, 0L};
          }

          if (!stretches.isEmpty() && isEndTag(body, close + 1)) {
            long[] last = stretches.get(stretches.size() - 1);
            last[END] = last[END] < 0L ? at : last[END];
          }

          i = close + 1;
          continue;
        }
      }

      if (Character.isWhitespace(c)) {
        if (inWord) {
          text.append(' ');
          inWord = false;
        }
      } else {
        if (open == null) {
          open = new long[] {0L, -1L, 0L};
        }

        open[CHARS]++;
        text.append(c);
        inWord = true;
      }

      i++;
    }

    if (open != null && open[CHARS] > 0L) {
      stretches.add(open);
    }

    String clean = text.toString().strip();
    if (isOnlyMusicMarks(clean)) {
      clean = "";
    }

    return new Raw(start, clean, anyTag && !clean.isEmpty() ? partsOf(stretches) : null);
  }

  /** Whether only whitespace or another tag comes next, so that the tag just read has no letters of its own. */
  private static boolean isEndTag(String body, int from) {
    for (int i = from; i < body.length(); i++) {
      char c = body.charAt(i);
      if (c == '<') {
        return true;
      }

      if (!Character.isWhitespace(c)) {
        return false;
      }
    }

    return true;
  }

  private static SungParts partsOf(List<long[]> stretches) {
    int count = stretches.size();
    long[] starts = new long[count];
    long[] ends = new long[count];
    int[] chars = new int[count];
    for (int i = 0; i < count; i++) {
      long[] stretch = stretches.get(i);
      starts[i] = stretch[START];
      ends[i] = stretch[END];
      chars[i] = (int) stretch[CHARS];
    }

    return SungParts.of(starts, ends, chars);
  }

  static boolean isOnlyMusicMarks(String text) {
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c != '♪' && c != '♫' && c != '♬' && c != '♩' && c != ' ') {
        return false;
      }
    }

    return true;
  }

  /** Reads mm:ss, mm:ss.x, mm:ss.xx, mm:ss.xxx or mm:ss:xx between the two positions; -1 when it is no time. */
  private static long parseTime(String text, int from, int to) {
    int firstColon = text.indexOf(':', from);
    if (firstColon <= from || firstColon >= to) {
      return -1L;
    }

    int secondsEnd = firstColon + 1;
    while (secondsEnd < to && Character.isDigit(text.charAt(secondsEnd))) {
      secondsEnd++;
    }

    long minutes = digits(text, from, firstColon);
    long seconds = digits(text, firstColon + 1, secondsEnd);
    if (minutes < 0L || seconds < 0L) {
      return -1L;
    }

    long fraction = 0L;
    if (secondsEnd < to) {
      char mark = text.charAt(secondsEnd);
      if (mark != '.' && mark != ':') {
        return -1L;
      }

      long value = digits(text, secondsEnd + 1, to);
      if (value < 0L) {
        return -1L;
      }

      int length = to - secondsEnd - 1;
      fraction = length == 1 ? value * 100L : length == 2 ? value * 10L : length == 3 ? value : value / pow10(length - 3);
    }

    return minutes * 60_000L + seconds * 1_000L + fraction;
  }

  private static long pow10(int power) {
    long result = 1L;
    for (int i = 0; i < power; i++) {
      result *= 10L;
    }

    return result;
  }

  private static long digits(String text, int from, int to) {
    if (from >= to || to - from > 9) {
      return -1L;
    }

    long value = 0L;
    for (int i = from; i < to; i++) {
      char c = text.charAt(i);
      if (c < '0' || c > '9') {
        return -1L;
      }

      value = value * 10L + (c - '0');
    }

    return value;
  }

  private record Raw(long startMs, String text, SungParts parts) {

  }
}
