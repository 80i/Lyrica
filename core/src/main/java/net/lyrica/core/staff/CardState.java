package net.lyrica.core.staff;

import java.util.Objects;
import net.labymod.api.client.component.Component;
import net.labymod.api.client.gfx.pipeline.renderer.text.FontRenderer;
import net.labymod.api.util.I18n;
import net.lyrica.api.LyricsBridge;

/**
 * One player's song card from frame to frame: what it says, and how far each of its fades and movements has come. Text
 * is built and measured only when it changes; all the rest of the time this is a little arithmetic.
 */
final class CardState {

  /** How much the card shows, going by how many screen pixels its text would get. */
  enum Detail { FULL, LINE }

  // Sizes in text pixels, with the font's capitals at 7 of them
  static final float CAP_HEIGHT = 7.0F;
  static final float TIME_SIZE = 0.78F;
  static final float ARTIST_SIZE = 0.86F;
  static final float PADDING_X = 7.0F;
  static final float PADDING_Y = 5.5F;
  static final float ARTIST_GAP = 3.5F;
  static final float LABEL_GAP = 3.5F;
  static final float WAVE_GAP = 4.0F;
  static final float WAVE_HEIGHT = 6.5F;
  static final float LINE_PADDING_Y = 4.0F;
  /** Between the title and the time on the top line, at least. */
  private static final float BESIDE_TIME = 8.0F;
  private static final float TIME_ROOM = 18.0F;
  /** What the pause mark takes before the word. */
  static final float PAUSE_MARK_ROOM = 6.0F;
  private static final float MOST_LINE_WIDTH = 118.0F;
  private static final float LEAST_SONG_WIDTH = 84.0F;
  /** How wide a title may make a card for a player who wants to see all of it. */
  private static final float MOST_FULL_TITLE_WIDTH = 1000.0F;
  private static final String TITLE_SHORTENED = "CUT";
  private static final String TITLE_WHOLE = "FULL";

  // The full card at half text scale close up. Further off it grows just enough to keep this many screen pixels per
  // text pixel: below that the font's strokes fall between pixels and turn blotchy
  private static final float FULL_SCALE = 0.5F;
  private static final float FULL_HOLD_PIXELS = 1.6F;
  private static final float FULL_MOST_SCALE = 0.8F;
  // Then the title alone on one line in bold, whose strokes hold out longer
  private static final float LINE_HOLD_PIXELS = 1.15F;
  private static final float LINE_LEAST_SCALE = 0.5F;
  private static final float LINE_MOST_SCALE = 1.3F;
  // In screen pixels per text pixel at text scale one. Two limits each, so nothing flickers at a limit
  private static final float LINE_BELOW = FULL_HOLD_PIXELS / FULL_MOST_SCALE;
  private static final float FULL_ABOVE = LINE_BELOW * 1.12F;
  private static final float GONE_BELOW = 0.5F;
  private static final float BACK_ABOVE = 0.55F;

  // Hiding: the words go first with the panel still solid, then the panel folds down toward the name, so that a dark
  // panel is never half see-through against a bright sky
  private static final float REVEAL_S = 0.24F;
  private static final float WORDS_FROM = 0.5F;
  // It has gone before it could be a thin strip across the sky
  private static final float GONE_BEFORE = 0.1F;
  private static final float SOLID_FROM = 0.2F;

  private static final float CONTENT_OUT_S = 0.12F;
  private static final float CONTENT_IN_S = 0.20F;
  private static final float WAVE_SETTLE_S = 0.35F;
  private static final float ACCENT_CHANGE_S = 0.35F;
  private static final float LONGEST_STEP_S = 0.1F;
  private static final float SIZE_SPEED = 16.0F;
  private static final float PROGRESS_SPEED = 10.0F;

  /** A song's wave has this many columns; ticks across the card read them evenly. */
  static final int WAVE_COLUMNS = 64;
  private static final float WAVE_PAUSED = 0.16F;
  private static final float WAVE_LOWEST = 0.22F;

  private static final String PAUSED_KEY = "lyrical.card.paused";

