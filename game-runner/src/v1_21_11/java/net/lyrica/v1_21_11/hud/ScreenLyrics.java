package net.lyrica.v1_21_11.hud;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Style;
import net.lyrica.v1_21_11.look.Fonts;
import net.lyrica.v1_21_11.look.Look;
import net.lyrica.v1_21_11.look.Palette;
import net.lyrica.v1_21_11.song.NowPlaying;
import net.lyrica.v1_21_11.song.Playback;
import net.lyrica.v1_21_11.stage.ComposedLine;
import net.lyrica.v1_21_11.stage.FlatDrawer;
import net.lyrica.v1_21_11.stage.Letter;
import net.lyrica.v1_21_11.stage.LineComposer;
import net.lyrica.v1_21_11.words.DemoLines;
import net.lyrica.v1_21_11.words.LyricsLibrary;
import net.lyrica.v1_21_11.words.TimedLine;
import net.lyrica.v1_21_11.words.TimedLyrics;

/** The lyrics drawn flat on the screen, for the SCREEN place: the sung line and its smaller companions. */
public final class ScreenLyrics {

  private static final int NO_LINE = -2;
  private static final float BASE_SCALE = 2.0F;
  private static final float EDGE = 8.0F;
  // Clear of the hotbar, the health and the action bar text.
  // The game's action bar text ends 72 pixels above the bottom edge, and the sync keys report there.
  private static final float BOTTOM_CLEAR = 74.0F;
  private static final float ROW_GAP = 2.0F;
  private static final long LONGEST_EXIT_MS = 2_000L;

  private static final float EXTRA_SIZE = 0.6F;
  private static final float EXTRA_DEPTH = 0.5F;
  private static final float NEXT_ALPHA = 0.5F;
  private static final float EARLIER_ALPHA = 0.42F;
  private static final float EARLIER_FALLOFF = 0.7F;
  private static final long EXTRA_WINDOW_MS = 3_600_000L;
  private static final int MOST_EARLIER = 3;

  private static final long BREAK_FROM_MS = 8_000L;
  private static final int BREAK_DOTS = 4;
  private static final int BREAK_DOT_SIZE = 5;
  private static final int BREAK_DOT_GAP = 4;

  private static final float BACKGROUND_ALPHA = 0.85F;

  private static final Look EXTRA = new Look();
  private static final Look BEHIND = new Look();
  private static final LineComposer BACKGROUND = new LineComposer();
  private static final LineComposer NEXT = new LineComposer();
  private static final LineComposer[] EARLIER = new LineComposer[MOST_EARLIER];
  private static final ComposedLine[] EARLIER_ROWS = new ComposedLine[MOST_EARLIER];

  static {
    for (int i = 0; i < MOST_EARLIER; i++) {
      EARLIER[i] = new LineComposer();
    }
  }

  private static LineComposer mainComposer = new LineComposer();
  private static LineComposer leavingComposer = new LineComposer();

  private static TimedLyrics shownLyrics;
  private static int mainIndex = NO_LINE;
  private static TimedLine mainLine;
  private static boolean mainDone;
  private static TimedLine leavingLine;

  private static NowPlaying keyedSong;
  private static String keyedKey;

  private ScreenLyrics() {
  }

