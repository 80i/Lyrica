package net.lyrica.v26_3.hud;

import java.util.Locale;
import net.labymod.api.util.I18n;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.lyrica.v26_3.look.Fonts;
import net.lyrica.v26_3.look.Look;
import net.lyrica.v26_3.look.Palette;
import net.lyrica.v26_3.song.NowPlaying;
import net.lyrica.v26_3.song.Playback;
import net.lyrica.v26_3.words.DemoLines;
import net.lyrica.v26_3.words.LyricsLibrary;
import net.lyrica.v26_3.words.TimedLine;
import net.lyrica.v26_3.words.TimedLyrics;
import org.joml.Matrix3x2fStack;

/**
 * The now-playing card. It reads like a line ruled on a record sleeve: square cover flush with the edge, a hairline
 * above, the time as a hairline below with a square playhead, one short stroke of the accent colour.
 *
 * <p>Sizes in here are card units, which {@code cardSize} turns into GUI pixels.
 */
public final class NowCard {

  public record Bounds(int x, int y, int w, int h) {

    public boolean contains(double px, double py) {
      return px >= this.x && py >= this.y && px < this.x + this.w && py < this.y + this.h;
    }
  }

  private static final int MARGIN = 4;
  private static final int PAD = 6;
  private static final int LINE_ROW = 10;
  private static final int STRIP_W = 178;
  private static final int STRIP_H = 28;
  private static final int DISC_W = 184;
  private static final int DISC_SIZE = 48;
  private static final int DISC_PANEL_H = 30;
  private static final int COVER_W = 112;
  private static final int COVER_NAMES_H = 37;
  private static final int TICKER_H = 12;

  private static final float TITLE_K = 1.3F;
  private static final float NAME_K = 0.78F;
  private static final float CLOCK_K = 0.72F;
  private static final float STATUS_K = 0.95F;

  private static final int PANEL = Palette.INK;
  private static final float PANEL_SHARE = 0.86F;
  private static final int BLANK_COVER = 0x24211C;
  private static final int NAME_COLOR = Palette.STONE;
  private static final float RULE_SHARE = 0.24F;
  private static final float TRACK_SHARE = 0.18F;
  private static final int ACCENT_STROKE = 10;

  // A dip through nothing rather than two sets of names on top of each other.
  private static final float FADE_OUT_S = 0.14F;
  private static final float FADE_IN_S = 0.2F;
  private static final float CARD_FADE_S = 0.25F;
  private static final float LONGEST_FRAME_S = 0.1F;

  private static final long ROLL_HOLD_MS = 1_600L;
  private static final float ROLL_UNITS_PER_S = 12.0F;
  private static final float ROLL_GAP = 26.0F;
  private static final float TICKER_UNITS_PER_S = 24.0F;
  private static final float TICKER_GAP = 16.0F;
  private static final float DISC_RADIANS_PER_S = 1.6F;
  private static final float FULL_TURN = (float) (Math.PI * 2.0);

  // Without a panel behind it the text needs its own help against the world.
  private static final float SHADOW_BELOW_OPACITY = 0.45F;

  private static final Style TITLE_STYLE = Fonts.style("BEBAS", false);
  private static final Style NAME_STYLE = Fonts.style("MONTSERRAT", false);
  private static final Style CLOCK_STYLE = Fonts.style("JETBRAINS_MONO", false);

  private static final Label TITLE = new Label();
  private static final Label ARTIST = new Label();
  private static final Label LINE = new Label();
  private static final Label STATUS = new Label();
  private static final Label MONOGRAM = new Label();
  private static final Label TIME = new Label();
  private static final Label TIME_FULL = new Label();

  private static NowPlaying shown;
  private static String shownKey;
  private static NowPlaying sample;
  private static String sampleKey;
  private static float cardFade;
  private static float contentFade;
  private static long lastFrameMs;
  private static long shownSecond = -1L;
  private static LyricsLibrary.State statusState;
  private static float tickerScroll;
  private static float discAngle;

