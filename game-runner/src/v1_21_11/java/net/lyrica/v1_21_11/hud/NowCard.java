package net.lyrica.v1_21_11.hud;

import java.util.Locale;
import net.labymod.api.util.I18n;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.lyrica.v1_21_11.look.Fonts;
import net.lyrica.v1_21_11.look.Look;
import net.lyrica.v1_21_11.look.Palette;
import net.lyrica.v1_21_11.song.NowPlaying;
import net.lyrica.v1_21_11.song.Playback;
import net.lyrica.v1_21_11.words.DemoLines;
import net.lyrica.v1_21_11.words.LyricsLibrary;
import net.lyrica.v1_21_11.words.TimedLine;
import net.lyrica.v1_21_11.words.TimedLyrics;
import org.joml.Matrix3x2fStack;

/**
 * The now-playing card, a little player that goes with the card over the name in each of its forms: a rounded panel with a
 * one-pixel rim, the title in bold over the artist, the time, and the song's own wave, which fills up as the song goes on
 * and lies down when it pauses. STRIP has the cover as its whole left end, DISC a turning record standing proud of the
 * panel, COVER the cover large on top. TICKER runs along the screen's edge, so it has one square edge-to-edge band, with a
 * small cover, the names scrolling, a short wave and the time.
 *
 * <p>Sizes in here are card units, which {@code cardSize} turns into GUI pixels. The shapes go straight to screen pixels, so
 * that their edges stay hard at every GUI scale and card size.
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
  private static final int STRIP_W = 180;
  private static final int STRIP_H = 38;
  private static final int DISC_W = 204;
  private static final int DISC_SIZE = 54;
  private static final int DISC_PANEL_H = 38;
  private static final int COVER_W = 124;
  private static final int COVER_NAMES_H = 38;
  private static final int TICKER_H = 16;
  private static final int TICKER_WAVE_W = 46;
  private static final float TICKER_WAVE_HEIGHT = 8.0F;
  private static final float TICKER_THUMB_INSET = 1.0F;
  private static final float TICKER_THUMB_CORNERS = 3.0F;

  private static final float TITLE_K = 0.92F;
  private static final float ARTIST_K = 0.86F;
  private static final float LINE_K = 0.78F;
  private static final float CORNER_K = 0.78F;
  private static final float STATUS_K = 0.72F;

  // In card units and with the colours of the card over the name
  private static final float CARD_PAD_X = 7.0F;
  private static final float CARD_PAD_Y = 5.5F;
  private static final float CARD_CORNERS = 5.0F;
  private static final float ARTIST_Y = 16.0F;
  private static final float LINE_Y = 25.5F;
  private static final float CAP = 7.0F;
  private static final float BESIDE = 6.0F;
  private static final float PANEL_SHARE = 0.95F;
  private static final float PAUSE_BAR = 1.25F;
  private static final int PANEL_RGB = 0x101216;
  private static final int RIM_RGB = 0x3D424C;
  private static final int TITLE_RGB = 0xF4F5F7;
  private static final int SOFT_RGB = 0xA6ABB5;
  private static final int WAVE_EMPTY_RGB = 0x454A54;
  // Below this the panel no longer carries the words by itself, and the grey ones lift toward white
  private static final float BARE_BELOW = 0.8F;
  private static final float PAUSED_COVER = 0.45F;

  private static final int WAVE_COLUMNS = 64;
  private static final float WAVE_HEIGHT = 6.5F;
  private static final float WAVE_PITCH = 2.25F;
  private static final float WAVE_TICK_SHARE = 0.55F;
  private static final float WAVE_PAUSED = 0.16F;
  private static final float WAVE_LOWEST = 0.22F;
  private static final float PAUSE_SETTLE_S = 0.35F;

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

  // Bold Montserrat rather than Bebas, which has no Cyrillic and left such titles to the game's pixel font
  private static final Style TITLE_STYLE = Fonts.style("MONTSERRAT", true);
  private static final Style NAME_STYLE = Fonts.style("MONTSERRAT", false);
  private static final Style CLOCK_STYLE = Fonts.style("JETBRAINS_MONO", false);

  private static final Label TITLE = new Label();
  private static final Label ARTIST = new Label();
  private static final Label LINE = new Label();
  private static final Label STATUS = new Label();
  private static final Label MONOGRAM = new Label();
  private static final Label TIME = new Label();
  private static final Label PAUSED = new Label();

  /** How this song's wave stands, made from its name so it looks the same whenever it comes around. */
  private static final float[] WAVE_SHAPE = new float[WAVE_COLUMNS];

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
  private static float pausedShare;

  // What this frame's drawing shares, to keep the many small helpers short.
  private static Font font;
  private static long nowMs;
  private static float panelAlpha;
  private static float textAlpha;
  private static int accent;
  private static boolean textShadow;
  private static int pixelsWide;
  private static int pixelsHigh;
  private static float pixelsPerUnit;
  private static int softRgb;
  private static int waveEmptyRgb;

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

  public static void render(GuiGraphics g, NowPlaying song, Look look) {
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
    float bare = Math.max(0.0F, 1.0F - look.cardOpacity / BARE_BELOW);
    softRgb = Palette.mix(SOFT_RGB, TITLE_RGB, bare);
    waveEmptyRgb = Palette.mix(WAVE_EMPTY_RGB, SOFT_RGB, bare);
    pausedShare = shown.playing() ? Math.max(0.0F, pausedShare - seconds / PAUSE_SETTLE_S)
        : Math.min(1.0F, pausedShare + seconds / PAUSE_SETTLE_S);
    prepareLabels(look);

    Bounds b = bounds(look);
    float scale = look.cardSize;
    int guiScale = Minecraft.getInstance().getWindow().getGuiScale();
    pixelsWide = b.w * guiScale;
    pixelsHigh = b.h * guiScale;
    pixelsPerUnit = scale * guiScale;
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
    String title = song.title() == null || song.title().isBlank() ? I18n.translate("lyrical.card.untitled")
        : song.title().strip();
    TITLE.set(title, TITLE_STYLE);
    String artist = song.artist() == null || song.artist().isBlank() ? song.sourceApp() : song.artist().strip();
    ARTIST.set(artist == null ? "" : artist, NAME_STYLE);
    PAUSED.set(I18n.translate("lyrical.card.paused"), NAME_STYLE);
    shapeWave(title, artist == null ? "" : artist);
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
      TIME.set(clock(position), CLOCK_STYLE);
    }

    LyricsLibrary.State state = LyricsLibrary.get().state();
    if (state != statusState) {
      statusState = state;
      String key = switch (state) {
        case SEARCHING -> "lyrical.card.searching";
        case NOT_FOUND -> "lyrical.card.not_found";
        case OFFLINE -> "lyrical.card.offline";
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

  private static void strip(GuiGraphics g, Look look, int w, int h) {
    float left = snap((look.cardCover ? h : 0) + CARD_PAD_X);
    float right = w - CARD_PAD_X;
    pushPixels(g);
    roundedPanel(g, 0, 0, pixelsWide, pixelsHigh);
    if (look.cardCover) {
      coverArt(g, 1, 1, pixelsHigh - 2, cornerPixels() - 1, AlbumTexture.Rounded.LEFT);
    }

    wave(g, left, right, h - CARD_PAD_Y, WAVE_HEIGHT);
    g.pose().popMatrix();
    names(g, look, left, right, 0.0F);
  }

  /** A record half out of its sleeve: the disc stands proud of the panel above and below. */
  private static void disc(GuiGraphics g, Look look, int w, int h, float seconds) {
    int panelH = DISC_PANEL_H + (look.cardLine ? LINE_ROW : 0);
    int top = (h - panelH) / 2;
    int middle = DISC_SIZE / 2;
    float left = snap(DISC_SIZE + PAD);
    float right = w - CARD_PAD_X;
    pushPixels(g);
    roundedPanel(g, pixels(middle), pixels(top), pixelsWide, pixels(top + panelH));
    wave(g, left, right, top + panelH - CARD_PAD_Y, WAVE_HEIGHT);
    g.pose().popMatrix();
    names(g, look, left, right, top);

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

  /** One rounded card with the cover filling its top, one pixel inside the rim. */
  private static void cover(GuiGraphics g, Look look, int w, int h) {
    int art = look.cardCover ? w : 0;
    float left = CARD_PAD_X;
    float right = w - CARD_PAD_X;
    pushPixels(g);
    roundedPanel(g, 0, 0, pixelsWide, pixelsHigh);
    if (look.cardCover) {
      coverArt(g, 1, 1, pixelsWide - 2, cornerPixels() - 1, AlbumTexture.Rounded.TOP);
    }

    wave(g, left, right, h - CARD_PAD_Y, WAVE_HEIGHT);
    g.pose().popMatrix();
    names(g, look, left, right, art);
  }

  /** A band along the screen's edge, so square, with its rim on the side that faces the screen. */
  private static void ticker(GuiGraphics g, Look look, int w, int h, float seconds, boolean atTop) {
    pushPixels(g);
    float alpha = panelAlpha * PANEL_SHARE;
    box(g, 0, atTop ? 0 : 1, pixelsWide, pixelsHigh - (atTop ? 1 : 0), PANEL_RGB, alpha);
    int rimY = atTop ? pixelsHigh - 1 : 0;
    box(g, 0, rimY, pixelsWide, rimY + 1, RIM_RGB, alpha);
    int start = PAD;
    if (look.cardCover) {
      int inset = Math.max(1, pixels(TICKER_THUMB_INSET));
      int side = pixelsHigh - inset * 2;
      coverArt(g, inset, inset, side, pixels(TICKER_THUMB_CORNERS), AlbumTexture.Rounded.ALL);
      start = (int) Math.ceil((inset + side) / pixelsPerUnit) + PAD;
    }

    g.pose().popMatrix();

    float right = w - PAD;
    float waveRight = right - cornerWords(g, look, right, snap((h - CAP) * 0.5F));
    float waveLeft = waveRight - TICKER_WAVE_W;
    int end = (int) Math.floor(waveLeft) - PAD;
    pushPixels(g);
    wave(g, waveLeft, waveRight, h - (h - TICKER_WAVE_HEIGHT) * 0.5F, TICKER_WAVE_HEIGHT);
    g.pose().popMatrix();

    float loop = tickerLoop(look);
    tickerScroll += seconds * TICKER_UNITS_PER_S;
    while (loop > 0.0F && tickerScroll >= loop) {
      tickerScroll -= loop;
    }

    g.enableScissor(start, 0, end, h);
    float x = start - tickerScroll;
    while (loop > 0.0F && x < end) {
      tickerRound(g, look, x, end, h);
      x += loop;
    }

    g.disableScissor();
  }

  private static float tickerLoop(Look look) {
    float loop = tickerPart(TITLE, TITLE_K) + tickerPart(ARTIST, ARTIST_K) + tickerPart(STATUS, STATUS_K);
    return look.cardLine ? loop + tickerPart(LINE, LINE_K) : loop;
  }

  private static float tickerPart(Label label, float k) {
    return label.width == 0 ? 0.0F : label.width * k + TICKER_GAP;
  }

  private static void tickerRound(GuiGraphics g, Look look, float start, int end, int h) {
    float x = tickerPiece(g, TITLE, start, TITLE_K, TITLE_RGB, end, h);
    x = tickerPiece(g, ARTIST, x, ARTIST_K, softRgb, end, h);
    if (look.cardLine) {
      x = tickerPiece(g, LINE, x, LINE_K, TITLE_RGB, end, h);
    }

    tickerPiece(g, STATUS, x, STATUS_K, accent, end, h);
  }

  private static float tickerPiece(GuiGraphics g, Label label, float x, float k, int rgb, int end, int h) {
    if (label.width == 0) {
      return x;
    }

    float after = x + label.width * k;
    if (after > 0.0F && x < end) {
      text(g, label, x, snap((h - CAP * k) * 0.5F), k, rgb);
      int mark = Math.round(after + TICKER_GAP * 0.5F) - 1;
      box(g, mark, h / 2 - 1, mark + 2, h / 2 + 1, accent, textAlpha);
    }

    return after + TICKER_GAP;
  }

  // The parts

  /**
   * Title and the corner's words on one row, artist and status on the next, then the sung line when asked for.
   *
   * @param top where the card's rows start, in card units
   */
  private static void names(GuiGraphics g, Look look, float left, float right, float top) {
    float titleY = snap(top + CARD_PAD_Y);
    float used = cornerWords(g, look, right, titleY);
    rolling(g, TITLE, left, titleY, right - left - used, TITLE_K, TITLE_RGB);

    float artistY = snap(top + ARTIST_Y);
    float statusWide = 0.0F;
    if (STATUS.width > 0) {
      statusWide = STATUS.width * STATUS_K;
      text(g, STATUS, right - statusWide, artistY, STATUS_K, accent);
      statusWide += PAD;
    }

    rolling(g, ARTIST, left, artistY, right - left - statusWide, ARTIST_K, softRgb);
    if (look.cardLine) {
      rolling(g, LINE, left, snap(top + LINE_Y), right - left, LINE_K, TITLE_RGB);
    }
  }

  /**
   * The top line's right end: the time, or in its place the word Paused in the card's colour with two bars before it.
   *
   * @param top where the capitals of the row start, in card units
   * @return how much of the row it took
   */
  private static float cornerWords(GuiGraphics g, Look look, float right, float top) {
    float y = snap(top + CAP * (1.0F - CORNER_K) * 0.5F);
    if (!shown.playing()) {
      float wide = PAUSED.width * CORNER_K;
      float wordLeft = right - wide;
      text(g, PAUSED, wordLeft, y, CORNER_K, accent);
      float high = CAP * CORNER_K;
      float marks = wordLeft - PAUSE_BAR * 1.5F;
      pauseBar(g, marks - PAUSE_BAR, y, high);
      pauseBar(g, marks - PAUSE_BAR * 3.0F, y, high);
      return wide + PAUSE_BAR * 4.5F + BESIDE;
    }

    if (!look.cardTimes) {
      return 0.0F;
    }

    float wide = TIME.width * CORNER_K;
    text(g, TIME, right - wide, y, CORNER_K, softRgb);
    return wide + BESIDE;
  }

  private static void pauseBar(GuiGraphics g, float x, float y, float high) {
    pushPixels(g);
    int x0 = pixels(x);
    int y0 = pixels(y);
    box(g, x0, y0, x0 + Math.max(1, pixels(PAUSE_BAR)), y0 + pixels(high), accent, textAlpha);
    g.pose().popMatrix();
  }

  /** A panel in screen pixels: four corners from one picture, the straight edges and the body between them. */
  private static void roundedPanel(GuiGraphics g, int x0, int y0, int x1, int y1) {
    float alpha = panelAlpha * PANEL_SHARE;
    int argb = color(0xFFFFFF, alpha);
    if ((argb >>> 24) == 0) {
      return;
    }

    int r = cornerPixels();
    Identifier corners = AlbumTexture.panelCorners(r, PANEL_RGB, RIM_RGB);
    cornerOf(g, corners, x0, y0, 0, 0, r, argb);
    cornerOf(g, corners, x1 - r, y0, r, 0, r, argb);
    cornerOf(g, corners, x1 - r, y1 - r, r, r, r, argb);
    cornerOf(g, corners, x0, y1 - r, 0, r, r, argb);

    box(g, x0 + r, y0 + 1, x1 - r, y1 - 1, PANEL_RGB, alpha);
    box(g, x0 + 1, y0 + r, x0 + r, y1 - r, PANEL_RGB, alpha);
    box(g, x1 - r, y0 + r, x1 - 1, y1 - r, PANEL_RGB, alpha);
    box(g, x0 + r, y0, x1 - r, y0 + 1, RIM_RGB, alpha);
    box(g, x0 + r, y1 - 1, x1 - r, y1, RIM_RGB, alpha);
    box(g, x0, y0 + r, x0 + 1, y1 - r, RIM_RGB, alpha);
    box(g, x1 - 1, y0 + r, x1, y1 - r, RIM_RGB, alpha);
  }

  private static void cornerOf(GuiGraphics g, Identifier corners, int x, int y, int u, int v, int r, int argb) {
    g.blit(RenderPipelines.GUI_TEXTURED, corners, x, y, u, v, r, r, r, r, r * 2, r * 2, argb);
  }

  /**
   * The cover in screen pixels. A paused song's steps back a little, so that the card reads as paused at a glance. Without
   * a cover yet, the title's first letter stands there.
   *
   * @param radius its corners' radius in screen pixels
   */
  private static void coverArt(GuiGraphics g, int x, int y, int side, int radius, AlbumTexture.Rounded rounded) {
    float fade = AlbumTexture.fade(shown) * textAlpha;
    if (fade < 1.0F && MONOGRAM.width > 0) {
      float k = side * 0.42F / font.lineHeight;
      float restore = textAlpha;
      textAlpha = textAlpha * (1.0F - fade);
      text(g, MONOGRAM, x + (side - MONOGRAM.width * k) * 0.5F, y + side * 0.33F, k, accent);
      textAlpha = restore;
    }

    if (fade > 0.0F) {
      int size = AlbumTexture.SIZE;
      int texels = Math.round(Math.max(0, radius) * size / (float) side);
      int shade = Math.round((1.0F - (1.0F - PAUSED_COVER) * pausedShare) * 255.0F);
      g.blit(RenderPipelines.GUI_TEXTURED, AlbumTexture.roundedCover(texels, rounded), x, y, 0.0F, 0.0F, side, side, size,
          size, size, size, color(shade << 16 | shade << 8 | shade, fade));
    }
  }

  /**
   * The song's wave: played ticks in the accent colour, the rest in dark grey, the tick at the playhead between the two so
   * that it moves smoothly. It is a drawing and does not hear the music. Positions in card units, drawn in screen pixels.
   */
  private static void wave(GuiGraphics g, float leftUnits, float rightUnits, float floorUnits, float tallestUnits) {
    int left = pixels(leftUnits);
    int across = pixels(rightUnits) - left;
    int floor = pixels(floorUnits);
    int pitch = Math.max(3, pixels(WAVE_PITCH));
    int tick = Math.max(1, (int) (pitch * WAVE_TICK_SHARE));
    int ticks = Math.min(WAVE_COLUMNS, Math.max(2, (across + pitch - tick) / pitch));
    float tallest = tallestUnits * pixelsPerUnit;
    long duration = shown.durationMs();
    float playhead = duration > 0L ? Math.min(1.0F, shown.positionNow(0L) / (float) duration) * ticks : 0.0F;
    double time = (nowMs % 3_600_000L) / 1000.0;
    for (int i = 0; i < ticks; i++) {
      int column = i * WAVE_COLUMNS / ticks;
      float ripple = (float) (0.82 + 0.18 * Math.sin(time * 3.4 - column * 0.37));
      float stands = WAVE_SHAPE[column] * ripple;
      stands += (WAVE_PAUSED - stands) * pausedShare;
      int x = left + Math.round(i * (float) (across - tick) / (ticks - 1));
      int rgb = Palette.mix(waveEmptyRgb, accent, Math.max(0.0F, Math.min(1.0F, playhead - i)));
      box(g, x, floor - Math.max(1, Math.round(stands * tallest)), x + tick, floor, rgb, textAlpha);
    }
  }

  /** Until the pop, drawing goes by whole screen pixels from the card's corner. */
  private static void pushPixels(GuiGraphics g) {
    Matrix3x2fStack pose = g.pose();
    pose.pushMatrix();
    pose.scale(1.0F / pixelsPerUnit, 1.0F / pixelsPerUnit);
  }

  private static int pixels(float units) {
    return Math.round(units * pixelsPerUnit);
  }

  private static int cornerPixels() {
    return Math.max(2, pixels(CARD_CORNERS));
  }

  /** The nearest position on a screen pixel, so that the same text looks the same from frame to frame. */
  private static float snap(float units) {
    return Math.round(units * pixelsPerUnit) / pixelsPerUnit;
  }

  /** Each column leans on the last, so that the wave has phrases rather than static. */
  private static void shapeWave(String title, String artist) {
    int seed = title.hashCode() * 31 + artist.hashCode();
    float last = 0.5F;
    for (int i = 0; i < WAVE_COLUMNS; i++) {
      seed = seed * 1_103_515_245 + 12_345;
      float noise = (seed >>> 16 & 0x7FFF) / 32767.0F;
      last = last * 0.4F + noise * 0.6F;
      float high = Math.max(0.0F, Math.min(1.0F, (last - 0.25F) / 0.5F));
      WAVE_SHAPE[i] = WAVE_LOWEST + (1.0F - WAVE_LOWEST) * high;
    }
  }

  private static void texture(GuiGraphics g, Identifier id, int x, int y, int side, float alpha) {
    int size = AlbumTexture.SIZE;
    g.blit(RenderPipelines.GUI_TEXTURED, id, x, y, 0.0F, 0.0F, side, side, size, size, size, size,
        color(0xFFFFFF, alpha));
  }

  private static void box(GuiGraphics g, int x0, int y0, int x1, int y1, int rgb, float alpha) {
    int argb = color(rgb, alpha);
    if ((argb >>> 24) > 0) {
      g.fill(x0, y0, x1, y1, argb);
    }
  }

  private static void text(GuiGraphics g, Label label, float x, float y, float k, int rgb) {
    int argb = color(rgb, textAlpha);
    // Below this the game's text drawing would take the colour as fully opaque.
    if (label.sequence == null || label.width == 0 || (argb >>> 24) < 5) {
      return;
    }

    Matrix3x2fStack pose = g.pose();
    pose.pushMatrix();
    pose.translate(x, y);
    pose.scale(k, k);
    g.drawString(font, label.sequence, 0, 0, argb, textShadow);
    pose.popMatrix();
  }

  /** Text that fits stands still. Longer text waits, then rolls by slowly, its start following its end around. */
  private static void rolling(GuiGraphics g, Label label, float x, float y, float room, float k, int rgb) {
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