  // The plain card's colours, which a card's style can only move away from
  private static final int TITLE_RGB = 0xF4F5F7;
  private static final int SOFT_RGB = 0xA6ABB5;
  private static final int WAVE_EMPTY_RGB = 0x454A54;
  private static final int RIM_RGB = 0x3D424C;
  private static final int TITLE_ON_LIGHT_RGB = 0x14161A;
  private static final int SOFT_ON_LIGHT_RGB = 0x4A4F59;
  private static final int WAVE_EMPTY_ON_LIGHT_RGB = 0xB4B8C0;
  // A panel this bright and this solid carries dark words
  private static final int LIGHT_PANEL = 150;
  private static final float LIGHT_PANEL_SOLID = 0.6F;
  // Below this the panel no longer carries the words by itself: they get a shadow, and the grey ones lift toward white
  private static final float WORDS_ALONE_BELOW = 0.8F;
  private static final int WHITE_RGB = 0xFFFFFF;
  private static final float RIM_LIFT = 0.2F;
  private static final int BRIGHTEST_ON_LIGHT = 120;

  /** How the card looks, with the colours that follow from it below. */
  LyricsBridge.CardLook look = LyricsBridge.CardLook.PLAIN;
  int titleRgb = TITLE_RGB;
  int softRgb = SOFT_RGB;
  int waveEmptyRgb = WAVE_EMPTY_RGB;
  int rimRgb = RIM_RGB;
  /** The wave and the word Paused right now: what the viewer chose, moving smoothly when that changes. */
  int accentRgb;
  /** The words have no panel to stand on, so they carry their own shadow. */
  boolean wordsShadow;
  /** 0 hidden and folded, 1 fully there. */
  float reveal;
  /** Everything on the card: one fade, so it always changes together. */
  float contentAlpha = 1.0F;
  float playing;
  float paused;
  float progress;
  float width;
  float height;
  float textLeft;
  float scale = FULL_SCALE;
  /** How high each column of the wave stands right now, 0 to 1. */
  final float[] wave = new float[WAVE_COLUMNS];
  /** Camera to the tag in screen pixels per text pixel at text scale one, as last frame's drawing found it. */
  float basePixels = -1.0F;
  Face shown;
  Component time;
  float timeWidth;
  long lastSeenMs;

  private final float[] waveShape = new float[WAVE_COLUMNS];
  private Detail detail = Detail.FULL;
  private boolean tooFar;
  private Face pending;
  private String keyLabel;
  private String keySongTitle;
  private String keySongArtist;
  private String keyTitleMode;
  private LyricsBridge.LocalSong.Cover keyCover;
  private boolean keyProgress;
  private boolean keyHasTime;
  private boolean keyPaused;
  private int keyColor;
  private Detail keyDetail;
  private boolean keySet;
  private boolean keyCoverOn;
  private boolean keyWaveOn;
  private boolean keyTimeOn;
  private boolean lightPanel;
  private boolean keyLightPanel;
  private boolean accentSet;
  private int accentFrom;
  private int accentTo;
  private float accentAt;
  private long keyTimeSeconds = -1L;
  private long lastStepMs;

  /**
   * What a card says.
   *
   * @param label      a team member's role in small words over the title, for those who keep it here; or null
   * @param first      the song's title, cut to fit
   * @param second     the song's artist, or null
   * @param labelColor the role's colour, which only the label takes
   * @param song       whether this is the full card: with the wave and the time if the look has them
   * @param paused     the top line's right end says so, in the place of the time
   * @param word       the words for that, measured in {@code wordWidth}
   */
  record Face(Words label, Words first, Words second, int labelColor, boolean song, boolean hasProgress,
      LyricsBridge.LocalSong.Cover cover, boolean wave, boolean showTime, boolean paused, Component word,
      float wordWidth, Detail detail) {

    /** Part of it is standing in for a picture that is still being made. */
    boolean waiting() {
      return this.first.waiting || this.label != null && this.label.waiting
          || this.second != null && this.second.waiting;
    }
  }

