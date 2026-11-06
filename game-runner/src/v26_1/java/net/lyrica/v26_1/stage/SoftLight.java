package net.lyrica.v26_1.stage;

import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix4f;

/** Meshes with soft edges: halos and twinkles of added light, the shadow on the ground, and solid discs. */
public final class SoftLight {

  /** How many ground heights a shadow takes along its length. */
  public static final int SHADOW_COLUMNS = 13;

  private static final int SHADOW_ROWS = 7;
  private static final int CORNER_STEPS = 1;
  private static final int PERIMETER = 4 * (CORNER_STEPS + 1);
  private static final float[] RING_AT = {0.0F, 0.3F, 0.62F, 1.0F};
  private static final float HALO_FALLOFF = 3.5F;
  // Close to the letter the light falls fast, then trails away, which is what makes it read as light and not as a stroke.
  private static final float[] CONTOUR_AT = {0.0F, 0.16F, 0.45F, 1.0F};
  private static final float[] CONTOUR_LIGHT = {1.0F, 0.56F, 0.17F, 0.0F};
  private static final float POOL_WAIST = 0.5F;
  private static final float POOL_WAIST_LIGHT = 0.42F;

  private static final int DISC_STEPS = 16;
  private static final float TWINKLE_WAIST = 0.2F;
  private static final float TWINKLE_WAIST_LIGHT = 0.4F;
  private static final int TWINKLE_POINTS = 8;

  private static final float[] PERIMETER_X = new float[PERIMETER];
  private static final float[] PERIMETER_Y = new float[PERIMETER];
  private static final float[] CORNER_X = new float[PERIMETER];
  private static final float[] CORNER_Y = new float[PERIMETER];
  private static final float[] RING_LIGHT = new float[RING_AT.length];
  private static final float[] DISC_X = new float[DISC_STEPS + 1];
  private static final float[] DISC_Y = new float[DISC_STEPS + 1];
  private static final float[] SHADOW_ALONG = new float[SHADOW_COLUMNS];
  private static final float[] SHADOW_ACROSS = new float[SHADOW_ROWS];
  private static final float[] TWINKLE_X = new float[TWINKLE_POINTS];
  private static final float[] TWINKLE_Y = new float[TWINKLE_POINTS];

  // Only ever used on the render thread, within one call.
  private static final float[] COLUMN_X = new float[SHADOW_COLUMNS];
  private static final float[] COLUMN_Z = new float[SHADOW_COLUMNS];
  private static final float[] COLUMN_SIN = new float[SHADOW_COLUMNS];
  private static final float[] COLUMN_COS = new float[SHADOW_COLUMNS];
  private static final float[] COLUMN_Y = new float[SHADOW_COLUMNS];
  private static final float[] COLUMN_LIGHT = new float[SHADOW_COLUMNS];

  static {
    float[] signX = {1.0F, -1.0F, -1.0F, 1.0F};
    float[] signY = {1.0F, 1.0F, -1.0F, -1.0F};
    for (int corner = 0; corner < 4; corner++) {
      for (int step = 0; step <= CORNER_STEPS; step++) {
        int i = corner * (CORNER_STEPS + 1) + step;
        double angle = (corner + step / (double) CORNER_STEPS) * Math.PI * 0.5;
        PERIMETER_X[i] = (float) Math.cos(angle);
        PERIMETER_Y[i] = (float) Math.sin(angle);
        CORNER_X[i] = signX[corner];
        CORNER_Y[i] = signY[corner];
      }
    }

    // Lifted so the last ring is truly zero: added light that stopped short of zero would show the mesh's outline.
    double tail = Math.exp(-HALO_FALLOFF);
    for (int i = 0; i < RING_AT.length; i++) {
      double gauss = Math.exp(-HALO_FALLOFF * RING_AT[i] * RING_AT[i]);
      RING_LIGHT[i] = (float) ((gauss - tail) / (1.0 - tail));
    }

    for (int i = 0; i <= DISC_STEPS; i++) {
      double angle = i * Math.PI * 2.0 / DISC_STEPS;
      DISC_X[i] = (float) Math.cos(angle);
      DISC_Y[i] = (float) Math.sin(angle);
    }

    for (int i = 0; i < SHADOW_COLUMNS; i++) {
      double t = columnAt(i);
      double fourth = t * t * t * t;
      // Flat along most of the line, because the letters above are evenly heavy; only the ends fall away.
      SHADOW_ALONG[i] = (float) (Math.exp(-2.5 * fourth) * (1.0 - fourth));
    }

    for (int i = 0; i < SHADOW_ROWS; i++) {
      double v = rowAt(i);
      SHADOW_ACROSS[i] = (float) (Math.exp(-2.5 * v * v) * (1.0 - v * v));
    }

    for (int i = 0; i < TWINKLE_POINTS; i++) {
      double angle = i * Math.PI * 2.0 / TWINKLE_POINTS;
      float reach = (i & 1) == 0 ? 1.0F : TWINKLE_WAIST;
      TWINKLE_X[i] = (float) Math.cos(angle) * reach;
      TWINKLE_Y[i] = (float) Math.sin(angle) * reach;
    }
  }

