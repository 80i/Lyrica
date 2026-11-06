package net.lyrica.v26_3.stage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import net.lyrica.v26_3.Env;
import net.lyrica.v26_3.look.Fonts;
import net.lyrica.v26_3.look.Look;

/**
 * One font as the look wants it, in real outlines. Building a glyph can cost milliseconds and a font's first use far
 * more, so a worker builds what a song needs ahead of time, and a line only goes on stage once its letters are ready.
 * Everything but the worker's part runs on the render thread.
 */
final class VectorFace {

  /** The strokes that thicken a font with no bold file, and that make an outline. Outline pixels, both sides counted. */
  static final float BOLD_STROKE = 0.35F;
  static final float OUTLINE_STROKE = 1.2F;

  /**
   * Every glitch character has to be there too, as they stand in for letters at any moment, and the space, as every
   * layout measures it even for a line without one.
   */
  private static final String ALWAYS = " #%&/?01XZ$=+";
  private static final long WORKER_IDLE_SECONDS = 20L;

  private static final List<VectorFace> KNOWN = new ArrayList<>();
  private static final Glyph NO_GLYPH = new Glyph(0, false);
  private static final ExecutorService WORKER = newWorker();

  private static VectorFace last;
  private static boolean logSet;

  final String fontName;
  final boolean bold;
  final boolean outline;
  final String file;
  final boolean boldFile;
  /** Text pixels per outline pixel. */
  final float scale;
  /** What a thickened font's letters take beyond their advance, in text pixels. */
  final float extraAdvance;

  private final float bodyStroke;
  private final float plateStroke;
  private final Map<String, Glyph> glyphs = new HashMap<>();
  private final Set<Integer> ready = ConcurrentHashMap.newKeySet();
  private final Set<String> asked = ConcurrentHashMap.newKeySet();

  private VectorFace(String fontName, boolean bold, boolean outline, String file) {
    this.fontName = fontName;
    this.bold = bold;
    this.outline = outline;
    this.file = file;
    this.boldFile = bold && Fonts.hasBoldFile(fontName);
    this.scale = Fonts.meshScale(fontName, bold);
    this.bodyStroke = bold && !this.boldFile ? BOLD_STROKE : 0.0F;
    this.plateStroke = outline ? this.bodyStroke + OUTLINE_STROKE : 0.0F;
    this.extraAdvance = this.bodyStroke * this.scale;
  }

  /** The face this look asks for, or null when it uses the game's own font. */
  static VectorFace of(Look look) {
    VectorFace known = last;
    if (known != null && known.bold == look.bold && known.outline == look.outline && known.fontName.equals(look.font)) {
      return known;
    }

    String file = Fonts.file(look.font);
    if (file == null) {
      return null;
    }

    for (int i = 0; i < KNOWN.size(); i++) {
      VectorFace face = KNOWN.get(i);
      if (face.bold == look.bold && face.outline == look.outline && face.fontName.equals(look.font)) {
        last = face;
        return face;
      }
    }

    if (!logSet) {
      logSet = true;
      try {
        GlyphOutlines.setLog((message, cause) -> Env.LOG.warn(message, cause));
      } catch (LinkageError failure) {
        // A game without working java.awt: every letter then comes from the font's texture, see glyph().
        Env.LOG.warn("Letter outlines are not available", failure);
      }
    }

    VectorFace face = new VectorFace(look.font, look.bold, look.outline, file);
    KNOWN.add(face);
    last = face;
    return face;
  }

