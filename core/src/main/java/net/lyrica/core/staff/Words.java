package net.lyrica.core.staff;

import java.util.HashMap;
import java.util.Map;
import net.labymod.api.client.component.Component;
import net.labymod.api.client.component.format.TextDecoration;
import net.labymod.api.client.gfx.pipeline.renderer.text.FontRenderer;
import net.labymod.api.client.render.matrix.Stack;
import net.labymod.api.client.resources.ResourceLocation;
import net.labymod.api.laby3d.pipeline.RenderStates;
import net.labymod.api.laby3d.pipeline.material.LevelMaterial;
import net.labymod.api.laby3d.pipeline.material.Material;
import net.labymod.api.laby3d.render.queue.SubmissionCollector;
import net.labymod.laby3d.api.vertex.VertexConsumer;
import net.lyrica.api.LyricsBridge;
import org.joml.Matrix4f;

/**
 * One line of words on a card. The font LabyMod draws tags in has Latin letters and little else and shows anything further
 * afield as empty boxes, and it pays no attention to a font asked for. So words with other letters come as a picture from
 * the version side, which also gets their joining and direction right, and everything else is plain text. A title that
 * slides is also a picture whatever its letters: a tag's text cannot be clipped, but a window on a picture can.
 */
final class Words {

  private static final String ELLIPSIS = "…";
  private static final char LATIN_ENDS = 0x0250;
  private static final char PUNCTUATION = 0x2000;
  private static final char PUNCTUATION_ENDS = 0x2070;
  private static final int SHADOW_RGB = 0x000000;
  private static final float SHADOW_ALPHA = 0.55F;
  // A picture of the whole title, however long, within what a texture can be
  private static final float MOST_PICTURE_WIDTH = 2000.0F;
  private static final long SLIDE_HOLD_MS = 1_600L;
  private static final float SLIDE_UNITS_PER_S = 14.0F;
  private static final float SLIDE_GAP = 26.0F;
  private static final Map<ResourceLocation, Material> MATERIALS = new HashMap<>();

  final Component text;
  final LyricsBridge.TextImage image;
  /** On the card, in text pixels, at the size it was made for. */
  final float width;
  final float size;
  /** Its picture is still being made, so this stands in for it. */
  final boolean waiting;
  /** The width of the window the picture slides through, or 0 when it does not slide. */
  private final float window;
  private final long sinceMs;

  private Words(Component text, LyricsBridge.TextImage image, float width, float size, boolean waiting) {
    this(text, image, width, size, waiting, 0.0F);
  }

  private Words(Component text, LyricsBridge.TextImage image, float width, float size, boolean waiting, float window) {
    this.text = text;
    this.image = image;
    this.width = width;
    this.size = size;
    this.waiting = waiting;
    this.window = window;
    this.sinceMs = System.currentTimeMillis();
  }

  /** @param most how wide it may get on the card, in text pixels */
  static Words of(String words, boolean bold, float most, float size, FontRenderer font, LyricsBridge bridge) {
    if (latin(words)) {
      Component text = Component.text(cut(words, most / size, bold, font));
      if (bold) {
        text = text.decorate(TextDecoration.BOLD);
      }

      return new Words(text, null, font.getWidth(text) * size, size, false);
    }

    LyricsBridge.TextImage image = bridge.wordsImage(words, bold, most / size);
    if (image == null) {
      return new Words(Component.empty(), null, most * 0.5F, size, true);
    }

    return new Words(null, image, image.width() * size, size, false);
  }

  /**
   * A bold title at full size that slides through a window of this width if it does not fit. Until its picture is made,
   * a title in the tag's font stands in for it, cut to fit.
   *
   * @param window how wide it may get on the card, in text pixels
   */
  static Words sliding(String words, float window, FontRenderer font, LyricsBridge bridge) {
    boolean latin = latin(words);
    if (latin && font.getWidth(Component.text(words).decorate(TextDecoration.BOLD)) <= window) {
      return of(words, true, window, 1.0F, font, bridge);
    }

    LyricsBridge.TextImage image = bridge.wordsImage(words, true, MOST_PICTURE_WIDTH);
    if (image == null) {
      Words standIn = of(words, true, window, 1.0F, font, bridge);
      return new Words(standIn.text, standIn.image, standIn.width, 1.0F, true);
    }

    return image.width() <= window ? new Words(null, image, image.width(), 1.0F, false)
        : new Words(null, image, window, 1.0F, false, window);
  }