  // What this frame's drawing shares, to keep the many small helpers short.
  private static Font font;
  private static long nowMs;
  private static float panelAlpha;
  private static float textAlpha;
  private static int accent;
  private static boolean textShadow;

  private NowCard() {
  }

  /** Where the card stands on the screen in GUI pixels. Empty for the style NONE. */
  public static Bounds bounds(Look look) {
    int screenW = screenWidth();
    int screenH = screenHeight();
    int w;
    if ("NONE".equals(look.cardStyle)) {
      w = 0;
    } else if ("TICKER".equals(look.cardStyle)) {
      w = screenW;
    } else {
      w = Math.round(unitsWide(look) * look.cardSize);
    }

    int h = "NONE".equals(look.cardStyle) ? 0 : Math.round(unitsHigh(look) * look.cardSize);
    int x;
    int y;
    if (look.cardX < 0.0F || look.cardY < 0.0F) {
      x = look.cardCorner.endsWith("RIGHT") ? screenW - w - MARGIN : MARGIN;
      y = look.cardCorner.startsWith("BOTTOM") ? screenH - h - MARGIN : MARGIN;
      if ("TICKER".equals(look.cardStyle)) {
        x = 0;
        y = look.cardCorner.startsWith("BOTTOM") ? screenH - h : 0;
      }
    } else {
      x = Math.round(look.cardX * Math.max(0, screenW - w));
      y = Math.round(look.cardY * Math.max(0, screenH - h));
    }

    return new Bounds(x, y, w, h);
  }

  /**
   * Puts the card's top left corner here. Saved as a share of the free room rather than of the screen, so that the whole
   * card stays on the screen at every GUI scale and card size. The caller saves the look.
   */
  public static void moveTo(Look look, double x, double y) {
    Bounds now = bounds(look);
    look.cardX = share(x, screenWidth() - now.w);
    look.cardY = share(y, screenHeight() - now.h);
  }

  public static void render(GuiGraphicsExtractor g, NowPlaying song, Look look) {
    if (!look.cardOn || "NONE".equals(look.cardStyle)) {
      forget();
      return;
    }

    nowMs = System.currentTimeMillis();
    float seconds = Math.max(0.0F, Math.min(LONGEST_FRAME_S, (nowMs - lastFrameMs) / 1000.0F));
    lastFrameMs = nowMs;

    AlbumTexture.update(song);
    follow(song, seconds);
    if (shown == null) {
      return;
    }

    font = Minecraft.getInstance().font;
    panelAlpha = cardFade * look.cardOpacity;
    textAlpha = cardFade * contentFade;
    accent = look.cardAccent & 0xFFFFFF;
    textShadow = look.cardOpacity < SHADOW_BELOW_OPACITY;
    prepareLabels(look);

    Bounds b = bounds(look);
    float scale = look.cardSize;
    int w = Math.round(b.w / scale);
    int h = Math.round(b.h / scale);
    Matrix3x2fStack pose = g.pose();
    pose.pushMatrix();
    pose.translate(b.x, b.y);
    pose.scale(scale, scale);
    switch (look.cardStyle) {
      case "DISC" -> disc(g, look, w, h, seconds);
      case "COVER" -> cover(g, look, w, h);
      case "TICKER" -> ticker(g, look, w, h, seconds, b.y < screenHeight() / 2);
      default -> strip(g, look, w, h);
    }

    pose.popMatrix();
  }

  /** Forgets the song and lets go of the cover, for when the addon is switched off. */
  public static void clear() {
    forget();
    AlbumTexture.clear();
  }

  // Following the song

  private static void follow(NowPlaying song, float seconds) {
    if (song == null) {
      cardFade = Math.max(0.0F, cardFade - seconds / CARD_FADE_S);
      if (cardFade <= 0.0F) {
        forget();
      }

      return;
    }

    cardFade = Math.min(1.0F, cardFade + seconds / CARD_FADE_S);
    if (song != sample) {
      sample = song;
      sampleKey = song.key();
    }

    if (shown == null) {
      show(song);
    } else if (sampleKey.equals(shownKey)) {
      shown = song;
      contentFade = Math.min(1.0F, contentFade + seconds / FADE_IN_S);
    } else {
      contentFade -= seconds / FADE_OUT_S;
      if (contentFade <= 0.0F) {
        show(song);
      }
    }
  }

