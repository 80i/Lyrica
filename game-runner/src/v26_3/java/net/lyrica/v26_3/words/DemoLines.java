package net.lyrica.v26_3.words;

import java.util.ArrayList;
import java.util.List;
import net.labymod.api.util.I18n;
import net.lyrica.v26_3.song.NowPlaying;

/** Lines to show when no real ones are there: while the menu is open, and a line forced by the development script. */
public final class DemoLines {

  private static final String[] LINE_KEYS = {
      "lyrica.demo.line1", "lyrica.demo.line2", "lyrica.demo.line3", "lyrica.demo.line4"
  };
  private static final long LINE_MS = 4_000L;
  // A long run of repeats rather than a jump back, so the stage only ever sees time moving on to a new line.
  private static final int ROUNDS = 225;
  private static final long LOOP_MS = LINE_MS * LINE_KEYS.length * ROUNDS;
  private static final long FORCED_SPAN_MS = 7_200_000L;
  private static final long FORCED_LEAD_MS = 5_000L;
  private static final String FORCED_RUN = "forced run ";

  private static String forcedText;
  private static long forcedCount;
  private static TimedLyrics forcedLyrics;
  private static NowPlaying forcedSong;

  private static boolean menuOpen;
  private static TimedLyrics loopLyrics;
  private static NowPlaying loopSong;

  private DemoLines() {
  }

  /** Makes a line stand, fully on stage, for a long time; null ends it. */
  public static void force(String lineOrNull) {
    if (lineOrNull == null) {
      forcedText = null;
      forcedLyrics = null;
      forcedSong = null;
      return;
    }

    if (lineOrNull.equals(forcedText)) {
      return;
    }

    forcedText = lineOrNull;
    forcedCount++;
    long start = forcedCount * FORCED_SPAN_MS;
    long end = start + FORCED_SPAN_MS;
    forcedLyrics = new TimedLyrics(List.of(new TimedLine(start, end, lineOrNull, null)));
    forcedSong = demoSong(end, start + FORCED_LEAD_MS);
  }

  /**
   * Plays these lines one after the other from their very start, each this long. A forced line stands long past its
   * entrance and its karaoke, so catching those takes a real clock; and only lines of one run can stay on stage
   * together, because new lyrics clear the stage.
   */
  public static void forceRun(long eachMs, String... lines) {
    forcedCount++;
    forcedText = FORCED_RUN + forcedCount;
    long start = forcedCount * FORCED_SPAN_MS;
    List<TimedLine> timed = new ArrayList<>(lines.length);
    for (int i = 0; i < lines.length; i++) {
      long from = start + i * eachMs;
      timed.add(new TimedLine(from, from + eachMs, lines[i], null));
    }

    forcedLyrics = new TimedLyrics(timed);
    forcedSong = demoSong(start + FORCED_SPAN_MS, start);
  }

  public static void menuOpen(boolean open) {
    if (open && !menuOpen) {
      startLoop();
    }

    menuOpen = open;
  }

  /**
   * True when a line is forced, or the menu is open with nothing to show. A paused song counts as nothing: the real
   * line in the world is the menu's only preview, so without this every fader would move with nothing to see.
   */
  public static boolean active(NowPlaying song, TimedLyrics lyrics) {
    return forcedText != null || (menuOpen && (song == null || !song.playing() || lyrics == null || lyrics.isEmpty()));
  }

  public static NowPlaying song() {
    if (forcedSong != null) {
      return forcedSong;
    }

    if (loopSong == null || System.currentTimeMillis() - loopSong.sampledAtMs() >= LOOP_MS - LINE_MS) {
      startLoop();
    }

    return loopSong;
  }

  public static TimedLyrics lyrics() {
    if (forcedLyrics != null) {
      return forcedLyrics;
    }

    if (loopLyrics == null) {
      startLoop();
    }

    return loopLyrics;
  }

  private static void startLoop() {
    String[] texts = new String[LINE_KEYS.length];
    for (int i = 0; i < texts.length; i++) {
      texts[i] = I18n.translate(LINE_KEYS[i]);
    }

    List<TimedLine> lines = new ArrayList<>(texts.length * ROUNDS);
    for (int round = 0; round < ROUNDS; round++) {
      for (int i = 0; i < texts.length; i++) {
        long start = (round * (long) texts.length + i) * LINE_MS;
        lines.add(new TimedLine(start, start + LINE_MS, texts[i], null));
      }
    }

    loopLyrics = new TimedLyrics(lines);
    loopSong = demoSong(LOOP_MS, 0L);
  }

  private static NowPlaying demoSong(long durationMs, long positionMs) {
    return new NowPlaying(
        I18n.translate("lyrica.demo.title"),
        I18n.translate("lyrica.demo.artist"),
        "",
        durationMs,
        positionMs,
        System.currentTimeMillis(),
        true,
        "Lyrica",
        null);
  }
}