  /** @param role the role to carry on the card, or null */
  void step(long nowMs, int accentRgb, LyricsBridge.StaffRole role, LyricsBridge.CardLook wanted,
      LyricsBridge.LocalSong song, boolean discreet, FontRenderer font, LyricsBridge bridge) {
    float seconds = this.tick(nowMs);
    this.followDistance();
    this.followLook(wanted);
    this.followAccent(seconds, accentRgb);
    this.followText(role == null ? 0 : role.colorHex(), role, song, font, bridge);
    this.followContent(seconds, song, nowMs);
    this.followSize(seconds);
    this.reveal = toward(this.reveal, discreet || this.tooFar ? 0.0F : 1.0F, seconds / REVEAL_S);

    boolean songShown = this.pending == null;
    boolean playingNow = songShown && song.playing();
    this.playing = toward(this.playing, playingNow ? 1.0F : 0.0F, seconds / WAVE_SETTLE_S);
    this.paused = toward(this.paused, songShown && !playingNow ? 1.0F : 0.0F, seconds / WAVE_SETTLE_S);
    if (this.shown.wave) {
      this.followWave(nowMs);
    }

    if (songShown && song.hasProgress()) {
      this.progress = ease(this.progress, song.progressAt(nowMs), seconds, PROGRESS_SPEED);
    }

    if (songShown && song.hasTime() && this.look.time() && !this.shown.paused) {
      this.followTime(song, nowMs, font);
    }
  }

  /** Hides with nothing new to say, for a card that no longer has a song to show. */
  void fold(long nowMs) {
    this.reveal = toward(this.reveal, 0.0F, this.tick(nowMs) / REVEAL_S);
  }

  private float tick(long nowMs) {
    float seconds = this.lastStepMs == 0L ? 0.0F
        : Math.max(0.0F, Math.min(LONGEST_STEP_S, (nowMs - this.lastStepMs) / 1000.0F));
    this.lastStepMs = nowMs;
    this.lastSeenMs = nowMs;
    return seconds;
  }

  /** The words' share of the card's reveal. */
  float wordsAlpha() {
    return clamp((this.reveal - WORDS_FROM) / (1.0F - WORDS_FROM));
  }

  /** How solid the panel is while it folds. */
  float panelAlpha() {
    return clamp((this.reveal - GONE_BEFORE) / (SOLID_FROM - GONE_BEFORE));
  }

  /** How unfolded the panel is, 0 to 1. */
  float unfolded() {
    float at = clamp(this.reveal / WORDS_FROM);
    return at * at * (3.0F - 2.0F * at);
  }

  private void followDistance() {
    if (this.basePixels < 0.0F) {
      return;
    }

    boolean full = this.basePixels >= (this.detail == Detail.FULL ? LINE_BELOW : FULL_ABOVE);
    this.detail = full ? Detail.FULL : Detail.LINE;
    this.tooFar = this.basePixels < (this.tooFar ? BACK_ABOVE : GONE_BELOW);
  }

  /** The colours that go with a look. Only when it changes, which is rarely. */
  private void followLook(LyricsBridge.CardLook wanted) {
    if (wanted == this.look) {
      return;
    }

    this.look = wanted;
    float opacity = wanted.panelOpacity();
    this.lightPanel = brightness(wanted.panelRgb()) >= LIGHT_PANEL && opacity >= LIGHT_PANEL_SOLID;
    this.wordsShadow = !this.lightPanel && opacity < WORDS_ALONE_BELOW;
    float bare = clamp(1.0F - opacity / WORDS_ALONE_BELOW);
    this.titleRgb = this.lightPanel ? TITLE_ON_LIGHT_RGB : TITLE_RGB;
    this.softRgb = this.lightPanel ? SOFT_ON_LIGHT_RGB : blend(SOFT_RGB, TITLE_RGB, bare);
    this.waveEmptyRgb = this.lightPanel ? WAVE_EMPTY_ON_LIGHT_RGB : blend(WAVE_EMPTY_RGB, SOFT_RGB, bare);
    if (!wanted.rim()) {
      this.rimRgb = wanted.panelRgb();
    } else if (wanted.panelRgb() == LyricsBridge.CardLook.PLAIN.panelRgb()) {
      this.rimRgb = RIM_RGB;
    } else {
      this.rimRgb = blend(wanted.panelRgb(), this.lightPanel ? 0 : WHITE_RGB, RIM_LIFT);
    }
  }