  private static void show(NowPlaying song) {
    shown = song;
    shownKey = sampleKey;
    contentFade = 0.0F;
    tickerScroll = 0.0F;
    shownSecond = -1L;
    String title = song.title() == null || song.title().isBlank() ? I18n.translate("lyrica.card.untitled")
        : song.title().strip();
    TITLE.set(title, TITLE_STYLE);
    String artist = song.artist() == null || song.artist().isBlank() ? song.sourceApp() : song.artist().strip();
    ARTIST.set(artist == null ? "" : artist, NAME_STYLE);
    int first = title.codePointAt(0);
    MONOGRAM.set(new String(Character.toChars(first)).toUpperCase(Locale.ROOT), TITLE_STYLE);
  }

  private static void forget() {
    shown = null;
    shownKey = null;
    sample = null;
    sampleKey = null;
    cardFade = 0.0F;
    contentFade = 0.0F;
    statusState = null;
  }

  private static void prepareLabels(Look look) {
    long position = shown.positionNow(0L);
    long second = position / 1000L;
    if (second != shownSecond) {
      shownSecond = second;
      String elapsed = clock(position);
      TIME.set(elapsed, CLOCK_STYLE);
      TIME_FULL.set(shown.durationMs() > 0L ? elapsed + " / " + clock(shown.durationMs()) : elapsed, CLOCK_STYLE);
    }

    LyricsLibrary.State state = LyricsLibrary.get().state();
    if (state != statusState) {
      statusState = state;
      String key = switch (state) {
        case SEARCHING -> "lyrica.card.searching";
        case NOT_FOUND -> "lyrica.card.not_found";
        case OFFLINE -> "lyrica.card.offline";
        default -> null;
      };
      STATUS.set(key == null ? "" : I18n.translate(key), TITLE_STYLE);
    }

    if (look.cardLine) {
      LINE.set(sungLine(look), NAME_STYLE);
    }
  }

  private static String sungLine(Look look) {
    TimedLyrics lyrics = LyricsLibrary.get().current();
    NowPlaying clock = shown;
    if (DemoLines.active(Playback.get().current(), lyrics)) {
      lyrics = DemoLines.lyrics();
      clock = DemoLines.song();
    }

    if (lyrics == null) {
      return "";
    }

    String key = clock == shown ? shownKey : clock.key();
    TimedLine line = lyrics.lineAt(clock.positionNow(look.offsetFor(key)));
    return line == null || line.text() == null ? "" : line.text();
  }

  // The styles

  private static void strip(GuiGraphicsExtractor g, Look look, int w, int h) {
    int side = look.cardCover ? h : 0;
    box(g, side, 0, w, h, PANEL, panelAlpha * PANEL_SHARE);
    if (look.cardCover) {
      artwork(g, 0, 0, side);
    }

    rule(g, side, w, 0);
    names(g, look, side + PAD, w - PAD, 4);
    progress(g, side, w, h - 1);
  }

  /** A record half out of its sleeve: the disc stands proud of the panel above and below. */
  private static void disc(GuiGraphicsExtractor g, Look look, int w, int h, float seconds) {
    int panelH = DISC_PANEL_H + (look.cardLine ? LINE_ROW : 0);
    int top = (h - panelH) / 2;
    int middle = DISC_SIZE / 2;
    box(g, middle, top, w, top + panelH, PANEL, panelAlpha * PANEL_SHARE);
    rule(g, middle, w, top);
    names(g, look, DISC_SIZE + PAD, w - PAD, top + 5);
    progress(g, middle, w, top + panelH - 1);

    if (shown.playing()) {
      discAngle = (discAngle + seconds * DISC_RADIANS_PER_S) % FULL_TURN;
    }

    Matrix3x2fStack pose = g.pose();
    pose.pushMatrix();
    pose.translate(middle, h * 0.5F);
    pose.rotate(discAngle);
    texture(g, AlbumTexture.blankRecord(), -middle, -middle, DISC_SIZE, cardFade);
    if (look.cardCover) {
      float fade = AlbumTexture.fade(shown) * textAlpha;
      if (fade > 0.0F) {
        texture(g, AlbumTexture.RECORD, -middle, -middle, DISC_SIZE, fade);
      }
    }

    pose.popMatrix();
  }

