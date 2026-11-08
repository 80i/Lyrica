package net.lyrica.v26_3.stage;

import java.text.Bidi;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.lyrica.v26_3.look.Look;
import net.lyrica.v26_3.look.Palette;
import net.lyrica.v26_3.words.SungParts;

/**
 * Turns text and time into letters, with no drawing. Keep one per user: it holds the layout, which is only worked out
 * again when the text or something that shapes it changes, and it returns the same {@link ComposedLine} every time.
 */
public final class LineComposer {

  private static final float ROW_AIR = 1.0F;
  private static final float BIG_FIRST_WORD = 1.55F;
  private static final float SMALL_REST = 0.8F;
  private static final float STAIR_RISE = 0.62F;
  private static final float STAIR_DEPTH = 7.0F;
  private static final float ARC_WIDEST_SWEEP = 1.9F;
  private static final float ARC_LEAST_RADIUS = 0.55F;
  private static final float STACK_PAIR_SHARE = 0.62F;
  private static final float STACK_LEAST_WIDTH = 70.0F;
  private static final float STACK_LEAST_SCALE = 0.8F;
  private static final float STACK_MOST_SCALE = 1.9F;

  // The characters GLITCH shows for a moment: plain ones that every bundled font has.
  private static final String[] GLITCH_GLYPHS = {"#", "%", "&", "/", "?", "0", "1", "X", "Z", "$", "=", "+"};

  private static final float SOFT_EDGE_MS = 70.0F;
  private static final float BAND_MS = 160.0F;
  // A word lights up as it starts and goes down after it ends, rather than at once.
  private static final float WORD_RISE_MS = 90.0F;
  private static final float WORD_FALL_MS = 280.0F;
  // A word held longer than this starts to swell, and has swollen in full this much later.
  private static final float HELD_AFTER_MS = 450.0F;
  private static final float HELD_FULL_MS = 1_200.0F;
  private static final float HELD_GROWTH = 0.16F;
  // A letter sung longer than this is part of a held word, and then it is the word that shows, not the letter.
  private static final float LETTER_SHOWS_MS = 300.0F;
  private static final float WORD_BRIGHT = 0.5F;
  private static final float WORD_LIFT = 0.32F;
  private static final float SECOND_VOICE_TINT = 0.65F;
  // WIPE: how many letters the soft edge of the fill is spread over, and how bright the edge itself runs.
  private static final float WIPE_EDGE_LETTERS = 1.6F;
  private static final float WIPE_EDGE_BRIGHT = 0.55F;
  // SPRING: how fast the letter springs up and how quickly it settles; under 1 it overshoots a little.
  private static final float SPRING_HZ = 3.2F;
  private static final float SPRING_DAMPING = 0.42F;
  // A syllable held this long has its letters swell one after another, as in a long sung note.
  private static final float LONG_SYLLABLE_MS = 900.0F;
  // GLOW: how long a letter keeps its light after it was sung.
  private static final float GLOW_FADE_MS = 520.0F;

  private final ComposedLine line = new ComposedLine();
  private final List<Letter> pool = new ArrayList<>();
  private final boolean outlines;

  private int rowsPlaced;
  private int voice;

  private boolean partsBound;
  private SungParts boundParts;

  private boolean laidOut;
  private String laidText;
  private Font laidFont;
  private Style laidStyle;
  private float laidSize;
  private String laidArrange;
  private float laidSpacing;
  private float laidGap;
  private int laidWrap;
  private boolean laidUpper;

  /** Which singer of a duet the line belongs to, 0 when that is nothing to tell apart. */
  public void voice(int voice) {
    this.voice = voice;
  }

  /** For what is drawn with the font's own texture: the menu's tiles and the flat lyrics. */
  public LineComposer() {
    this(false);
  }

  /** @param outlines lay letters out by their real outlines, for the world, where those are what gets drawn */
  LineComposer(boolean outlines) {
    this.outlines = outlines;
  }