  private static boolean latin(String text) {
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c >= LATIN_ENDS && (c < PUNCTUATION || c >= PUNCTUATION_ENDS)) {
        return false;
      }
    }

    return true;
  }

  /** A long name ends in an ellipsis, so it never makes a huge card. */
  private static String cut(String text, float most, boolean bold, FontRenderer font) {
    float plain = font.getWidth(text);
    float wide = bold ? font.getWidth(Component.text(text).decorate(TextDecoration.BOLD)) : plain;
    if (wide <= most) {
      return text;
    }

    float toPlain = plain <= 0.0F ? 1.0F : plain / wide;
    String kept = font.trimTextToWidth(text, (most - font.getWidth(ELLIPSIS)) * toPlain);
    return kept.stripTrailing() + ELLIPSIS;
  }

  /** At these card coordinates, with its first line's capitals starting at {@code y}. */
  void submit(Stack stack, SubmissionCollector collector, CardGrid grid, float x, float y, int rgb, float alpha, float z,
      int light, int flags, int order, boolean shadow) {
    if (this.image == null) {
      stack.push();
      stack.translate(grid.x(x), grid.y(y), z);
      stack.scale(this.size, this.size, 1.0F);
      collector.order(order).submitComponent(stack, this.text, 0.0F, 0.0F, Math.round(alpha * 255.0F) << 24 | rgb, light, 0,
          1.0F, flags);
      stack.pop();
      return;
    }

    float left = grid.x(x);
    float top = grid.y(y) - this.image.above() * this.size;
    float right = left + this.image.width() * this.size;
    float bottom = top + this.image.height() * this.size;
    float pixel = grid.pixel;
    Material material = material(this.image.texture());
    if (this.window > 0.0F) {
      this.submitSliding(stack, collector, material, left, grid.x(x + this.window), top, bottom, pixel, rgb, alpha, z, order,
          shadow);
      return;
    }

    collector.order(order).submitCustomGeometry(stack, material, (pose, out) -> {
      if (shadow) {
        CardShapes.picture(out, pose, left + pixel, top + pixel, right + pixel, bottom + pixel, z, SHADOW_RGB,
            alpha * SHADOW_ALPHA);
      }

      CardShapes.picture(out, pose, left, top, right, bottom, z, rgb, alpha);
    });
  }

  /** The window on the picture, with the title's start following its end around, waiting a while at the start. */
  private void submitSliding(Stack stack, SubmissionCollector collector, Material material, float left, float right,
      float top, float bottom, float pixel, int rgb, float alpha, float z, int order, boolean shadow) {
    float wide = this.image.width();
    float travel = wide + SLIDE_GAP;
    long rollMs = (long) (travel / SLIDE_UNITS_PER_S * 1000.0F);
    long at = (System.currentTimeMillis() - this.sinceMs) % (SLIDE_HOLD_MS + rollMs);
    float along = at <= SLIDE_HOLD_MS ? 0.0F : (at - SLIDE_HOLD_MS) * SLIDE_UNITS_PER_S / 1000.0F;
    float shown = right - left;
    float firstEnd = Math.min(wide, along + shown);
    float secondWide = Math.min(shown, along + shown - travel);
    collector.order(order).submitCustomGeometry(stack, material, (pose, out) -> {
      if (shadow) {
        slices(out, pose, left + pixel, top + pixel, bottom + pixel, along, firstEnd, secondWide, travel, wide, z, SHADOW_RGB,
            alpha * SHADOW_ALPHA);
      }

      slices(out, pose, left, top, bottom, along, firstEnd, secondWide, travel, wide, z, rgb, alpha);
    });
  }

  private static void slices(VertexConsumer out, Matrix4f pose, float left, float top, float bottom, float along,
      float firstEnd, float secondWide, float travel, float wide, float z, int rgb, float alpha) {
    if (along < wide) {
      CardShapes.slice(out, pose, left, top, left + firstEnd - along, bottom, along / wide, firstEnd / wide, z, rgb, alpha);
    }

    if (secondWide > 0.0F) {
      float start = left + travel - along;
      CardShapes.slice(out, pose, start, top, start + secondWide, bottom, 0.0F, secondWide / wide, z, rgb, alpha);
    }
  }

  private static Material material(ResourceLocation texture) {
    Material material = MATERIALS.get(texture);
    if (material == null) {
      material = LevelMaterial.builder(RenderStates.SIMPLE_LEVEL_GEOMETRY).setTexture(0, texture).build();
      MATERIALS.put(texture, material);
    }

    return material;
  }
}
