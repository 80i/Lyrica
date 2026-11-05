package net.splyrics.core.staff;

import net.labymod.laby3d.api.vertex.VertexConsumer;
import org.joml.Matrix4f;

/**
 * The card's shapes as vertices, in the quads of LabyMod's simple level geometry. Curved edges blend across one screen
 * pixel centred on the edge, the width of which the caller passes in; straight edges that lie on the pixel grid come out
 * hard from the same geometry. Only used on the render thread, so the working arrays are shared, and nothing is
 * allocated.
 */
final class CardShapes {

  /** Quarter-circle steps of a corner: on the roundest card, as close as it is drawn, this leaves under half a pixel. */
  private static final int STEPS = 12;
  private static final int MOST_POINTS = 4 * (STEPS + 1);
  /** Corners up to the plain card's take every other step, which at the largest leaves less than a tenth of a pixel. */
  private static final int COARSE_STRIDE = 2;
  private static final float[] SIN = new float[STEPS + 1];
  private static final float[] COS = new float[STEPS + 1];
  private static final int FULL_BRIGHT = 0xF000F0;

  private static final float[] OUTER_X = new float[MOST_POINTS];
  private static final float[] OUTER_Y = new float[MOST_POINTS];
  private static final float[] EDGE_X = new float[MOST_POINTS];
  private static final float[] EDGE_Y = new float[MOST_POINTS];
  private static final float[] INNER_X = new float[MOST_POINTS];
  private static final float[] INNER_Y = new float[MOST_POINTS];

  private static int stride = COARSE_STRIDE;
  private static int points = 4 * (STEPS / COARSE_STRIDE + 1);
  private static boolean image;
  private static float imageLeft;
  private static float imageTop;
  private static float imageWidth;

  static {
    for (int i = 0; i <= STEPS; i++) {
      double angle = Math.PI * 0.5 * i / STEPS;
      SIN[i] = (float) Math.sin(angle);
      COS[i] = (float) Math.cos(angle);
    }
  }

  private CardShapes() {
  }

  /**
   * The panel: a rounded rectangle whose outermost pixel is the rim's colour, so its shape holds on any background.
   *
   * @param fine whether its corners are rounder than the plain card's, and need the finer steps
   */
  static void panel(VertexConsumer out, Matrix4f pose, float left, float top, float right, float bottom, float radius,
      float pixel, float z, int bodyRgb, int rimRgb, float alpha, boolean fine) {
    image = false;
    steps(fine);
    float half = pixel * 0.5F;
    outline(left, top, right, bottom, radius, radius, radius, radius, half, OUTER_X, OUTER_Y);
    outline(left, top, right, bottom, radius, radius, radius, radius, -half, EDGE_X, EDGE_Y);
    outline(left, top, right, bottom, radius, radius, radius, radius, -half - pixel, INNER_X, INNER_Y);
    int body = argb(bodyRgb, alpha);
    int rim = argb(rimRgb, alpha);
    fan(out, pose, INNER_X, INNER_Y, z, body);
    ring(out, pose, INNER_X, INNER_Y, body, EDGE_X, EDGE_Y, rim, z);
    ring(out, pose, EDGE_X, EDGE_Y, rim, OUTER_X, OUTER_Y, argb(rimRgb, 0.0F), z);
  }

  /**
   * The material's texture, as a square with rounded left corners that follow the card's. Its right corners round off
   * only as far as the panel behind it goes away, because then nothing else gives it its shape.
   *
   * @param fine whether its corners are rounder than the plain card's, and need the finer steps
   */
  static void image(VertexConsumer out, Matrix4f pose, float left, float top, float right, float bottom,
      float leftRadius, float rightRadius, float pixel, float z, int rgb, float alpha, boolean fine) {
    image = true;
    steps(fine);
    imageLeft = left;
    imageTop = top;
    imageWidth = right - left;
    float half = pixel * 0.5F;
    outline(left, top, right, bottom, leftRadius, rightRadius, rightRadius, leftRadius, half, OUTER_X, OUTER_Y);
    outline(left, top, right, bottom, leftRadius, rightRadius, rightRadius, leftRadius, -half, EDGE_X, EDGE_Y);

    int color = argb(rgb, alpha);
    fan(out, pose, EDGE_X, EDGE_Y, z, color);
    ring(out, pose, EDGE_X, EDGE_Y, color, OUTER_X, OUTER_Y, argb(rgb, 0.0F), z);
    image = false;
  }