  private static void cover(GuiGraphicsExtractor g, Look look, int w, int h) {
    int art = look.cardCover ? w : 0;
    if (look.cardCover) {
      artwork(g, 0, 0, art);
    }

    box(g, 0, art, w, h, PANEL, panelAlpha * PANEL_SHARE);
    int left = PAD;
    int right = w - PAD;
    int y = art + 4;
    rolling(g, TITLE, left, y, right - left, TITLE_K, Palette.PAPER);
    y += 12;
    rolling(g, ARTIST, left, y, right - left, NAME_K, NAME_COLOR);
    y += 9;
    if (look.cardTimes) {
      text(g, TIME_FULL, left, y, CLOCK_K, NAME_COLOR);
      if (!shown.playing()) {
        pauseMark(g, Math.round(left + TIME_FULL.width * CLOCK_K) + 4, y + 1);
      }
    } else if (!shown.playing()) {
      pauseMark(g, left, y + 1);
    }

    text(g, STATUS, right - STATUS.width * STATUS_K, y - 1, STATUS_K, accent);
    if (look.cardLine) {
      rolling(g, LINE, left, y + 9, right - left, NAME_K, Palette.PAPER);
    }

    progress(g, 0, w, art);
  }

  private static void ticker(GuiGraphicsExtractor g, Look look, int w, int h, float seconds, boolean atTop) {
    box(g, 0, 0, w, h, PANEL, panelAlpha * PANEL_SHARE);
    progress(g, 0, w, atTop ? h - 1 : 0);

    int clockRoom = look.cardTimes ? Math.round(TIME_FULL.width * CLOCK_K) + PAD * 2 : 0;
    int end = w - clockRoom;
    float loop = tickerLoop(look);
    tickerScroll += seconds * TICKER_UNITS_PER_S;
    while (loop > 0.0F && tickerScroll >= loop) {
      tickerScroll -= loop;
    }

    g.enableScissor(0, 0, end, h);
    float x = PAD - tickerScroll;
    while (loop > 0.0F && x < end) {
      tickerRound(g, look, x, end);
      x += loop;
    }

    g.disableScissor();
    if (look.cardTimes) {
      if (!shown.playing()) {
        pauseMark(g, end - 3, 3);
      }

      text(g, TIME_FULL, end + PAD, 3, CLOCK_K, NAME_COLOR);
    } else if (!shown.playing()) {
      pauseMark(g, w - PAD - 3, 3);
    }
  }

  private static float tickerLoop(Look look) {
    float loop = tickerPart(TITLE, 1.0F) + tickerPart(ARTIST, NAME_K) + tickerPart(STATUS, STATUS_K);
    return look.cardLine ? loop + tickerPart(LINE, NAME_K) : loop;
  }

  private static float tickerPart(Label label, float k) {
    return label.width == 0 ? 0.0F : label.width * k + TICKER_GAP;
  }

  private static void tickerRound(GuiGraphicsExtractor g, Look look, float start, int end) {
    float x = tickerPiece(g, TITLE, start, 2.0F, 1.0F, Palette.PAPER, end);
    x = tickerPiece(g, ARTIST, x, 3.0F, NAME_K, NAME_COLOR, end);
    if (look.cardLine) {
      x = tickerPiece(g, LINE, x, 3.0F, NAME_K, Palette.PAPER, end);
    }

    tickerPiece(g, STATUS, x, 2.0F, STATUS_K, accent, end);
  }