  /** A cover's colours can arrive a moment after its song, so a new colour moves there rather than jumping. */
  private void followAccent(float seconds, int wanted) {
    int target = this.lightPanel ? readableOnLight(wanted) : readable(wanted);
    if (!this.accentSet) {
      this.accentSet = true;
      this.accentRgb = target;
      this.accentFrom = target;
      this.accentTo = target;
      this.accentAt = 1.0F;
      return;
    }

    if (target != this.accentTo) {
      this.accentFrom = this.accentRgb;
      this.accentTo = target;
      this.accentAt = 0.0F;
    }

    if (this.accentAt < 1.0F) {
      this.accentAt = toward(this.accentAt, 1.0F, seconds / ACCENT_CHANGE_S);
      this.accentRgb = blend(this.accentFrom, this.accentTo, this.accentAt);
    }
  }

  private void followText(int labelRgb, LyricsBridge.StaffRole role, LyricsBridge.LocalSong song, FontRenderer font,
      LyricsBridge bridge) {
    boolean progress = song.hasProgress();
    boolean hasTime = song.hasTime();
    boolean paused = !song.playing();
    String label = role == null ? null : LyricsBridge.StaffRole.shown(role.label());
    String titleMode = bridge.songCardTitle();
    boolean same = this.keySet && this.detail == this.keyDetail && labelRgb == this.keyColor
        && progress == this.keyProgress && hasTime == this.keyHasTime && paused == this.keyPaused && Objects.equals(song.cover(), this.keyCover)
        && song.title().equals(this.keySongTitle) && song.artist().equals(this.keySongArtist)
        && titleMode.equals(this.keyTitleMode)
        && this.look.cover() == this.keyCoverOn && this.look.wave() == this.keyWaveOn
        && this.look.time() == this.keyTimeOn && this.lightPanel == this.keyLightPanel
        && Objects.equals(label, this.keyLabel);
    if (same) {
      // Pictures of words arrive a little later, and the card takes them when they do
      Face latest = this.pending != null ? this.pending : this.shown;
      if (latest != null && latest.waiting()) {
        Face again = this.face(labelRgb, label, song, paused, font, bridge);
        if (!again.waiting()) {
          this.pending = again;
        }
      }

      return;
    }

    this.keySet = true;
    this.keyLabel = label;
    this.keyDetail = this.detail;
    this.keyColor = labelRgb;
    this.keySongTitle = song.title();
    this.keySongArtist = song.artist();
    this.keyTitleMode = titleMode;
    this.keyCover = song.cover();
    this.keyProgress = progress;
    this.keyHasTime = hasTime;
    this.keyPaused = paused;
    this.keyCoverOn = this.look.cover();
    this.keyWaveOn = this.look.wave();
    this.keyTimeOn = this.look.time();
    this.keyLightPanel = this.lightPanel;
    this.pending = this.face(labelRgb, label, song, paused, font, bridge);
  }

  private void followContent(float seconds, LyricsBridge.LocalSong song, long nowMs) {
    if (this.shown == null) {
      this.show(song, nowMs);
      this.width = this.wantedWidth();
      this.height = this.wantedHeight();
      this.textLeft = this.wantedTextLeft();
      this.scale = this.wantedScale();
      return;
    }

    if (this.pending == null) {
      this.contentAlpha = toward(this.contentAlpha, 1.0F, seconds / CONTENT_IN_S);
      return;
    }

    this.contentAlpha = toward(this.contentAlpha, 0.0F, seconds / CONTENT_OUT_S);
    if (this.contentAlpha <= 0.0F) {
      this.show(song, nowMs);
    }
  }

  /** The new face goes up with the wave of its song, whose line begins where that song is. */
  private void show(LyricsBridge.LocalSong song, long nowMs) {
    this.shown = this.pending;
    this.pending = null;
    shapeWave(this.waveShape, song.title(), song.artist());
    if (song.hasProgress()) {
      this.progress = song.progressAt(nowMs);
    }
  }

  private void followSize(float seconds) {
    this.width = ease(this.width, this.wantedWidth(), seconds, SIZE_SPEED);
    this.height = ease(this.height, this.wantedHeight(), seconds, SIZE_SPEED);
    this.textLeft = ease(this.textLeft, this.wantedTextLeft(), seconds, SIZE_SPEED);
    this.scale = ease(this.scale, this.wantedScale(), seconds, SIZE_SPEED);
  }

  /** Once a second at the most. */
  private void followTime(LyricsBridge.LocalSong song, long nowMs, FontRenderer font) {
    long at = song.positionAt(nowMs) / 1000L;
    if (at == this.keyTimeSeconds) {
      return;
    }

    this.keyTimeSeconds = at;
    long seconds = at % 60L;
    String text = at / 60L + (seconds < 10L ? ":0" : ":") + seconds;
    this.time = Component.text(text);
    this.timeWidth = font.getWidth(text);
  }