  /** One thread, there only while it has work, so that nothing stays behind once the lyrics are switched off. */
  private static ExecutorService newWorker() {
    ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, WORKER_IDLE_SECONDS, TimeUnit.SECONDS,
        new LinkedBlockingQueue<>(), task -> {
          Thread thread = new Thread(task, "Lyrica glyphs");
          thread.setDaemon(true);
          return thread;
        });
    worker.allowCoreThreadTimeOut(true);
    return worker;
  }

  // Getting ready

  /** Whether all of this text can be drawn without building anything. If not, it is on its way. */
  boolean ready(String text) {
    String joined = Script.joined(text);
    for (int i = 0; i < joined.length(); ) {
      int codePoint = joined.codePointAt(i);
      i += Character.charCount(codePoint);
      if (!this.ready.contains(codePoint)) {
        this.ask(text);
        return false;
      }
    }

    return true;
  }

  /** Builds this text's characters on the worker, unless that has been asked for already. */
  void ask(String text) {
    if (this.asked.add(text)) {
      WORKER.execute(() -> this.build(text));
    }
  }

  private void build(String text) {
    String all = text + Script.joined(text) + text.toUpperCase(Locale.ROOT) + ALWAYS;
    StringBuilder distinct = new StringBuilder();
    Set<Integer> seen = new HashSet<>();
    for (int i = 0; i < all.length(); ) {
      int codePoint = all.codePointAt(i);
      i += Character.charCount(codePoint);
      if (!this.ready.contains(codePoint) && seen.add(codePoint)) {
        distinct.appendCodePoint(codePoint);
      }
    }

    try {
      if (distinct.length() > 0) {
        String wanted = distinct.toString();
        GlyphOutlines.prepare(this.file, this.boldFile, wanted, this.bodyStroke, Long.MAX_VALUE);
        if (this.plateStroke > 0.0F) {
          GlyphOutlines.prepare(this.file, this.boldFile, wanted, this.plateStroke, Long.MAX_VALUE);
        }
      }
    } catch (RuntimeException | Error failure) {
      Env.LOG.warn("Could not build the letters of " + this.fontName, failure);
    } finally {
      // Marked last, and whatever came of it: a glyph that could not be built simply falls back to the font's texture,
      // while a line that was never marked would wait for good. From here on the marks answer for this text.
      this.ready.addAll(seen);
      this.asked.remove(text);
    }
  }

  // Glyphs

  /** A letter's outlines, or null when any part of it is missing from the font and it has to come from the texture. */
  Glyph glyph(String text) {
    Glyph glyph = this.glyphs.get(text);
    if (glyph == null) {
      try {
        glyph = this.make(text);
      } catch (LinkageError failure) {
        glyph = NO_GLYPH;
      }

      this.glyphs.put(text, glyph);
    }

    return glyph == NO_GLYPH ? null : glyph;
  }

  private Glyph make(String text) {
    int parts = text.codePointCount(0, text.length());
    if (parts == 0) {
      return NO_GLYPH;
    }

    Glyph glyph = new Glyph(parts, this.plateStroke > 0.0F);
    float pen = 0.0F;
    int part = 0;
    for (int i = 0; i < text.length(); part++) {
      int codePoint = text.codePointAt(i);
      i += Character.charCount(codePoint);
      GlyphMesh fine = this.body(codePoint, GlyphOutlines.FINE);
      GlyphMesh coarse = this.body(codePoint, GlyphOutlines.COARSE);
      if (fine == null || coarse == null) {
        return NO_GLYPH;
      }

      // A vowel sign in a right-to-left script is drawn for a pen that moves the other way, so it is put over its letter
      float at = part > 0 && fine.advance() == 0.0F && !fine.isEmpty() && rightToLeft(codePoint)
          ? glyph.partX[0] + (glyph.fine[0].minX() + glyph.fine[0].maxX() - fine.minX() - fine.maxX()) * 0.5F
          : pen;
      glyph.fine[part] = fine;
      glyph.coarse[part] = coarse;
      glyph.partX[part] = at;
      glyph.take(fine, at);
      if (this.plateStroke > 0.0F) {
        GlyphMesh plateFine = this.plate(codePoint, GlyphOutlines.FINE);
        GlyphMesh plateCoarse = this.plate(codePoint, GlyphOutlines.COARSE);
        if (plateFine == null || plateCoarse == null) {
          return NO_GLYPH;
        }

        glyph.plateFine[part] = plateFine;
        glyph.plateCoarse[part] = plateCoarse;
        glyph.take(plateFine, at);
      }

      pen += fine.advance();
    }

    glyph.advance = pen;
    return glyph;
  }

  private static boolean rightToLeft(int codePoint) {
    return Script.joins(codePoint) || codePoint >= 0x0590 && codePoint <= 0x05FF;
  }

  private GlyphMesh body(int codePoint, int detail) {
    return this.bodyStroke > 0.0F
        ? GlyphOutlines.outlined(this.file, this.boldFile, codePoint, detail, this.bodyStroke)
        : GlyphOutlines.get(this.file, this.boldFile, codePoint, detail);
  }

  private GlyphMesh plate(int codePoint, int detail) {
    return GlyphOutlines.outlined(this.file, this.boldFile, codePoint, detail, this.plateStroke);
  }

  /** The advance of a piece of text in text pixels, or -1 when part of it would come from the font's texture. */
  float advanceOf(String text) {
    Glyph glyph = this.glyph(text);
    return glyph == null ? -1.0F : glyph.advance * this.scale + this.extraAdvance;
  }

  /** A letter's meshes: usually one part, more when an accent comes as a character of its own. */
  static final class Glyph {

    final GlyphMesh[] fine;
    final GlyphMesh[] coarse;
    final GlyphMesh[] plateFine;
    final GlyphMesh[] plateCoarse;
    final float[] partX;
    float advance;
    float minX;
    float minY;
    float maxX;
    float maxY;

    private Glyph(int parts, boolean plates) {
      this.fine = new GlyphMesh[parts];
      this.coarse = new GlyphMesh[parts];
      this.plateFine = plates ? new GlyphMesh[parts] : null;
      this.plateCoarse = plates ? new GlyphMesh[parts] : null;
      this.partX = new float[parts];
    }

    private void take(GlyphMesh mesh, float pen) {
      if (!mesh.isEmpty()) {
        this.minX = Math.min(this.minX, mesh.minX() + pen);
        this.minY = Math.min(this.minY, mesh.minY());
        this.maxX = Math.max(this.maxX, mesh.maxX() + pen);
        this.maxY = Math.max(this.maxY, mesh.maxY());
      }
    }
  }
}