  private SoftLight() {
  }

  /** Where a shadow column sits along the line, -1 to 1. */
  static float columnAt(int column) {
    return column * 2.0F / (SHADOW_COLUMNS - 1) - 1.0F;
  }

  private static float rowAt(int row) {
    return row * 2.0F / (SHADOW_ROWS - 1) - 1.0F;
  }

  /**
   * A rounded rectangle of light, full inside the rectangle and falling off like a gaussian to nothing at {@code
   * spread} beyond it, lying in the matrix's z = 0 plane, whose translation is relative to the camera. For {@code
   * RenderTypes.dragonRays()}, which culls: the triangles go round the way the camera sees as their front.
   *
   * @param peak strength inside the rectangle, 0 to 1
   */
  public static void halo(VertexConsumer out, Matrix4f matrix, float halfWidth, float halfHeight, float spread, int rgb,
      float peak) {
    int color = rgb & 0xFFFFFF;
    int core = color | alphaBits(peak * RING_LIGHT[0]);
    boolean forward = seenFromFront(matrix);
    triangle(out, matrix, forward, -halfWidth, -halfHeight, core, halfWidth, -halfHeight, core, halfWidth, halfHeight, core);
    triangle(out, matrix, forward, -halfWidth, -halfHeight, core, halfWidth, halfHeight, core, -halfWidth, halfHeight, core);

    for (int ring = 0; ring + 1 < RING_AT.length; ring++) {
      float inner = RING_AT[ring] * spread;
      float outer = RING_AT[ring + 1] * spread;
      int innerColor = color | alphaBits(peak * RING_LIGHT[ring]);
      int outerColor = color | alphaBits(peak * RING_LIGHT[ring + 1]);
      for (int i = 0; i < PERIMETER; i++) {
        int j = (i + 1) % PERIMETER;
        float ax = CORNER_X[i] * halfWidth + PERIMETER_X[i] * inner;
        float ay = CORNER_Y[i] * halfHeight + PERIMETER_Y[i] * inner;
        float bx = CORNER_X[i] * halfWidth + PERIMETER_X[i] * outer;
        float by = CORNER_Y[i] * halfHeight + PERIMETER_Y[i] * outer;
        float cx = CORNER_X[j] * halfWidth + PERIMETER_X[j] * outer;
        float cy = CORNER_Y[j] * halfHeight + PERIMETER_Y[j] * outer;
        float dx = CORNER_X[j] * halfWidth + PERIMETER_X[j] * inner;
        float dy = CORNER_Y[j] * halfHeight + PERIMETER_Y[j] * inner;
        triangle(out, matrix, forward, ax, ay, innerColor, bx, by, outerColor, cx, cy, outerColor);
        triangle(out, matrix, forward, ax, ay, innerColor, cx, cy, outerColor, dx, dy, innerColor);
      }
    }
  }