  /** The material's whole texture on a rectangle of any shape. */
  static void picture(VertexConsumer out, Matrix4f pose, float left, float top, float right, float bottom, float z, int rgb,
      float alpha) {
    int color = argb(rgb, alpha);
    out.addVertex(pose, left, top, z).setColor(color).setUv(0.0F, 0.0F).setPackedLight(FULL_BRIGHT);
    out.addVertex(pose, left, bottom, z).setColor(color).setUv(0.0F, 1.0F).setPackedLight(FULL_BRIGHT);
    out.addVertex(pose, right, bottom, z).setColor(color).setUv(1.0F, 1.0F).setPackedLight(FULL_BRIGHT);
    out.addVertex(pose, right, top, z).setColor(color).setUv(1.0F, 0.0F).setPackedLight(FULL_BRIGHT);
  }

  /** A hard rectangle, for something that lies on the pixel grid. */
  static void rectangle(VertexConsumer out, Matrix4f pose, float left, float top, float right, float bottom, float z,
      int rgb, float alpha) {
    quad(out, pose, left, top, left, bottom, right, bottom, right, top, z, argb(rgb, alpha));
  }

  private static void steps(boolean fine) {
    stride = fine ? 1 : COARSE_STRIDE;
    points = 4 * (STEPS / stride + 1);
  }

  /**
   * The outline of a rectangle moved outward by {@code by}. Points walk clockwise from the left end of the top-left
   * corner, and a corner without a radius stays square.
   */
  private static void outline(float left, float top, float right, float bottom, float topLeft, float topRight,
      float bottomRight, float bottomLeft, float by, float[] xs, float[] ys) {
    int at = 0;
    for (int corner = 0; corner < 4; corner++) {
      float radius = corner == 0 ? topLeft : corner == 1 ? topRight : corner == 2 ? bottomRight : bottomLeft;
      float moved = Math.max(0.0F, radius + by);
      float centerX = corner == 0 || corner == 3 ? left - by + moved : right + by - moved;
      float centerY = corner == 0 || corner == 1 ? top - by + moved : bottom + by - moved;
      for (int i = 0; i <= STEPS; i += stride) {
        float x;
        float y;
        if (corner == 0) {
          x = -COS[i];
          y = -SIN[i];
        } else if (corner == 1) {
          x = SIN[i];
          y = -COS[i];
        } else if (corner == 2) {
          x = COS[i];
          y = SIN[i];
        } else {
          x = -SIN[i];
          y = COS[i];
        }

        xs[at] = centerX + moved * x;
        ys[at] = centerY + moved * y;
        at++;
      }
    }
  }

  /** The inside of a convex outline, as quads that all share its first point. */
  private static void fan(VertexConsumer out, Matrix4f pose, float[] xs, float[] ys, float z, int color) {
    for (int i = 1; i + 2 < points; i += 2) {
      emit(out, pose, xs[0], ys[0], z, color);
      emit(out, pose, xs[i], ys[i], z, color);
      emit(out, pose, xs[i + 1], ys[i + 1], z, color);
      emit(out, pose, xs[i + 2], ys[i + 2], z, color);
    }
  }

  private static void ring(VertexConsumer out, Matrix4f pose, float[] fromX, float[] fromY, int fromColor, float[] toX,
      float[] toY, int toColor, float z) {
    for (int i = 0; i < points; i++) {
      int next = (i + 1) % points;
      emit(out, pose, fromX[i], fromY[i], z, fromColor);
      emit(out, pose, toX[i], toY[i], z, toColor);
      emit(out, pose, toX[next], toY[next], z, toColor);
      emit(out, pose, fromX[next], fromY[next], z, fromColor);
    }
  }

  private static void quad(VertexConsumer out, Matrix4f pose, float x0, float y0, float x1, float y1, float x2,
      float y2, float x3, float y3, float z, int color) {
    plain(out, pose, x0, y0, z, color);
    plain(out, pose, x1, y1, z, color);
    plain(out, pose, x2, y2, z, color);
    plain(out, pose, x3, y3, z, color);
  }

  private static void emit(VertexConsumer out, Matrix4f pose, float x, float y, float z, int color) {
    if (image) {
      float u = clamp((x - imageLeft) / imageWidth);
      float v = clamp((y - imageTop) / imageWidth);
      out.addVertex(pose, x, y, z).setColor(color).setUv(u, v).setPackedLight(FULL_BRIGHT);
    } else {
      plain(out, pose, x, y, z, color);
    }
  }

  private static void plain(VertexConsumer out, Matrix4f pose, float x, float y, float z, int color) {
    out.addVertex(pose, x, y, z).setColor(color).setUv(0.5F, 0.5F).setPackedLight(FULL_BRIGHT);
  }

  private static float clamp(float value) {
    return value < 0.0F ? 0.0F : Math.min(value, 1.0F);
  }

  private static int argb(int rgb, float alpha) {
    int a = Math.round(clamp(alpha) * 255.0F);
    return a << 24 | rgb & 0xFFFFFF;
  }
}