  /**
   * Each song stands on its own wave, made from its name, so that it looks the same whenever it comes around. While it
   * plays a ripple runs through the wave; a pause lays it down. It is a drawing and does not hear the music.
   */
  private void followWave(long nowMs) {
    if (this.playing <= 0.0F && this.paused >= 1.0F) {
      if (this.wave[0] != WAVE_PAUSED) {
        for (int i = 0; i < WAVE_COLUMNS; i++) {
          this.wave[i] = WAVE_PAUSED;
        }
      }

      return;
    }

    double seconds = (nowMs % 3_600_000L) / 1000.0;
    for (int i = 0; i < WAVE_COLUMNS; i++) {
      float ripple = (float) (0.82 + 0.18 * Math.sin(seconds * 3.4 - i * 0.37));
      float height = this.waveShape[i] * (ripple + (1.0F - ripple) * (1.0F - this.playing));
      this.wave[i] = height + (WAVE_PAUSED - height) * this.paused;
    }
  }

  private static void shapeWave(float[] into, String title, String artist) {
    int seed = title.hashCode() * 31 + artist.hashCode();
    float last = 0.5F;
    for (int i = 0; i < into.length; i++) {
      seed = seed * 1_103_515_245 + 12_345;
      float noise = (seed >>> 16 & 0x7FFF) / 32767.0F;
      // Each column leans on the last so the wave has phrases, not static
      last = last * 0.4F + noise * 0.6F;
      into[i] = WAVE_LOWEST + (1.0F - WAVE_LOWEST) * clamp((last - 0.25F) / 0.5F);
    }
  }

  private float wantedScale() {
    // A bigger or smaller card close up. Further off it still holds the pixels its text needs, whatever its size
    float size = this.look.size();
    float grown = Math.max(1.0F, size);
    if (this.basePixels <= 0.0F) {
      return (this.shown.detail == Detail.FULL ? FULL_SCALE : LINE_LEAST_SCALE) * size;
    }

    return this.shown.detail == Detail.FULL
        ? Math.max(FULL_SCALE * size, Math.min(FULL_MOST_SCALE * grown, FULL_HOLD_PIXELS / this.basePixels))
        : Math.max(LINE_LEAST_SCALE * size, Math.min(LINE_MOST_SCALE * grown, LINE_HOLD_PIXELS / this.basePixels));
  }

  private float wantedHeight() {
    if (this.shown.detail == Detail.LINE) {
      return LINE_PADDING_Y * 2.0F + CAP_HEIGHT;
    }

    float high = PADDING_Y * 2.0F + CAP_HEIGHT;
    if (this.shown.label != null) {
      high += CAP_HEIGHT * TIME_SIZE + LABEL_GAP;
    }

    if (this.shown.second != null) {
      high += ARTIST_GAP + CAP_HEIGHT * ARTIST_SIZE;
    }

    if (this.shown.wave) {
      high += WAVE_GAP + WAVE_HEIGHT;
    }

    return high;
  }

  private float wantedTextLeft() {
    float cover = this.shown.cover == null ? 0.0F : this.wantedHeight();
    return cover + PADDING_X;
  }

  private float wantedWidth() {
    Face face = this.shown;
    float second = face.second == null ? 0.0F : face.second.width;
    float text;
    if (!face.song) {
      text = face.first.width;
    } else if (face.label != null) {
      text = Math.max(LEAST_SONG_WIDTH, Math.max(second, Math.max(face.first.width, face.label.width + topRightRoom(face))));
    } else {
      text = Math.max(LEAST_SONG_WIDTH, Math.max(second, face.first.width + topRightRoom(face)));
    }

    return this.wantedTextLeft() + text + PADDING_X;
  }

  private static float topRightRoom(Face face) {
    if (face.paused) {
      return BESIDE_TIME + PAUSE_MARK_ROOM + face.wordWidth;
    }

    return face.showTime ? BESIDE_TIME + TIME_ROOM : 0.0F;
  }