  /**
   * The line as it stands at this moment.
   *
   * @param elapsedMs          time since the line began; past {@code windowMs} its exit plays, and when that is over
   *                           {@link ComposedLine#finished} is true. Before 0 nothing shows.
   * @param windowMs           how long the line is sung
   * @param parts              when the letters are sung, or null to spread them evenly over the window
   * @param nowMs              a running clock for idle motion and colour flow
   * @param seed               differs per line, so no two lines scatter the same way
   * @param colors             the album's two colours, or null
   */
  public ComposedLine compose(Look look, Font font, Style style, String text, long elapsedMs, long windowMs,
      SungParts parts, long nowMs, long seed, int[] colors) {
    String source = text == null ? "" : text;
    if (!this.isLaidOut(look, font, style, source)) {
      this.layOut(look, font, style, source);
    }

    if (!this.partsBound || parts != this.boundParts) {
      this.bindParts(parts, source);
    }

    ComposedLine composed = this.line;
    List<Letter> letters = composed.letters;
    int count = letters.size();
    for (int i = 0; i < count; i++) {
      letters.get(i).backHome();
    }

    Motions.apply(look, composed, elapsedMs, windowMs, nowMs, seed);

    float flow = (float) ((nowMs % 3_600_000L) * 0.00022 * look.flowSpeed);
    float shown = 0.0F;
    for (int i = 0; i < count; i++) {
      Letter l = letters.get(i);
      int rgb = baseColor(look, l, colors, flow, nowMs, seed);
      // The even-numbered singer of a duet, where the look has just one colour to tell them apart with.
      if (this.voice % 2 == 0 && this.voice > 0 && "SOLID".equals(look.colorMode)) {
        rgb = Palette.mix(rgb, look.color2, SECOND_VOICE_TINT);
      }

      if (look.karaoke) {
        rgb = sing(look, l, composed, rgb, elapsedMs, windowMs);
      }

      float alpha = Motions.clamp01(l.alpha);
      shown += alpha;
      int alphaByte = Math.round(alpha * 255.0F);
      // Nearly clear text is not worth drawing, and the eye cannot tell it from gone.
      l.argb = alphaByte < 4 ? 0 : alphaByte << 24 | (Palette.dim(rgb, l.brightness) & 0xFFFFFF);
    }

    composed.presence = count == 0 ? 0.0F : shown / count;
    return composed;
  }

  // Colour and karaoke

  private static int baseColor(Look look, Letter l, int[] colors, float flow, long nowMs, long seed) {
    return switch (look.colorMode) {
      case "GRADIENT" -> Palette.mix(look.color, look.color2, gradientAt(l, flow));
      case "RAINBOW" -> Palette.rgbAt(l.index, nowMs, look.flowSpeed);
      case "ALBUM" -> colors != null && colors.length > 0 ? colors[0] : look.color;
      case "ALBUM_GRADIENT" -> colors != null && colors.length > 1
          ? Palette.mix(colors[0], colors[1], gradientAt(l, flow))
          : Palette.mix(look.color, look.color2, gradientAt(l, flow));
      case "PER_LINE" -> Palette.hsv(Motions.noise(seed, 7, 0), 0.62F, 1.0F);
      default -> look.color;
    };
  }

  /** Runs there and back rather than around, so the colours flow without a seam. */
  private static float gradientAt(Letter l, float flow) {
    float position = (l.spanX * 0.8F + l.spanY * 0.2F) * 0.5F + flow;
    float part = position - (float) Math.floor(position);
    return 1.0F - Math.abs(1.0F - 2.0F * part);
  }