  /**
   * Light that hugs a letter: from the letter's own outline it falls off to nothing at {@code reach}, with round corners
   * and into the letter's bowls, so it has the letter's shape and no box around it. For {@code RenderTypes.dragonRays()}.
   *
   * @param matrix takes mesh pixels to camera-relative world space
   * @param z      the plane it lies in, in the matrix's z
   * @param reach  how far it goes beyond the outline, in mesh pixels
   * @param peak   its strength along the outline, 0 to 1
   * @return how many vertices it wrote
   */
  public static int contour(VertexConsumer out, Matrix4f matrix, GlyphMesh mesh, float z, float reach, int rgb,
      float peak) {
    int color = rgb & 0xFFFFFF;
    boolean forward = seenFromFront(matrix);
    float[] t = mesh.triangles();
    int core = color | alphaBits(peak);
    for (int i = 0; i + 5 < t.length; i += 6) {
      triangleAt(out, matrix, forward, z, t[i], t[i + 1], core, t[i + 2], t[i + 3], core, t[i + 4], t[i + 5], core);
    }

    int written = t.length / 2;
    float[] s = mesh.sides();
    int ringAt = 0;
    for (int at = 0; at < s.length; at += GlyphMesh.SIDE_STRIDE) {
      float x0 = s[at];
      float y0 = s[at + 1];
      float nx0 = s[at + 2];
      float ny0 = s[at + 3];
      float x1 = s[at + 4];
      float y1 = s[at + 5];
      float nx1 = s[at + 6];
      float ny1 = s[at + 7];
      // Rings follow each other in the array, and a ring ends where it began.
      boolean closes = x1 == s[ringAt] && y1 == s[ringAt + 1];
      int next = closes ? ringAt : at + GlyphMesh.SIDE_STRIDE;
      float nextNx = s[next + 2];
      float nextNy = s[next + 3];
      boolean bends = nextNx != nx1 || nextNy != ny1;
      // Only an outer corner leaves a gap to round off; an inner one would just add light where the edges overlap.
      boolean outer = bends && (x1 - x0) * nextNx + (y1 - y0) * nextNy > 0.0F;
      for (int ring = 0; ring + 1 < CONTOUR_AT.length; ring++) {
        float in = CONTOUR_AT[ring] * reach;
        float on = CONTOUR_AT[ring + 1] * reach;
        int inColor = color | alphaBits(peak * CONTOUR_LIGHT[ring]);
        int onColor = color | alphaBits(peak * CONTOUR_LIGHT[ring + 1]);
        float ax = x0 + nx0 * in;
        float ay = y0 + ny0 * in;
        float bx = x0 + nx0 * on;
        float by = y0 + ny0 * on;
        float cx = x1 + nx1 * on;
        float cy = y1 + ny1 * on;
        float dx = x1 + nx1 * in;
        float dy = y1 + ny1 * in;
        triangleAt(out, matrix, forward, z, ax, ay, inColor, bx, by, onColor, cx, cy, onColor);
        triangleAt(out, matrix, forward, z, ax, ay, inColor, cx, cy, onColor, dx, dy, inColor);
        written += 6;
        if (outer) {
          float ex = x1 + nextNx * on;
          float ey = y1 + nextNy * on;
          float fx = x1 + nextNx * in;
          float fy = y1 + nextNy * in;
          triangleAt(out, matrix, forward, z, dx, dy, inColor, cx, cy, onColor, ex, ey, onColor);
          triangleAt(out, matrix, forward, z, dx, dy, inColor, ex, ey, onColor, fx, fy, inColor);
          written += 6;
        }
      }

      if (closes) {
        ringAt = at + GlyphMesh.SIDE_STRIDE;
      }
    }

    return written;
  }