  private Face face(int labelRgb, String label, LyricsBridge.LocalSong song, boolean paused, FontRenderer font,
      LyricsBridge bridge) {
    int color = this.lightPanel ? readableOnLight(labelRgb) : readable(labelRgb);
    Detail detail = this.detail;
    String title = song.title().isEmpty() ? song.artist() : song.title();
    String under = song.title().isEmpty() ? "" : song.artist();
    LyricsBridge.LocalSong.Cover cover = this.look.cover() ? song.cover() : null;
    if (detail == Detail.LINE) {
      return new Face(null, Words.of(title, true, MOST_LINE_WIDTH, 1.0F, font, bridge), null, color, false, false, cover,
          false, false, paused, null, 0.0F, detail);
    }

    Component word = null;
    float wordWidth = 0.0F;
    if (paused) {
      String words = I18n.translate(PAUSED_KEY);
      word = Component.text(words);
      wordWidth = font.getWidth(words) * TIME_SIZE;
    }

    boolean showTime = !paused && song.hasTime() && this.look.time();
    float room = paused ? BESIDE_TIME + PAUSE_MARK_ROOM + wordWidth : showTime ? BESIDE_TIME + TIME_ROOM : 0.0F;
    // The time stands on the top line, whichever line that is
    Words labelWords = label == null ? null : Words.of(label, true, MOST_LINE_WIDTH - room, TIME_SIZE, font, bridge);
    float titleRoom = label == null ? MOST_LINE_WIDTH - room : MOST_LINE_WIDTH;
    String titleMode = bridge.songCardTitle();
    Words first;
    if (TITLE_SHORTENED.equals(titleMode)) {
      first = Words.of(title, true, titleRoom, 1.0F, font, bridge);
    } else if (TITLE_WHOLE.equals(titleMode)) {
      first = Words.of(title, true, MOST_FULL_TITLE_WIDTH, 1.0F, font, bridge);
    } else {
      first = Words.sliding(title, titleRoom, font, bridge);
    }

    Words second = under.isEmpty() ? null : Words.of(under, false, MOST_LINE_WIDTH, ARTIST_SIZE, font, bridge);
    return new Face(labelWords, first, second, color, true, song.hasProgress(), cover, this.look.wave(), showTime, paused,
        word, wordWidth, detail);
  }

  /** A colour, lifted toward white if it is too dark to read on the dark card. */
  static int readable(int rgb) {
    int red = rgb >> 16 & 0xFF;
    int green = rgb >> 8 & 0xFF;
    int blue = rgb & 0xFF;
    int brightness = brightness(rgb);
    if (brightness >= 110) {
      return rgb & 0xFFFFFF;
    }

    float lift = (110 - brightness) / 255.0F + 0.1F;
    return mix(red, lift) << 16 | mix(green, lift) << 8 | mix(blue, lift);
  }

  /** A colour, pressed down if it is too bright to read on a light card. */
  static int readableOnLight(int rgb) {
    int brightness = brightness(rgb);
    return brightness <= BRIGHTEST_ON_LIGHT ? rgb & 0xFFFFFF
        : blend(rgb, 0, 1.0F - BRIGHTEST_ON_LIGHT / (float) brightness);
  }

  static int brightness(int rgb) {
    return ((rgb >> 16 & 0xFF) * 3 + (rgb >> 8 & 0xFF) * 6 + (rgb & 0xFF)) / 10;
  }

  static int blend(int from, int to, float amount) {
    int red = Math.round((from >> 16 & 0xFF) + ((to >> 16 & 0xFF) - (from >> 16 & 0xFF)) * amount);
    int green = Math.round((from >> 8 & 0xFF) + ((to >> 8 & 0xFF) - (from >> 8 & 0xFF)) * amount);
    int blue = Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * amount);
    return red << 16 | green << 8 | blue;
  }

  private static int mix(int channel, float lift) {
    return Math.min(255, Math.round(channel + (255 - channel) * lift));
  }

  private static float clamp(float value) {
    return value < 0.0F ? 0.0F : Math.min(value, 1.0F);
  }

  private static float toward(float value, float target, float step) {
    return value < target ? Math.min(target, value + step) : Math.max(target, value - step);
  }

  private static float ease(float value, float target, float seconds, float speed) {
    float moved = value + (target - value) * (1.0F - (float) Math.exp(-seconds * speed));
    return Math.abs(target - moved) < 0.01F ? target : moved;
  }
}