  private static int sing(Look look, Letter l, ComposedLine line, int rgb, long elapsedMs, long windowMs) {
    boolean timed = l.timed;
    float start = timed ? l.singFrom : windowMs * l.linePosition;
    float length = timed ? l.singFor : windowMs / (float) Math.max(1, line.letters.size());
    float since = elapsedMs - start;
    float sung = elapsedMs >= windowMs ? 1.0F : Motions.smooth(since / Math.max(SOFT_EDGE_MS, length));
    float across = (since - length * 0.5F) / Math.max(BAND_MS, length);
    float beingSung = Math.abs(across) >= 1.0F ? 0.0F : (1.0F - across * across) * (1.0F - across * across);
    if (timed && length > LETTER_SHOWS_MS) {
      beingSung *= LETTER_SHOWS_MS / length;
    }

    float word = timed ? wordBeingSung(l, elapsedMs) : 0.0F;
    l.karaokeGlow = 0.0F;
    return switch (look.karaokeStyle) {
      // The whole line lit while it is sung, for lyrics that only time lines.
      case "LINE" -> {
        float lit = elapsedMs < 0L ? 0.0F
            : Motions.smooth(elapsedMs / 180.0F) * (1.0F - Motions.smooth((elapsedMs - windowMs) / 300.0F));
        yield Palette.dim(rgb, 0.32F + 0.68F * lit);
      }
      // A soft edge running through the line, spread over its neighbours so the fill never steps letter by letter.
      case "WIPE" -> {
        float edge = Math.max(SOFT_EDGE_MS, length * WIPE_EDGE_LETTERS);
        float cover = elapsedMs >= windowMs ? 1.0F : Motions.clamp01((since + edge * 0.5F) / (length + edge));
        float lead = cover <= 0.0F || cover >= 1.0F ? 0.0F : 1.0F - Math.abs(2.0F * cover - 1.0F);
        int base = Palette.mix(Palette.dim(rgb, 0.38F), rgb, cover * cover * (3.0F - 2.0F * cover));
        yield Palette.mix(base, 0xFFFFFF, lead * WIPE_EDGE_BRIGHT);
      }
      // Each letter springs up when it is sung and settles back with a small bounce; a long syllable swells.
      case "SPRING" -> {
        float h = line.glyphHeight * l.homeScale;
        float bounce = spring(since) * (1.0F - spring(since - Math.max(length, 220.0F)) * 0.85F);
        float syllable = timed ? l.wordTo - l.wordFrom : length;
        float swell = syllable >= LONG_SYLLABLE_MS ? beingSung : 0.0F;
        l.y -= h * (0.16F * bounce + 0.08F * swell);
        l.z += h * (0.35F * bounce + 0.3F * swell);
        l.scale *= 1.0F + 0.08F * bounce + 0.22F * swell;
        yield Palette.dim(rgb, 0.45F + 0.55F * Math.max(sung, word));
      }
      // The letter being sung lights up and leaves a glow fading behind it.
      case "GLOW" -> {
        float trail = since < 0.0F ? 0.0F
            : since < length ? 1.0F : 1.0F - Motions.smooth((since - length) / GLOW_FADE_MS);
        l.karaokeGlow = Math.max(trail, word * 0.35F);
        yield Palette.mix(Palette.dim(rgb, 0.4F + 0.6F * sung), 0xFFFFFF, trail * 0.35F);
      }
      case "SWEEP" -> Palette.mix(Palette.dim(rgb, 0.8F), 0xFFFFFF, Math.max(beingSung * 0.85F, word * WORD_BRIGHT));
      case "FILL" -> Palette.mix(rgb, look.color2, sung);
      case "LIFT" -> {
        float h = line.glyphHeight * l.homeScale;
        float held = word * Motions.smooth((elapsedMs - l.wordFrom - HELD_AFTER_MS) / HELD_FULL_MS);
        l.z += h * (0.6F * beingSung + WORD_LIFT * word);
        l.y -= h * (0.12F * beingSung + 0.06F * word);
        l.scale *= (1.0F + 0.2F * beingSung) * (1.0F + HELD_GROWTH * held);
        yield rgb;
      }
      default -> Palette.dim(rgb, 0.32F + 0.68F * sung);
    };
  }

  /**
   * How far a spring released at time 0 has gone towards 1, with no state kept between frames: the closed form of a
   * damped spring, so it overshoots a little and settles. 0 before it starts.
   */
  private static float spring(float ms) {
    if (ms <= 0.0F) {
      return 0.0F;
    }

    float t = ms / 1000.0F;
    float omega = (float) (2.0 * Math.PI * SPRING_HZ);
    float damped = omega * (float) Math.sqrt(1.0 - SPRING_DAMPING * SPRING_DAMPING);
    float decay = (float) Math.exp(-SPRING_DAMPING * omega * t);
    return 1.0F - decay * ((float) Math.cos(damped * t)
        + SPRING_DAMPING * omega / damped * (float) Math.sin(damped * t));
  }

  /** 1 from when the word starts until it has been sung, with a short way up and a longer way down. */
  private static float wordBeingSung(Letter l, long elapsedMs) {
    if (elapsedMs < l.wordFrom) {
      return 0.0F;
    }

    return elapsedMs < l.wordTo
        ? Motions.smooth((elapsedMs - l.wordFrom) / WORD_RISE_MS)
        : 1.0F - Motions.smooth((elapsedMs - l.wordTo) / WORD_FALL_MS);
  }

  /**
   * Works out once per line when each letter is sung. It goes by the chars of the text as written, so that capitals or
   * joined letters, which can change how many letters a word has, still begin and end with their word.
   */
  private void bindParts(SungParts parts, String text) {
    this.partsBound = true;
    this.boundParts = parts;
    List<Letter> letters = this.line.letters;
    if (parts == null) {
      for (int i = 0; i < letters.size(); i++) {
        letters.get(i).timed = false;
      }

      return;
    }

    // One more than there are words: the chars before each, then all of them.
    int[] charsBefore = new int[this.line.wordCount + 1];
    int word = -1;
    int chars = 0;
    boolean inWord = false;
    for (int i = 0; i < text.length(); i++) {
      if (Character.isWhitespace(text.charAt(i))) {
        inWord = false;
        continue;
      }

      if (!inWord && ++word < charsBefore.length) {
        charsBefore[word] = chars;
      }

      inWord = true;
      chars++;
    }

    for (int i = Math.max(0, word + 1); i < charsBefore.length; i++) {
      charsBefore[i] = chars;
    }

    for (int i = 0; i < letters.size(); i++) {
      Letter l = letters.get(i);
      l.timed = !l.wholeRow && l.word + 1 < charsBefore.length;
      if (l.timed) {
        int first = charsBefore[l.word];
        int wordChars = charsBefore[l.word + 1] - first;
        float from = first + wordChars * l.letterInWord / (float) l.wordLetters;
        float to = first + wordChars * (l.letterInWord + 1) / (float) l.wordLetters;
        l.singFrom = parts.startAt(from);
        l.singFor = Math.max(1.0F, parts.endAt(to) - l.singFrom);
        l.wordFrom = parts.startAt(first);
        l.wordTo = parts.endAt(first + wordChars);
      }
    }
  }