  public static void render(GuiGraphics g, Look look) {
    if (!look.showLyrics || !"SCREEN".equals(look.place)) {
      forget();
      return;
    }

    NowPlaying song = Playback.get().current();
    TimedLyrics lyrics = LyricsLibrary.get().current();
    if (DemoLines.active(song, lyrics)) {
      song = DemoLines.song();
      lyrics = DemoLines.lyrics();
    }

    if (song == null || lyrics == null || lyrics.isEmpty()) {
      forget();
      return;
    }

    if (look.hideWhenPaused && !song.playing()) {
      return;
    }

    if (lyrics != shownLyrics) {
      forget();
      shownLyrics = lyrics;
    }

    long ms = song.positionNow(look.offsetFor(keyOf(song)));
    long nowMs = System.currentTimeMillis();
    int index = lyrics.indexAt(ms);
    if (index != mainIndex) {
      changeLine(lyrics, index, ms);
    }

    Font font = Minecraft.getInstance().font;
    Style style = Fonts.style(look.font, look.bold);
    // Always the real song: the album cache holds one, and the menu's tiles ask about the real one in the same frame.
    int[] colors = look.colorMode.startsWith("ALBUM") ? Palette.albumColors(Playback.get().current()) : null;
    float width = g.guiWidth();
    float baseScale = BASE_SCALE * look.screenSize;
    float centerX = width * 0.5F;

    ComposedLine main = composeMain(look, font, style, ms, nowMs, colors);
    boolean singing = main != null && hasText(mainLine);
    float mainScale = singing ? fit(main, baseScale, width) : baseScale;
    float mainHeight = singing ? main.height * mainScale : font.lineHeight * look.size * baseScale;

    prepareExtraLook(look);
    long sinceLine = mainLine == null ? EXTRA_WINDOW_MS - 1L : Math.min(EXTRA_WINDOW_MS - 1L, ms - mainLine.startMs());
    float rowHeight = font.lineHeight * EXTRA.size * baseScale + ROW_GAP;

    TimedLine behind = singing ? mainLine.background() : null;
    ComposedLine background = behind == null ? null : BACKGROUND.compose(BEHIND, font, style, behind.text(),
        ms - behind.startMs(), behind.windowMs(), behind.parts(), nowMs, behind.startMs(), colors);
    if (background != null) {
      fadeRow(background, BACKGROUND_ALPHA);
    }

    ComposedLine next = null;
    if (look.nextLine && index + 1 < lyrics.size() && hasText(lyrics.get(index + 1))) {
      TimedLine line = lyrics.get(index + 1);
      next = extraRow(NEXT, font, style, line.text(), sinceLine, nowMs, line.startMs(), colors, NEXT_ALPHA);
    }

    int earlierRows = Math.max(0, Math.min(MOST_EARLIER, look.earlierLines));
    float earlierHeight = 0.0F;
    float fade = EARLIER_ALPHA;
    for (int i = 0; i < earlierRows; i++) {
      EARLIER_ROWS[i] = null;
      int at = index - 1 - i;
      if (at >= 0 && hasText(lyrics.get(at))) {
        TimedLine line = lyrics.get(at);
        EARLIER_ROWS[i] = extraRow(EARLIER[i], font, style, line.text(), sinceLine, nowMs, line.startMs(), colors, fade);
        earlierHeight += extraHeight(EARLIER_ROWS[i], baseScale, width);
      }

      fade *= EARLIER_FALLOFF;
    }

    // Room is kept by the settings, not by what is there this moment, so the sung line does not hop when a row arrives.
    float above = Math.max(earlierRows * rowHeight, earlierHeight);
    float nextHeight = next == null ? 0.0F : extraHeight(next, baseScale, width);
    float backgroundHeight = background == null ? 0.0F : extraHeight(background, baseScale, width);
    float below = backgroundHeight + Math.max(look.nextLine ? rowHeight : 0.0F, nextHeight);
    float height = g.guiHeight();
    float mainTop = spotTop(look.screenSpot, height, mainHeight, above, below);

    if (leavingLine != null) {
      leavingComposer.voice(leavingLine.voice());
      ComposedLine leaving = leavingComposer.compose(look, font, style, leavingLine.text(),
          ms - leavingLine.startMs(), leavingLine.windowMs(), leavingLine.parts(), nowMs, leavingLine.startMs(), colors);
      if (leaving.finished) {
        leavingLine = null;
      } else {
        float scale = fit(leaving, baseScale, width);
        float top = spotTop(look.screenSpot, height, leaving.height * scale, above, below);
        // In a duet it can still be being sung, and then the new line must not lie on top of it.
        if (ms < leavingLine.sungEndMs()) {
          top = Math.max(EDGE, mainTop - leaving.height * scale - ROW_GAP);
        }

        FlatDrawer.draw(g, font, style, leaving, look, centerX, top, scale);
      }
    }

    if (singing) {
      FlatDrawer.draw(g, font, style, main, look, centerX, mainTop, mainScale);
    } else if (look.breakDots) {
      breakDots(g, look, lyrics, index, ms, centerX, mainTop + mainHeight * 0.5F);
    }

    float y = mainTop + mainHeight + ROW_GAP;
    if (background != null) {
      FlatDrawer.draw(g, font, style, background, BEHIND, centerX, y, fit(background, baseScale, width));
      y += backgroundHeight;
    }

    if (next != null) {
      FlatDrawer.draw(g, font, style, next, EXTRA, centerX, y, fit(next, baseScale, width));
    }

    float up = mainTop - ROW_GAP;
    for (int i = 0; i < earlierRows; i++) {
      ComposedLine row = EARLIER_ROWS[i];
      if (row != null) {
        up -= extraHeight(row, baseScale, width);
        FlatDrawer.draw(g, font, style, row, EXTRA, centerX, up, fit(row, baseScale, width));
      }
    }
  }

  /** The line being sung or still leaving, or null when it is gone. */
  private static ComposedLine composeMain(Look look, Font font, Style style, long ms, long nowMs, int[] colors) {
    if (mainLine == null) {
      return null;
    }

    if (mainDone) {
      if (ms >= mainLine.endMs()) {
        return null;
      }

      mainDone = false;
    }

    mainComposer.voice(mainLine.voice());
    ComposedLine main = mainComposer.compose(look, font, style, mainLine.text(), ms - mainLine.startMs(),
        mainLine.windowMs(), mainLine.parts(), nowMs, mainLine.startMs(), colors);
    if (main.finished) {
      mainDone = true;
      return null;
    }

    return main;
  }