  private static float tickerPiece(GuiGraphicsExtractor g, Label label, float x, float y, float k, int rgb, int end) {
    if (label.width == 0) {
      return x;
    }

    float after = x + label.width * k;
    if (after > 0.0F && x < end) {
      text(g, label, x, y, k, rgb);
      int mark = Math.round(after + TICKER_GAP * 0.5F) - 1;
      box(g, mark, 5, mark + 2, 7, accent, textAlpha);
    }

    return after + TICKER_GAP;
  }

  // The parts

  /** Title and clock on one row, artist and status on the next, then the sung line when asked for. */
  private static void names(GuiGraphicsExtractor g, Look look, int left, int right, int top) {
    float used = clockAtRight(g, look, right, top + 4);
    rolling(g, TITLE, left, top, right - left - used, TITLE_K, Palette.PAPER);

    float statusWide = STATUS.width * STATUS_K;
    if (STATUS.width > 0) {
      text(g, STATUS, right - statusWide, top + 11, STATUS_K, accent);
      statusWide += PAD;
    }

    rolling(g, ARTIST, left, top + 12, right - left - statusWide, NAME_K, NAME_COLOR);
    if (look.cardLine) {
      rolling(g, LINE, left, top + 21, right - left, NAME_K, Palette.PAPER);
    }
  }

  /** @return how much of the row it took */
  private static float clockAtRight(GuiGraphicsExtractor g, Look look, int right, int y) {
    float used = 0.0F;
    if (look.cardTimes) {
      used = TIME.width * CLOCK_K;
      text(g, TIME, right - used, y, CLOCK_K, NAME_COLOR);
      used += PAD;
    }

    if (!shown.playing()) {
      used += 3.0F;
      pauseMark(g, Math.round(right - used), y);
      used += PAD;
    }

    return used;
  }

  private static void pauseMark(GuiGraphicsExtractor g, int x, int y) {
    box(g, x, y, x + 1, y + 5, Palette.PAPER, textAlpha);
    box(g, x + 2, y, x + 3, y + 5, Palette.PAPER, textAlpha);
  }

  /** The cover flush in its square, or the title's first letter set large when there is no cover. */
  private static void artwork(GuiGraphicsExtractor g, int x, int y, int side) {
    float fade = AlbumTexture.fade(shown) * textAlpha;
    box(g, x, y, x + side, y + side, BLANK_COVER, cardFade);
    if (fade < 1.0F && MONOGRAM.width > 0) {
      float k = side * 0.62F / font.lineHeight;
      float restore = textAlpha;
      textAlpha = textAlpha * (1.0F - fade);
      text(g, MONOGRAM, x + (side - MONOGRAM.width * k) * 0.5F, y + side * 0.22F, k, accent);
      textAlpha = restore;
    }

    if (fade > 0.0F) {
      texture(g, AlbumTexture.COVER, x, y, side, fade);
    }
  }

  private static void rule(GuiGraphicsExtractor g, int x0, int x1, int y) {
    box(g, x0, y, x1, y + 1, Palette.PAPER, panelAlpha * RULE_SHARE);
    box(g, x0, y, Math.min(x0 + ACCENT_STROKE, x1), y + 1, accent, textAlpha);
  }

  private static void progress(GuiGraphicsExtractor g, int x0, int x1, int y) {
    box(g, x0, y, x1, y + 1, Palette.PAPER, panelAlpha * TRACK_SHARE);
    long duration = shown.durationMs();
    if (duration <= 0L) {
      return;
    }

    float part = Math.min(1.0F, shown.positionNow(0L) / (float) duration);
    int head = x0 + Math.round((x1 - x0 - 3) * part);
    box(g, x0, y, head, y + 1, accent, textAlpha);
    box(g, head, y - 1, head + 3, y + 2, accent, textAlpha);
  }

  private static void texture(GuiGraphicsExtractor g, Identifier id, int x, int y, int side, float alpha) {
    int size = AlbumTexture.SIZE;
    g.blit(RenderPipelines.GUI_TEXTURED, id, x, y, 0.0F, 0.0F, side, side, size, size, size, size,
        color(0xFFFFFF, alpha));
  }