  // Layout

  private boolean isLaidOut(Look look, Font font, Style style, String text) {
    return this.laidOut
        && this.laidFont == font
        && this.laidSize == look.size
        && this.laidSpacing == look.letterSpacing
        && this.laidGap == look.lineGap
        && this.laidWrap == look.wrapWidth
        && this.laidUpper == look.upper
        && (this.laidStyle == style || this.laidStyle.equals(style))
        && text.equals(this.laidText)
        && this.laidArrange.equals(look.arrange);
  }

  private void layOut(Look look, Font font, Style style, String text) {
    this.partsBound = false;
    this.laidOut = true;
    this.laidText = text;
    this.laidFont = font;
    this.laidStyle = style;
    this.laidSize = look.size;
    this.laidArrange = look.arrange;
    this.laidSpacing = look.letterSpacing;
    this.laidGap = look.lineGap;
    this.laidWrap = look.wrapWidth;
    this.laidUpper = look.upper;

    ComposedLine composed = this.line;
    this.rowsPlaced = 0;
    composed.letters.clear();
    composed.width = 0.0F;
    composed.height = 0.0F;
    composed.wordCount = 0;
    composed.glyphHeight = font.lineHeight;
    composed.restBottom = 0.0F;
    composed.restFront = 0.0F;
    composed.restBack = 0.0F;

    String shown = (look.upper ? text.toUpperCase(Locale.ROOT) : text).strip();
    if (shown.isEmpty()) {
      return;
    }

    // With outlines every letter is its own object in any script. Only the font's texture needs a right-to-left row in
    // one piece, as the game shapes it, and then no letter can move by itself.
    VectorFace face = this.outlines ? VectorFace.of(look) : null;
    String joined = face == null ? shown : Script.joined(shown);
    char[] chars = joined.toCharArray();
    boolean rightToLeft = Bidi.requiresBidi(chars, 0, chars.length);
    Sizer sizer = new Sizer(font, style, look, face);
    if (rightToLeft && face == null) {
      this.layOutWholeRows(shown, sizer, look, font);
    } else {
      this.layOutLetters(joined, sizer, look);
      prepareGlitch(composed, sizer);
    }

    composed.outlined = face != null;
    this.finishLayout();
    this.findRestExtent(face);
  }

  /** How low and how deep the line at rest really reaches, from the letters' true outlines where they have them. */
  private void findRestExtent(VectorFace face) {
    ComposedLine composed = this.line;
    List<Letter> letters = composed.letters;
    float lowest = 0.0F;
    float front = 0.0F;
    float back = 0.0F;
    float halfLine = composed.glyphHeight * 0.5F;
    for (int i = 0; i < letters.size(); i++) {
      Letter l = letters.get(i);
      VectorFace.Glyph glyph = face == null ? null : face.glyph(l.homeGlyph);
      float below = glyph == null ? halfLine : SlotDrawer.BASELINE_BELOW_CENTRE + glyph.maxY * face.scale;
      float turn = (float) Math.toRadians(l.homeTiltZ);
      float reach = below * Math.abs((float) Math.cos(turn)) + l.homeWidth * 0.5F * Math.abs((float) Math.sin(turn));
      lowest = Math.max(lowest, l.homeY + reach * l.homeScale);
      front = Math.max(front, l.homeZ);
      back = Math.min(back, l.homeZ);
    }

    composed.restBottom = lowest;
    composed.restFront = front;
    composed.restBack = back;
  }

  private void layOutLetters(String shown, Sizer sizer, Look look) {
    float size = Math.max(0.01F, look.size);
    List<Word> words = measureWords(shown, sizer);
    switch (look.arrange) {
      case "WORDS" -> this.arrangeWords(words, sizer, size);
      case "STAIRS" -> this.arrangeStairs(words, sizer, size);
      case "ARC" -> this.arrangeArc(words, sizer, size);
      case "STACK" -> this.arrangeStack(words, sizer, size);
      default -> this.arrangeRows(words, sizer, size, 0.0F);
    }
  }