  private static void changeLine(TimedLyrics lyrics, int index, long ms) {
    boolean forward = index > mainIndex && mainLine != null;
    if (forward && hasText(mainLine) && ms - mainLine.endMs() < LONGEST_EXIT_MS) {
      // A composed line belongs to its composer, so the leaving line takes the composer along.
      LineComposer spare = leavingComposer;
      leavingComposer = mainComposer;
      mainComposer = spare;
      leavingLine = mainLine;
    } else {
      leavingLine = null;
    }

    mainIndex = index;
    mainDone = false;
    mainLine = index >= 0 ? lyrics.get(index) : null;
  }

  private static void prepareExtraLook(Look look) {
    EXTRA.copyLookFrom(look);
    EXTRA.size = look.size * EXTRA_SIZE;
    EXTRA.depth = look.depth * EXTRA_DEPTH;
    EXTRA.enter = "FADE";
    EXTRA.exit = "NONE";
    EXTRA.idle = "NONE";
    EXTRA.karaoke = false;
    EXTRA.arrange = "LINE";
    BEHIND.copyLookFrom(EXTRA);
    BEHIND.karaoke = look.karaoke;
    BEHIND.karaokeStyle = look.karaokeStyle;
    BEHIND.exit = "FADE";
  }

  private static ComposedLine extraRow(LineComposer composer, Font font, Style style, String text, long elapsedMs,
      long nowMs, long seed, int[] colors, float alpha) {
    ComposedLine row = composer.compose(EXTRA, font, style, text, elapsedMs, EXTRA_WINDOW_MS, null, nowMs, seed, colors);
    fadeRow(row, alpha);
    return row;
  }

  private static void fadeRow(ComposedLine row, float alpha) {
    List<Letter> letters = row.letters;
    for (int i = 0; i < letters.size(); i++) {
      Letter l = letters.get(i);
      int faded = Math.round((l.argb >>> 24) * alpha);
      l.argb = faded < 5 ? 0 : faded << 24 | (l.argb & 0xFFFFFF);
    }
  }

  private static float extraHeight(ComposedLine row, float baseScale, float width) {
    return row.height * fit(row, baseScale, width) + ROW_GAP;
  }

  /** Long lines shrink to stay on the screen. Based on the width at rest, so it holds still while the line animates. */
  private static float fit(ComposedLine line, float scale, float width) {
    return Math.min(scale, (width - EDGE * 2.0F) / Math.max(1.0F, line.width));
  }

  private static float spotTop(String spot, float height, float mainHeight, float above, float below) {
    float top = switch (spot) {
      case "TOP" -> EDGE + above;
      case "MIDDLE" -> (height - mainHeight) * 0.5F;
      default -> height - BOTTOM_CLEAR - below - mainHeight;
    };
    return Math.max(EDGE, top);
  }

  /** Squares filling up, one after the other, over a long stretch without singing. */
  private static void breakDots(GuiGraphics g, Look look, TimedLyrics lyrics, int index, long ms, float centerX,
      float middleY) {
    if (index + 1 >= lyrics.size()) {
      return;
    }

    long from = 0L;
    if (index >= 0) {
      // An empty line is how a lyrics file marks a break, so the break runs from its start.
      TimedLine line = lyrics.get(index);
      from = hasText(line) ? line.sungEndMs() : line.startMs();
    }

    long to = lyrics.get(index + 1).firstSungMs();
    if (to - from < BREAK_FROM_MS || ms < from) {
      return;
    }

    float progress = (ms - from) / (float) (to - from) * BREAK_DOTS;
    int rgb = look.color & 0xFFFFFF;
    int wide = BREAK_DOTS * BREAK_DOT_SIZE + (BREAK_DOTS - 1) * BREAK_DOT_GAP;
    int left = Math.round(centerX - wide * 0.5F);
    int top = Math.round(middleY - BREAK_DOT_SIZE * 0.5F);
    for (int i = 0; i < BREAK_DOTS; i++) {
      int x = left + i * (BREAK_DOT_SIZE + BREAK_DOT_GAP);
      g.fill(x, top, x + BREAK_DOT_SIZE, top + BREAK_DOT_SIZE, 0x48000000 | rgb);
      int filled = Math.round(Math.max(0.0F, Math.min(1.0F, progress - i)) * BREAK_DOT_SIZE);
      if (filled > 0) {
        int bottom = top + BREAK_DOT_SIZE;
        g.fill(x, bottom - filled, x + BREAK_DOT_SIZE, bottom, 0xFF000000 | rgb);
      }
    }
  }

  private static boolean hasText(TimedLine line) {
    return line != null && line.text() != null && !line.text().isBlank();
  }

  // Asked for each frame, while a reading only changes a few times a second.
  private static String keyOf(NowPlaying song) {
    if (song != keyedSong) {
      keyedSong = song;
      keyedKey = song.key();
    }

    return keyedKey;
  }

  private static void forget() {
    shownLyrics = null;
    mainIndex = NO_LINE;
    mainLine = null;
    mainDone = false;
    leavingLine = null;
  }
}
