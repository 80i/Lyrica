package net.splyrics.v1_21_11.stage;

import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.splyrics.v1_21_11.look.Look;
import net.splyrics.v1_21_11.look.Palette;
import org.joml.Matrix3x2fStack;

/** Draws a composed line in 2D for screen lyrics and previews, keeping a solid feel with a stack of darker copies. */
public final class FlatDrawer {

  // The distance of an imagined eye, so that movement in depth still reads as growing and shrinking on a flat screen.
  private static final float EYE_DISTANCE = 180.0F;
  private static final float NEAREST_Z = 90.0F;
  private static final float FARTHEST_Z = -150.0F;
  // Never fully zero: a letter squashed to nothing would make a matrix that cannot be undone.
  private static final float LEAST_SQUASH = 0.04F;
  private static final int MOST_LAYERS = 6;
  // Each layer is a separate piece of GUI text the game prepares again every frame, so a long line gets fewer.
  private static final int LAYER_BUDGET = 240;
  private static final float LAYERS_PER_DEPTH = 3.0F;
  private static final float LAYER_STEP = 0.45F;
  private static final float OUTLINE_REACH = 0.6F;

  private FlatDrawer() {
  }

  /**
   * @param centerX where x = 0 of the line lands
   * @param topY    where y = 0 of the line lands
   * @param scale   screen pixels per glyph pixel, on top of the letters' own scale
   */
  public static void draw(GuiGraphics g, Font font, Style style, ComposedLine line, Look look, float centerX,
      float topY, float scale) {
    List<Letter> letters = line.letters;
    int affordable = Math.max(1, LAYER_BUDGET / Math.max(1, letters.size()));
    int layers = Math.min(Math.min(MOST_LAYERS, affordable), Math.round(look.depth * LAYERS_PER_DEPTH));
    float middleY = line.height * 0.5F;
    float halfHeight = font.lineHeight * 0.5F;
    for (int i = 0; i < letters.size(); i++) {
      Letter l = letters.get(i);
      if (!l.visible()) {
        continue;
      }

      float near = EYE_DISTANCE / (EYE_DISTANCE - Math.max(FARTHEST_Z, Math.min(NEAREST_Z, l.z)));
      float px = centerX + l.x * near * scale;
      float py = topY + (middleY + (l.y - middleY) * near) * scale;
      float size = l.scale * near * scale;
      float sizeX = size * squash(l.tiltY);
      float sizeY = size * squash(l.tiltX);
      float turn = (float) Math.toRadians(l.tiltZ);
      float left = -l.width * 0.5F;
      FormattedCharSequence text = l.sequence(style);
      int alpha = l.argb & 0xFF000000;
      int rgb = l.argb & 0xFFFFFF;

      // Back to front, and offset on the screen rather than along the letter, so the thickness falls to one side
      // however the letter is turned.
      for (int step = layers; step >= 1; step--) {
        float away = step * LAYER_STEP * size;
        float shade = look.sideShade * (1.0F - 0.5F * (step - 1) / layers);
        layer(g, font, text, px + away, py + away, turn, sizeX, sizeY, left, -halfHeight,
            alpha | Palette.dim(rgb, shade));
      }

      if (look.outline) {
        int edge = alpha | (look.outlineColor & 0xFFFFFF);
        layer(g, font, text, px, py, turn, sizeX, sizeY, left - OUTLINE_REACH, -halfHeight, edge);
        layer(g, font, text, px, py, turn, sizeX, sizeY, left + OUTLINE_REACH, -halfHeight, edge);
        layer(g, font, text, px, py, turn, sizeX, sizeY, left, -halfHeight - OUTLINE_REACH, edge);
        layer(g, font, text, px, py, turn, sizeX, sizeY, left, -halfHeight + OUTLINE_REACH, edge);
      }

      layer(g, font, text, px, py, turn, sizeX, sizeY, left, -halfHeight, l.argb);
    }
  }

  private static void layer(GuiGraphics g, Font font, FormattedCharSequence text, float px, float py, float turn,
      float sizeX, float sizeY, float left, float top, int argb) {
    Matrix3x2fStack pose = g.pose();
    pose.pushMatrix();
    pose.translate(px, py);
    pose.rotate(turn);
    pose.scale(sizeX, sizeY);
    pose.translate(left, top);
    g.drawString(font, text, 0, 0, argb, false);
    pose.popMatrix();
  }

  private static float squash(float tiltDegrees) {
    return Math.max(LEAST_SQUASH, Math.abs((float) Math.cos(Math.toRadians(tiltDegrees))));
  }
}