  private float arrangeRows(List<Word> words, Sizer sizer, float scale, float startY) {
    float y = startY;
    List<Row> rows = wrap(words, sizer.wrap / scale, sizer);
    for (int i = 0; i < rows.size(); i++) {
      this.placeRow(rows.get(i), sizer, scale, y, 0.0F, 0.0F);
      y += sizer.pitch(scale);
    }

    return y;
  }

  private void arrangeWords(List<Word> words, Sizer sizer, float size) {
    Word first = words.get(0);
    // Big, but never so big that the first word alone leaves the wrap width.
    float big = Math.max(size, Math.min(size * BIG_FIRST_WORD, sizer.wrap / Math.max(1.0F, first.width(sizer))));
    float y = this.arrangeRows(words.subList(0, 1), sizer, big, 0.0F);
    this.arrangeRows(words.subList(1, words.size()), sizer, size * SMALL_REST, y);
  }

  private void arrangeStairs(List<Word> words, Sizer sizer, float size) {
    List<Row> rows = wrap(words, sizer.wrap / size, sizer);
    float y = 0.0F;
    float rise = sizer.lineHeight * STAIR_RISE * size;
    for (int i = 0; i < rows.size(); i++) {
      Row row = rows.get(i);
      this.placeRow(row, sizer, size, y, rise, STAIR_DEPTH * size);
      y += sizer.pitch(size) + rise * (row.words.size() - 1);
    }
  }

  private void arrangeArc(List<Word> words, Sizer sizer, float size) {
    List<Row> rows = wrap(words, sizer.wrap / size, sizer);
    float pitch = sizer.pitch(size);
    float widest = 0.0F;
    for (int i = 0; i < rows.size(); i++) {
      widest = Math.max(widest, rows.get(i).width(sizer) * size);
    }

    // The rows share a centre, like the bands of a rainbow, so a row's ends cannot run into the top of the row below.
    float innerRadius = Math.max(widest / ARC_WIDEST_SWEEP, sizer.wrap * ARC_LEAST_RADIUS);
    for (int i = 0; i < rows.size(); i++) {
      float radius = innerRadius + pitch * (rows.size() - 1 - i);
      float apexY = i * pitch;
      int from = this.line.letters.size();
      this.placeRow(rows.get(i), sizer, size, apexY, 0.0F, 0.0F);
      for (int k = from; k < this.line.letters.size(); k++) {
        Letter l = this.line.letters.get(k);
        float angle = l.homeX / radius;
        l.homeX = radius * (float) Math.sin(angle);
        l.homeY += radius * (1.0F - (float) Math.cos(angle));
        l.homeTiltZ = (float) Math.toDegrees(angle);
      }
    }
  }

  private void arrangeStack(List<Word> words, Sizer sizer, float size) {
    float limit = sizer.wrap / size;
    List<Word> fitted = breakLongWords(words, limit, sizer);
    List<Row> rows = new ArrayList<>();
    Row open = null;
    for (int i = 0; i < fitted.size(); i++) {
      Word word = fitted.get(i);
      boolean joins = open != null
          && open.words.size() == 1
          && open.width(sizer) + sizer.space + word.width(sizer) <= limit * STACK_PAIR_SHARE;
      if (joins) {
        open.words.add(word);
      } else {
        open = new Row();
        open.words.add(word);
        rows.add(open);
      }
    }

    float target = STACK_LEAST_WIDTH;
    for (int i = 0; i < rows.size(); i++) {
      target = Math.max(target, rows.get(i).width(sizer));
    }

    target = Math.min(target, limit);
    float y = 0.0F;
    for (int i = 0; i < rows.size(); i++) {
      Row row = rows.get(i);
      // Every row is sized to the same width, which is what makes it read as a poster.
      float grow = target / Math.max(1.0F, row.width(sizer));
      float scale = size * Math.max(STACK_LEAST_SCALE, Math.min(STACK_MOST_SCALE, grow));
      this.placeRow(row, sizer, scale, y, 0.0F, 0.0F);
      y += sizer.pitch(scale);
    }
  }

