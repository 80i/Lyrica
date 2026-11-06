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
import net.lyrica.api.LyricsBridge;

/**
 * One line of words on a card. The font LabyMod draws tags in has Latin letters and little else and shows anything further
 * afield as empty boxes, and it pays no attention to a font asked for. So words with other letters come as a picture from
 * the version side, which also gets their joining and direction right, and everything else is plain text.
 */
final class Words {

  private static final String ELLIPSIS = "…";
  private static final char LATIN_ENDS = 0x0250;
  private static final char PUNCTUATION = 0x2000;
  private static final char PUNCTUATION_ENDS = 0x2070;
  private static final int SHADOW_RGB = 0x000000;
  private static final float SHADOW_ALPHA = 0.55F;
  private static final Map<ResourceLocation, Material> MATERIALS = new HashMap<>();

  final Component text;
  final LyricsBridge.TextImage image;
  /** On the card, in text pixels, at the size it was made for. */
  final float width;
  final float size;
  /** Its picture is still being made, so this stands in for it. */
  final boolean waiting;

  private Words(Component text, LyricsBridge.TextImage image, float width, float size, boolean waiting) {
    this.text = text;
    this.image = image;
    this.width = width;
    this.size = size;
    this.waiting = waiting;
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
    collector.order(order).submitCustomGeometry(stack, material, (pose, out) -> {
      if (shadow) {
        CardShapes.picture(out, pose, left + pixel, top + pixel, right + pixel, bottom + pixel, z, SHADOW_RGB,
            alpha * SHADOW_ALPHA);
      }

      CardShapes.picture(out, pose, left, top, right, bottom, z, rgb, alpha);
    });
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