  /**
   * A round pool of light with nothing inside it but a soft fall to its edge. Those of neighbouring letters melt into one
   * another, which leaves a word in a haze rounded at both ends. For {@code RenderTypes.dragonRays()}.
   *
   * @return how many vertices it wrote
   */
  public static int pool(VertexConsumer out, Matrix4f matrix, float x, float y, float z, float radiusX, float radiusY,
      int rgb, float peak) {
    int color = rgb & 0xFFFFFF;
    boolean forward = seenFromFront(matrix);
    int middle = color | alphaBits(peak);
    int waist = color | alphaBits(peak * POOL_WAIST_LIGHT);
    int rim = color;
    for (int i = 0; i < DISC_STEPS; i++) {
      float cosA = DISC_X[i];
      float sinA = DISC_Y[i];
      float cosB = DISC_X[i + 1];
      float sinB = DISC_Y[i + 1];
      float ax = x + cosA * radiusX * POOL_WAIST;
      float ay = y + sinA * radiusY * POOL_WAIST;
      float bx = x + cosB * radiusX * POOL_WAIST;
      float by = y + sinB * radiusY * POOL_WAIST;
      triangleAt(out, matrix, forward, z, x, y, middle, ax, ay, waist, bx, by, waist);
      float cx = x + cosA * radiusX;
      float cy = y + sinA * radiusY;
      float dx = x + cosB * radiusX;
      float dy = y + sinB * radiusY;
      triangleAt(out, matrix, forward, z, ax, ay, waist, cx, cy, rim, dx, dy, rim);
      triangleAt(out, matrix, forward, z, ax, ay, waist, dx, dy, rim, bx, by, waist);
    }

    return DISC_STEPS * 9;
  }

  private static void triangleAt(VertexConsumer out, Matrix4f matrix, boolean forward, float z, float ax, float ay,
      int colorA, float bx, float by, int colorB, float cx, float cy, int colorC) {
    vertex(out, matrix, ax, ay, z, colorA);
    if (forward) {
      vertex(out, matrix, bx, by, z, colorB);
      vertex(out, matrix, cx, cy, z, colorC);
    } else {
      vertex(out, matrix, cx, cy, z, colorC);
      vertex(out, matrix, bx, by, z, colorB);
    }
  }

  /**
   * A four-point star of light around (x, y, z), its long points fading out. For {@code RenderTypes.dragonRays()}.
   *
   * @param strength 0 to 1
   */
  public static void twinkle(VertexConsumer out, Matrix4f matrix, float x, float y, float z, float size, int rgb,
      float strength) {
    int color = rgb & 0xFFFFFF;
    int middle = color | alphaBits(strength);
    int waist = color | alphaBits(strength * TWINKLE_WAIST_LIGHT);
    for (int i = 0; i < TWINKLE_POINTS; i++) {
      int j = (i + 1) % TWINKLE_POINTS;
      int colorI = (i & 1) == 0 ? color : waist;
      int colorJ = (j & 1) == 0 ? color : waist;
      float ix = x + TWINKLE_X[i] * size;
      float iy = y + TWINKLE_Y[i] * size;
      float jx = x + TWINKLE_X[j] * size;
      float jy = y + TWINKLE_Y[j] * size;
      vertex(out, matrix, x, y, z, middle);
      vertex(out, matrix, ix, iy, z, colorI);
      vertex(out, matrix, jx, jy, z, colorJ);
      vertex(out, matrix, x, y, z, middle);
      vertex(out, matrix, jx, jy, z, colorJ);
      vertex(out, matrix, ix, iy, z, colorI);
    }
  }

  /**
   * A soft dark blob lying flat, following the curve of the line above it. The matrix has x along the line, y up and z
   * toward the viewer, in blocks.
   *
   * <p>For {@code RenderTypes.debugQuads()} rather than {@code textBackground()}: the text background's shader discards
   * everything under 10% alpha, which would leave a hard line where a shadow should melt away. That type has no fog,
   * so the caller fades a distant shadow out itself.
   *
   * @param halfLength along the line
   * @param halfWidth  across the line
   * @param curve      how much the line bends, in radians per block; 0 for straight
   * @param heights    ground height per column relative to the plane, {@link Float#NaN} where there is no usable
   *                   ground; or null for a flat plane
   * @param peak       darkness at the middle, 0 to 1
   */
  public static void groundShadow(VertexConsumer out, Matrix4f matrix, float halfLength, float halfWidth, float curve,
      float[] heights, float peak) {
    for (int i = 0; i < SHADOW_COLUMNS; i++) {
      float along = columnAt(i) * halfLength;
      float turn = along * curve;
      float sin = (float) Math.sin(turn);
      float cos = (float) Math.cos(turn);
      COLUMN_SIN[i] = sin;
      COLUMN_COS[i] = cos;
      COLUMN_X[i] = curve == 0.0F ? along : sin / curve;
      COLUMN_Z[i] = curve == 0.0F ? 0.0F : (1.0F - cos) / curve;
      float height = heights == null ? 0.0F : heights[i];
      boolean usable = !Float.isNaN(height);
      COLUMN_Y[i] = usable ? height : 0.0F;
      COLUMN_LIGHT[i] = usable ? SHADOW_ALONG[i] : 0.0F;
    }

    for (int i = 0; i + 1 < SHADOW_COLUMNS; i++) {
      for (int row = 0; row + 1 < SHADOW_ROWS; row++) {
        shadowVertex(out, matrix, i, row, halfWidth, peak);
        shadowVertex(out, matrix, i, row + 1, halfWidth, peak);
        shadowVertex(out, matrix, i + 1, row + 1, halfWidth, peak);
        shadowVertex(out, matrix, i + 1, row, halfWidth, peak);
      }
    }
  }