  /** Puts a row's letters down, centred on x = 0, with each word a step up and back when asked for. */
  private void placeRow(Row row, Sizer sizer, float scale, float topY, float stepUp, float stepBack) {
    int rowIndex = this.rowsPlaced++;
    float cursor = -row.width(sizer) * scale * 0.5F;
    float centreY = topY + sizer.lineHeight * scale * 0.5F;
    int firstLetter = this.line.letters.size();
    for (int w = 0; w < row.words.size(); w++) {
      Word word = row.words.get(w);
      if (w > 0) {
        cursor += (word.continuation ? sizer.spacing : sizer.space) * scale;
      }

      for (int g = 0; g < word.glyphs.size(); g++) {
        if (g > 0 && !joinsBefore(word, g)) {
          cursor += sizer.spacing * scale;
        }

        float glyphWidth = word.widths.get(g);
        Letter l = this.nextLetter();
        l.wholeRow = false;
        l.homeGlyph = word.glyphs.get(g);
        l.homeWidth = glyphWidth;
        l.homeScale = scale;
        l.homeX = cursor + glyphWidth * scale * 0.5F;
        l.homeY = centreY - w * stepUp;
        l.homeZ = -w * stepBack;
        l.homeTiltZ = 0.0F;
        l.word = word.index;
        l.row = rowIndex;
        cursor += glyphWidth * scale;
      }
    }

    this.readInDisplayOrder(row, firstLetter);
  }

  /**
   * Letters stay in reading order in the list, as the singing and the motion go along them, and it is only their places
   * across the row that a right-to-left run reverses. The row keeps its width, and where each letter stood is handed on
   * by the rules of Unicode's bidirectional algorithm: whatever part of a row runs right to left comes out mirrored in
   * place, so a Latin word inside an Arabic line still reads left to right.
   */
  private void readInDisplayOrder(Row row, int firstLetter) {
    String text = row.plainText();
    char[] chars = text.toCharArray();
    List<Letter> letters = this.line.letters;
    int count = letters.size() - firstLetter;
    if (count < 2 || !Bidi.requiresBidi(chars, 0, chars.length)) {
      if (count == 1 && Bidi.requiresBidi(chars, 0, chars.length)) {
        Letter only = letters.get(firstLetter);
        only.homeGlyph = Script.mirrored(only.homeGlyph);
      }

      return;
    }

    Bidi bidi = new Bidi(text, Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT);
    // One slot for each letter and for each gap between words, as a gap moves with the run it stands in.
    List<Slot> slots = new ArrayList<>(count + row.words.size());
    int at = 0;
    int letter = firstLetter;
    for (int w = 0; w < row.words.size(); w++) {
      Word word = row.words.get(w);
      if (w > 0 && !word.continuation) {
        slots.add(new Slot(null, this.gapBefore(letters, letter), bidi.getLevelAt(at)));
        at++;
      }

      for (int g = 0; g < word.glyphs.size(); g++) {
        Letter l = letters.get(letter++);
        slots.add(new Slot(l, l.homeWidth * l.homeScale, bidi.getLevelAt(at)));
        at += l.homeGlyph.length();
      }
    }

    int size = slots.size();
    byte[] levels = new byte[size];
    Slot[] order = new Slot[size];
    for (int i = 0; i < size; i++) {
      order[i] = slots.get(i);
      levels[i] = (byte) order[i].level;
    }

    Bidi.reorderVisually(levels, 0, order, 0, size);
    float left = Float.MAX_VALUE;
    float right = -Float.MAX_VALUE;
    for (int i = firstLetter; i < letters.size(); i++) {
      Letter l = letters.get(i);
      float half = l.homeWidth * l.homeScale * 0.5F;
      left = Math.min(left, l.homeX - half);
      right = Math.max(right, l.homeX + half);
    }

    float cursor = left;
    float total = 0.0F;
    for (int i = 0; i < size; i++) {
      total += order[i].width;
    }

    float spare = size > 1 ? (right - left - total) / (size - 1) : 0.0F;
    for (int i = 0; i < size; i++) {
      Slot slot = order[i];
      if (slot.letter != null) {
        slot.letter.homeX = cursor + slot.width * 0.5F;
        if ((slot.level & 1) != 0) {
          slot.letter.homeGlyph = Script.mirrored(slot.letter.homeGlyph);
        }
      }

      cursor += slot.width + spare;
    }
  }

  /** The room a word gap takes, read off the letters as they were placed in reading order. */
  private float gapBefore(List<Letter> letters, int next) {
    Letter before = letters.get(next - 1);
    Letter after = letters.get(next);
    return after.homeX - after.homeWidth * after.homeScale * 0.5F - before.homeX
        - before.homeWidth * before.homeScale * 0.5F;
  }

  private record Slot(Letter letter, float width, int level) {

  }