  private static void box(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int rgb, float alpha) {
    int argb = color(rgb, alpha);
    if ((argb >>> 24) > 0) {
      g.fill(x0, y0, x1, y1, argb);
    }
  }

  private static void text(GuiGraphicsExtractor g, Label label, float x, float y, float k, int rgb) {
    int argb = color(rgb, textAlpha);
    // Below this the game's text drawing would take the colour as fully opaque.
    if (label.sequence == null || label.width == 0 || (argb >>> 24) < 5) {
      return;
    }

    Matrix3x2fStack pose = g.pose();
    pose.pushMatrix();
    pose.translate(x, y);
    pose.scale(k, k);
    g.text(font, label.sequence, 0, 0, argb, textShadow);
    pose.popMatrix();
  }

  /** Text that fits stands still. Longer text waits, then rolls by slowly, its start following its end around. */
  private static void rolling(GuiGraphicsExtractor g, Label label, float x, float y, float room, float k, int rgb) {
    if (room <= 0.0F) {
      return;
    }

    float wide = label.width * k;
    if (wide <= room) {
      text(g, label, x, y, k, rgb);
      return;
    }

    float travel = wide + ROLL_GAP;
    long age = nowMs - label.sinceMs - ROLL_HOLD_MS;
    float along = age <= 0L ? 0.0F : (age * ROLL_UNITS_PER_S / 1000.0F) % travel;
    int top = (int) Math.floor(y) - 1;
    g.enableScissor((int) Math.floor(x), top, (int) Math.floor(x + room), top + 3 + (int) Math.ceil(font.lineHeight * k));
    text(g, label, x - along, y, k, rgb);
    if (along > 0.0F) {
      text(g, label, x - along + travel, y, k, rgb);
    }

    g.disableScissor();
  }

  // Small things

  private static int unitsWide(Look look) {
    return switch (look.cardStyle) {
      case "DISC" -> DISC_W;
      case "COVER" -> COVER_W;
      default -> STRIP_W;
    };
  }

  private static int unitsHigh(Look look) {
    int line = look.cardLine ? LINE_ROW : 0;
    return switch (look.cardStyle) {
      case "DISC" -> DISC_SIZE;
      case "COVER" -> (look.cardCover ? COVER_W : 0) + COVER_NAMES_H + line;
      case "TICKER" -> TICKER_H;
      default -> STRIP_H + line;
    };
  }

  private static int screenWidth() {
    return Minecraft.getInstance().getWindow().getGuiScaledWidth();
  }

  private static int screenHeight() {
    return Minecraft.getInstance().getWindow().getGuiScaledHeight();
  }

  private static float share(double position, int room) {
    return room <= 0 ? 0.0F : (float) Math.max(0.0, Math.min(1.0, position / room));
  }

  private static int color(int rgb, float alpha) {
    int byteAlpha = Math.round(Math.max(0.0F, Math.min(1.0F, alpha)) * 255.0F);
    return byteAlpha << 24 | (rgb & 0xFFFFFF);
  }

  private static String clock(long ms) {
    long whole = ms / 1000L;
    long hours = whole / 3600L;
    long minutes = whole / 60L % 60L;
    long seconds = whole % 60L;
    StringBuilder out = new StringBuilder(8);
    if (hours > 0L) {
      out.append(hours).append(':');
      if (minutes < 10L) {
        out.append('0');
      }
    }

    out.append(minutes).append(':');
    if (seconds < 10L) {
      out.append('0');
    }

    return out.append(seconds).toString();
  }

  /** A piece of text with its measured width, made again only when the text changes. */
  private static final class Label {

    private String text;
    private FormattedCharSequence sequence;
    private int width;
    private long sinceMs;

    void set(String value, Style style) {
      if (value.equals(this.text)) {
        return;
      }

      this.text = value;
      // Through a component, because that puts right-to-left names in their visual order.
      this.sequence = Component.literal(value).setStyle(style).getVisualOrderText();
      this.width = Minecraft.getInstance().font.width(this.sequence);
      this.sinceMs = System.currentTimeMillis();
    }
  }
}