  /**
   * A filled disc for {@code RenderTypes.textBackground()}, facing the same way as text drawn with this matrix. That
   * type takes quads, so each slice is a quad with one corner doubled.
   */
  public static void disc(VertexConsumer out, Matrix4f matrix, float x, float y, float z, float radius, int argb,
      int light) {
    for (int i = 0; i < DISC_STEPS; i++) {
      float ax = x + DISC_X[i] * radius;
      float ay = y + DISC_Y[i] * radius;
      float bx = x + DISC_X[i + 1] * radius;
      float by = y + DISC_Y[i + 1] * radius;
      lit(out, matrix, x, y, z, argb, light);
      lit(out, matrix, bx, by, z, argb, light);
      lit(out, matrix, ax, ay, z, argb, light);
      lit(out, matrix, ax, ay, z, argb, light);
    }
  }

  private static void shadowVertex(VertexConsumer out, Matrix4f matrix, int column, int row, float halfWidth,
      float peak) {
    float across = rowAt(row) * halfWidth;
    float x = COLUMN_X[column] - across * COLUMN_SIN[column];
    float z = COLUMN_Z[column] + across * COLUMN_COS[column];
    out.addVertex(matrix, x, COLUMN_Y[column], z).setColor(alphaBits(peak * COLUMN_LIGHT[column] * SHADOW_ACROSS[row]));
  }

  /**
   * Whether the camera, at the origin of the matrix's output, is on the side from which counter-clockwise in the
   * matrix's x and y looks counter-clockwise. Worked out from the output axes, so a mirrored matrix comes out right.
   */
  private static boolean seenFromFront(Matrix4f matrix) {
    float normalX = matrix.m01() * matrix.m12() - matrix.m02() * matrix.m11();
    float normalY = matrix.m02() * matrix.m10() - matrix.m00() * matrix.m12();
    float normalZ = matrix.m00() * matrix.m11() - matrix.m01() * matrix.m10();
    return normalX * matrix.m30() + normalY * matrix.m31() + normalZ * matrix.m32() < 0.0F;
  }

  private static void triangle(VertexConsumer out, Matrix4f matrix, boolean forward, float ax, float ay, int colorA,
      float bx, float by, int colorB, float cx, float cy, int colorC) {
    vertex(out, matrix, ax, ay, 0.0F, colorA);
    if (forward) {
      vertex(out, matrix, bx, by, 0.0F, colorB);
      vertex(out, matrix, cx, cy, 0.0F, colorC);
    } else {
      vertex(out, matrix, cx, cy, 0.0F, colorC);
      vertex(out, matrix, bx, by, 0.0F, colorB);
    }
  }

  private static void vertex(VertexConsumer out, Matrix4f matrix, float x, float y, float z, int argb) {
    out.addVertex(matrix, x, y, z).setColor(argb);
  }

  private static void lit(VertexConsumer out, Matrix4f matrix, float x, float y, float z, int argb, int light) {
    out.addVertex(matrix, x, y, z).setColor(argb).setLight(light);
  }

  private static int alphaBits(float amount) {
    return Math.round(Math.max(0.0F, Math.min(1.0F, amount)) * 255.0F) << 24;
  }
}