  private void layOutWholeRows(String shown, Sizer sizer, Look look, Font font) {
    float scale = Math.max(0.01F, look.size);
    List<Row> rows = wrap(measureWords(shown, sizer), sizer.wrap / scale, sizer);
    float y = 0.0F;
    for (int i = 0; i < rows.size(); i++) {
      String row = rows.get(i).plainText();
      // Letters here change shape with their neighbours and the row reads the other way, so it can only be one piece.
      Letter l = this.nextLetter();
      l.wholeRow = true;
      l.homeGlyph = font.bidirectionalShaping(row);
      l.homeWidth = sizer.measure(l.homeGlyph);
      l.homeScale = scale;
      l.homeX = 0.0F;
      l.homeY = y + sizer.lineHeight * scale * 0.5F;
      l.homeZ = 0.0F;
      l.homeTiltZ = 0.0F;
      l.word = i;
      l.row = i;
      y += sizer.pitch(scale);
    }
  }

  private static boolean joinsBefore(Word word, int glyph) {
    return Script.joins(word.glyphs.get(glyph).codePointAt(0)) && Script.joins(word.glyphs.get(glyph - 1).codePointAt(0));
  }

  private static void prepareGlitch(ComposedLine composed, Sizer sizer) {
    if (composed.glitchGlyphs != GLITCH_GLYPHS) {
      composed.glitchGlyphs = GLITCH_GLYPHS;
      composed.glitchWidths = new float[GLITCH_GLYPHS.length];
    }

    for (int i = 0; i < GLITCH_GLYPHS.length; i++) {
      composed.glitchWidths[i] = sizer.measure(GLITCH_GLYPHS[i]);
    }
  }

  private Letter nextLetter() {
    int used = this.line.letters.size();
    Letter l;
    if (used < this.pool.size()) {
      l = this.pool.get(used);
    } else {
      l = new Letter();
      this.pool.add(l);
    }

    this.line.letters.add(l);
    return l;
  }

  /** Moves everything so the origin is where {@link ComposedLine} says it is, and fills in what depends on the whole. */
  private void finishLayout() {
    ComposedLine composed = this.line;
    List<Letter> letters = composed.letters;
    int count = letters.size();
    if (count == 0) {
      return;
    }

    float left = Float.MAX_VALUE;
    float right = -Float.MAX_VALUE;
    float top = Float.MAX_VALUE;
    float bottom = -Float.MAX_VALUE;
    float back = Float.MAX_VALUE;
    float front = -Float.MAX_VALUE;
    for (int i = 0; i < count; i++) {
      Letter l = letters.get(i);
      float halfWidth = l.homeWidth * l.homeScale * 0.5F;
      float halfHeight = composed.glyphHeight * l.homeScale * 0.5F;
      left = Math.min(left, l.homeX - halfWidth);
      right = Math.max(right, l.homeX + halfWidth);
      top = Math.min(top, l.homeY - halfHeight);
      bottom = Math.max(bottom, l.homeY + halfHeight);
      back = Math.min(back, l.homeZ);
      front = Math.max(front, l.homeZ);
    }

    float middleX = (left + right) * 0.5F;
    float middleZ = (back + front) * 0.5F;
    composed.width = right - left;
    composed.height = bottom - top;

    int runStart = 0;
    for (int i = 0; i < count; i++) {
      Letter l = letters.get(i);
      l.homeX -= middleX;
      l.homeY -= top;
      l.homeZ -= middleZ;
      l.index = i;
      l.linePosition = i / (float) count;
      l.spanX = composed.width <= 0.0F ? 0.0F : (l.homeX + composed.width * 0.5F) / composed.width;
      l.spanY = composed.height <= 0.0F ? 0.0F : l.homeY / composed.height;

      if (i + 1 == count || letters.get(i + 1).word != l.word) {
        for (int k = runStart; k <= i; k++) {
          letters.get(k).letterInWord = k - runStart;
          letters.get(k).wordLetters = i + 1 - runStart;
        }

        runStart = i + 1;
      }
    }

    composed.wordCount = letters.get(count - 1).word + 1;
    composed.outlined &= !letters.get(0).wholeRow;
  }

  // Measuring

  private static List<Word> measureWords(String text, Sizer sizer) {
    List<Word> words = new ArrayList<>();
    BreakIterator clusters = BreakIterator.getCharacterInstance(Locale.ROOT);
    int length = text.length();
    int at = 0;
    while (at < length) {
      if (Character.isWhitespace(text.charAt(at))) {
        at++;
        continue;
      }

      int end = at;
      while (end < length && !Character.isWhitespace(text.charAt(end))) {
        end++;
      }

      // By clusters rather than chars, so an accent stays on its letter and an emoji stays whole.
      Word word = new Word(words.size(), false);
      String piece = text.substring(at, end);
      clusters.setText(piece);
      int from = clusters.first();
      for (int to = clusters.next(); to != BreakIterator.DONE; from = to, to = clusters.next()) {
        String glyph = piece.substring(from, to);
        word.add(glyph, sizer.measure(glyph));
      }

      words.add(word);
      at = end;
    }

    return words;
  }

  /** Text without spaces, like Chinese or Japanese, is one long "word" and still has to wrap. */
  private static List<Word> breakLongWords(List<Word> words, float limit, Sizer sizer) {
    List<Word> fitted = new ArrayList<>(words.size());
    for (int i = 0; i < words.size(); i++) {
      Word word = words.get(i);
      if (word.width(sizer) <= limit || word.glyphs.size() < 2) {
        fitted.add(word);
        continue;
      }

      Word part = new Word(word.index, false);
      for (int g = 0; g < word.glyphs.size(); g++) {
        float glyphWidth = word.widths.get(g);
        if (!part.glyphs.isEmpty() && part.width(sizer) + sizer.spacing + glyphWidth > limit) {
          fitted.add(part);
          part = new Word(word.index, true);
        }

        part.add(word.glyphs.get(g), glyphWidth);
      }

      fitted.add(part);
    }

    return fitted;
  }

  private static List<Row> wrap(List<Word> words, float limit, Sizer sizer) {
    List<Word> fitted = breakLongWords(words, limit, sizer);
    List<Row> rows = new ArrayList<>();
    Row open = null;
    for (int i = 0; i < fitted.size(); i++) {
      Word word = fitted.get(i);
      if (open == null || open.width(sizer) + sizer.space + word.width(sizer) > limit) {
        open = new Row();
        rows.add(open);
      }

      open.words.add(word);
    }

    return rows;
  }

  /** The font-dependent numbers of one layout, in font pixels. */
  private static final class Sizer {

    final Font font;
    final Style style;
    final float lineHeight;
    final float spacing;
    // The whole gap between two words, with the letter spacing on both sides of the space.
    final float space;
    final float gap;
    final float wrap;
    final VectorFace face;

    Sizer(Font font, Style style, Look look, VectorFace face) {
      this.font = font;
      this.style = style;
      this.face = face;
      this.lineHeight = font.lineHeight;
      this.spacing = look.letterSpacing;
      this.gap = look.lineGap;
      this.wrap = Math.max(20, look.wrapWidth);
      this.space = this.measure(" ") + look.letterSpacing * 2.0F;
    }

    /**
     * A letter's width: what its outlines say when that is how it will be drawn, and the font's own width otherwise, so
     * letters of both kinds in one row keep to the same spacing.
     */
    float measure(String glyph) {
      if (this.face != null) {
        float advance = this.face.advanceOf(glyph);
        if (advance >= 0.0F) {
          return advance;
        }
      }

      return this.font.getSplitter().stringWidth(FormattedCharSequence.forward(glyph, this.style));
    }

    float pitch(float scale) {
      return Math.max(2.0F, this.lineHeight + ROW_AIR + this.gap) * scale;
    }
  }

  private static final class Word {

    final int index;
    // A later part of a word that had to be broken: no space goes before it.
    final boolean continuation;
    final List<String> glyphs = new ArrayList<>();
    final List<Float> widths = new ArrayList<>();
    float glyphsWidth;
    private int joinedGaps;

    Word(int index, boolean continuation) {
      this.index = index;
      this.continuation = continuation;
    }

    void add(String glyph, float width) {
      this.glyphs.add(glyph);
      this.widths.add(width);
      this.glyphsWidth += width;
      if (this.glyphs.size() > 1 && joinsBefore(this, this.glyphs.size() - 1)) {
        this.joinedGaps++;
      }
    }

    float width(Sizer sizer) {
      return this.glyphsWidth + sizer.spacing * Math.max(0, this.glyphs.size() - 1 - this.joinedGaps);
    }
  }

  private static final class Row {

    final List<Word> words = new ArrayList<>();

    float width(Sizer sizer) {
      float width = 0.0F;
      for (int i = 0; i < this.words.size(); i++) {
        Word word = this.words.get(i);
        width += word.width(sizer);
        if (i > 0) {
          width += word.continuation ? sizer.spacing : sizer.space;
        }
      }

      return width;
    }

    String plainText() {
      StringBuilder text = new StringBuilder();
      for (int i = 0; i < this.words.size(); i++) {
        Word word = this.words.get(i);
        if (i > 0 && !word.continuation) {
          text.append(' ');
        }

        for (int g = 0; g < word.glyphs.size(); g++) {
          text.append(word.glyphs.get(g));
        }
      }

      return text.toString();
    }
  }
}
