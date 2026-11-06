package net.lyrica.v26_1.stage;

import org.joml.Matrix4f;

/**
 * Turns one glyph mesh into solid geometry: a front face, a darker back face, side walls, and a thin soft rim that fades
 * to nothing about a pixel beyond every edge the viewer can see against something else.
 *
 * <p>The matrix takes mesh pixels, with z in the letter's own depth, to camera-relative world space, and flips y, so
 * +z is the side the letter reads from. Which faces and walls show is worked out on the CPU from where the
 * eye is in mesh space, so nothing that faces away ever reaches the buffers. The rims cannot be left to the depth
 * buffer alone, so they go in their own sink, drawn after every solid surface; they are written far end first, as the
 * near plate's rim has to lie over the far ones.
 */
final class LetterMesher {

  // A fixed light from above and a little to one side. World axes.
  private static final float LIGHT_X = -0.36F;
  private static final float LIGHT_Y = 0.80F;
  private static final float LIGHT_Z = 0.48F;

  // The face takes hardly any of it, or a word's own colour would change with the way it happens to look.
  private static final float FRONT_BASE = 0.93F;
  private static final float FRONT_SPAN = 0.10F;
  private static final float WALL_BASE = 0.95F;
  private static final float WALL_SPAN = 0.55F;
  private static final float WALL_FAR_END = 0.72F;
  private static final float BACK_FACE = 0.82F;

  /** A rim seen at a slant is foreshortened, so it grows; this is how far that goes. */
  private static final float LEAST_RIM_SLANT = 0.35F;

  // A letter breaking, in mesh pixels. A letter is about 10 of them tall.
  private static final float SHARD_FALL = 26.0F;
  private static final float SHARD_SHRINK = 0.55F;
  private static final float SHARD_THIN = 0.5F;
  private static final float SHARD_LEAST_SPEED = 5.0F;
  private static final float SHARD_MORE_SPEED = 13.0F;
  private static final float SHARD_SCATTER = 1.3F;
  private static final float SHARD_TOWARD_VIEWER = 14.0F;
  private static final float SHARD_LEAST_SPIN = 3.0F;
  private static final float SHARD_MORE_SPIN = 9.0F;
  private static final float SHARD_LEAST_SHADE = 0.86F;
  private static final float SHARD_MORE_SHADE = 0.3F;

  private final Matrix4f inverse = new Matrix4f();
  private final float[] shardFront = new float[9];
  private final float[] shardBack = new float[9];

  int vertices;

  private GeometrySink body;
  private GeometrySink mask;
  private GeometrySink rim;
  private int light;

  private float ax;
  private float ay;
  private float az;
  private float bx;
  private float by;
  private float bz;
  private float cx;
  private float cy;
  private float cz;
  private float tx;
  private float ty;
  private float tz;

  private float eyeX;
  private float eyeY;
  private float eyeZ;

  private float lightAlongX;
  private float lightAlongY;
  private float lightAlongZ;

  private int rgb;
  private int alphaBits;
  private float side;

  /**
   * Where the next letters go.
   *
   * @param mask takes the solid surfaces' depth first when the letter is see-through; null when it is opaque
   */
  void target(GeometrySink body, GeometrySink mask, GeometrySink rim, int light) {
    this.body = body;
    this.mask = mask;
    this.rim = rim;
    this.light = light;
  }

  /**
   * A whole letter.
   *
   * @param front    the front face's z; {@code back} equal to it means a flat letter with no walls
   * @param sideShade how much of the colour the sides keep
   * @param fringe   the rim's width in mesh pixels when seen straight on; 0 for none
   */
  void letter(Matrix4f matrix, GlyphMesh mesh, float front, float back, int rgb, int alpha, float sideShade,
      float fringe) {
    this.useMatrix(matrix);
    this.rgb = rgb;
    this.alphaBits = alpha << 24;
    this.side = sideShade;

    boolean seesFront = this.eyeZ >= front;
    boolean seesBack = !seesFront && this.eyeZ <= back;
    int frontColor = this.alphaBits | shaded(rgb, FRONT_BASE + FRONT_SPAN * this.lightAlongZ);
    int backColor = this.alphaBits
        | shaded(rgb, sideShade * (WALL_BASE - WALL_SPAN * this.lightAlongZ) * BACK_FACE);
    if (seesFront) {
      this.faces(mesh, front, frontColor, true);
    } else if (seesBack) {
      this.faces(mesh, back, backColor, false);
    }

    float width = fringe;
    if (width > 0.0F) {
      // Seen at a slant, the rim lies foreshortened in its plane.
      float dx = this.eyeX;
      float dy = this.eyeY;
      float distance = (float) Math.sqrt(dx * dx + dy * dy + this.eyeZ * this.eyeZ);
      float slant = distance > 0.0F ? Math.abs(this.eyeZ) / distance : 1.0F;
      width /= Math.max(LEAST_RIM_SLANT, slant);
    }

    if (front > back) {
      this.walls(mesh, front, back, seesFront, seesBack, width);
    }

    if (width > 0.0F) {
      if (seesFront) {
        this.wholeRim(mesh, front, frontColor, true, width);
      } else if (seesBack) {
        this.wholeRim(mesh, back, backColor, false, width);
      }
    }
  }

  /**
   * The letter in pieces: each triangle of its face becomes a little solid chunk of its own thickness, thrown outward from
   * the letter's middle, turning about itself and falling as it goes. The chunks are what the letter was made of, so at
   * the first moment they still read as the whole letter.
   *
   * @param thickness half a chunk's depth, in the matrix's z
   * @param gone      how far the break has come, 0 to 1
   */
  void shards(Matrix4f matrix, GlyphMesh mesh, float thickness, int rgb, int alpha, float sideShade, float gone,
      long seed) {
    this.useMatrix(matrix);
    this.rgb = rgb;
    this.alphaBits = alpha << 24;
    this.side = sideShade;
    float[] t = mesh.triangles();
    float middleX = (mesh.minX() + mesh.maxX()) * 0.5F;
    float middleY = (mesh.minY() + mesh.maxY()) * 0.5F;
    float burst = 1.0F - (1.0F - gone) * (1.0F - gone) * (1.0F - gone);
    float fall = gone * gone * SHARD_FALL;
    float shrink = 1.0F - gone * gone * SHARD_SHRINK;
    float depth = thickness * (1.0F - gone * SHARD_THIN);
    for (int i = 0; i + 5 < t.length; i += 6) {
      float cx = (t[i] + t[i + 2] + t[i + 4]) / 3.0F;
      float cy = (t[i + 1] + t[i + 3] + t[i + 5]) / 3.0F;
      long at = seed * 0x9E3779B97F4A7C15L + i;
      float wobbleA = chance(at);
      float wobbleB = chance(at + 1L);
      float wobbleC = chance(at + 2L);
      float awayX = cx - middleX;
      float awayY = cy - middleY;
      float away = Math.max(0.2F, (float) Math.sqrt(awayX * awayX + awayY * awayY));
      float speed = SHARD_LEAST_SPEED + wobbleA * SHARD_MORE_SPEED;
      float moveX = (awayX / away + (wobbleB - 0.5F) * SHARD_SCATTER) * speed * burst;
      float moveY = (awayY / away + (wobbleC - 0.5F) * SHARD_SCATTER) * speed * burst + fall * (0.6F + wobbleA);
      float moveZ = (wobbleB - 0.35F) * SHARD_TOWARD_VIEWER * burst;
      float spin = gone * (SHARD_LEAST_SPIN + wobbleC * SHARD_MORE_SPIN) * (wobbleA < 0.5F ? -1.0F : 1.0F);
      float sinZ = (float) Math.sin(spin);
      float cosZ = (float) Math.cos(spin);
      float sinX = (float) Math.sin(spin * (0.6F + wobbleB));
      float cosX = (float) Math.cos(spin * (0.6F + wobbleB));
      float shade = SHARD_LEAST_SHADE + wobbleA * SHARD_MORE_SHADE;

      for (int corner = 0; corner < 3; corner++) {
        float dx = (t[i + corner * 2] - cx) * shrink;
        float dy = (t[i + corner * 2 + 1] - cy) * shrink;
        float x = dx * cosZ - dy * sinZ;
        float y = dx * sinZ + dy * cosZ;
        float px = cx + moveX + x;
        float frontY = cy + moveY + y * cosX - depth * sinX;
        float frontZ = moveZ + y * sinX + depth * cosX;
        float backY = cy + moveY + y * cosX + depth * sinX;
        float backZ = moveZ + y * sinX - depth * cosX;
        this.shardFront[corner * 3] = this.ax * px + this.bx * frontY + this.cx * frontZ + this.tx;
        this.shardFront[corner * 3 + 1] = this.ay * px + this.by * frontY + this.cy * frontZ + this.ty;
        this.shardFront[corner * 3 + 2] = this.az * px + this.bz * frontY + this.cz * frontZ + this.tz;
        this.shardBack[corner * 3] = this.ax * px + this.bx * backY + this.cx * backZ + this.tx;
        this.shardBack[corner * 3 + 1] = this.ay * px + this.by * backY + this.cy * backZ + this.ty;
        this.shardBack[corner * 3 + 2] = this.az * px + this.bz * backY + this.cz * backZ + this.tz;
      }

      this.writeShard(shade);
    }
  }

  /** A chunk: its two faces and three walls, each going round so that it is seen from outside. */
  private void writeShard(float shade) {
    float[] f = this.shardFront;
    float[] b = this.shardBack;
    int faceColor = this.alphaBits | shaded(this.rgb, shade);
    int backColor = this.alphaBits | shaded(this.rgb, shade * this.side * BACK_FACE);
    int wallColor = this.alphaBits | shaded(this.rgb, shade * this.side);
    GeometrySink body = this.body;
    GeometrySink mask = this.mask;
    int light = this.light;
    body.triangle(f[0], f[1], f[2], f[6], f[7], f[8], f[3], f[4], f[5], faceColor, light);
    body.triangle(b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7], b[8], backColor, light);
    if (mask != null) {
      mask.triangle(f[0], f[1], f[2], f[6], f[7], f[8], f[3], f[4], f[5], faceColor, light);
      mask.triangle(b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7], b[8], backColor, light);
    }

    for (int edge = 0; edge < 3; edge++) {
      int p = edge * 3;
      int q = (edge + 1) % 3 * 3;
      body.quad(f[p], f[p + 1], f[p + 2], wallColor, f[q], f[q + 1], f[q + 2], wallColor, b[q], b[q + 1], b[q + 2],
          wallColor, b[p], b[p + 1], b[p + 2], wallColor, light);
      if (mask != null) {
        mask.quad(f[p], f[p + 1], f[p + 2], wallColor, f[q], f[q + 1], f[q + 2], wallColor, b[q], b[q + 1], b[q + 2],
            wallColor, b[p], b[p + 1], b[p + 2], wallColor, light);
      }
    }

    this.vertices += mask != null ? 40 : 20;
  }

  private static float chance(long seed) {
    long mixed = (seed ^ seed >>> 30) * 0xBF58476D1CE4E5B9L;
    mixed = (mixed ^ mixed >>> 27) * 0x94D049BB133111EBL;
    return ((mixed ^ mixed >>> 31) >>> 40) / (float) (1L << 24);
  }

  /** A flat plate of one colour behind the front face, for an outline. Only seen from the front. */
  void plate(Matrix4f matrix, GlyphMesh mesh, float z, int argb, float fringe) {
    this.useMatrix(matrix);
    if (this.eyeZ >= z) {
      this.faces(mesh, z, argb, true);
      if (fringe > 0.0F) {
        this.wholeRim(mesh, z, argb, true, fringe);
      }
    }
  }

  /** Just a face, into a sink of its own, for the faint copy that shows through walls. */
  void ghost(Matrix4f matrix, GlyphMesh mesh, float z, int argb, GeometrySink to) {
    GeometrySink bodyThen = this.body;
    GeometrySink maskThen = this.mask;
    this.body = to;
    this.mask = null;
    this.useMatrix(matrix);
    this.faces(mesh, z, argb, this.eyeZ >= z);
    this.body = bodyThen;
    this.mask = maskThen;
  }

  private void useMatrix(Matrix4f m) {
    this.ax = m.m00();
    this.ay = m.m01();
    this.az = m.m02();
    this.bx = m.m10();
    this.by = m.m11();
    this.bz = m.m12();
    this.cx = m.m20();
    this.cy = m.m21();
    this.cz = m.m22();
    this.tx = m.m30();
    this.ty = m.m31();
    this.tz = m.m32();
    m.invertAffine(this.inverse);
    this.eyeX = this.inverse.m30();
    this.eyeY = this.inverse.m31();
    this.eyeZ = this.inverse.m32();

    this.lightAlongX = along(this.ax, this.ay, this.az);
    this.lightAlongY = along(this.bx, this.by, this.bz);
    this.lightAlongZ = along(this.cx, this.cy, this.cz);
  }

  private static float along(float x, float y, float z) {
    float length = (float) Math.sqrt(x * x + y * y + z * z);
    return length <= 0.0F ? 0.0F : (x * LIGHT_X + y * LIGHT_Y + z * LIGHT_Z) / length;
  }

  private void faces(GlyphMesh mesh, float z, int color, boolean forward) {
    float[] t = mesh.triangles();
    float zx = this.cx * z + this.tx;
    float zy = this.cy * z + this.ty;
    float zz = this.cz * z + this.tz;
    float ax = this.ax;
    float ay = this.ay;
    float az = this.az;
    float bx = this.bx;
    float by = this.by;
    float bz = this.bz;
    GeometrySink body = this.body;
    GeometrySink mask = this.mask;
    int light = this.light;
    for (int i = 0; i + 5 < t.length; i += 6) {
      float x0 = t[i];
      float y0 = t[i + 1];
      // The mesh's triangles run clockwise seen from +z, once its y is pointing down on screen as it does here.
      float x1 = t[forward ? i + 4 : i + 2];
      float y1 = t[forward ? i + 5 : i + 3];
      float x2 = t[forward ? i + 2 : i + 4];
      float y2 = t[forward ? i + 3 : i + 5];
      float px0 = ax * x0 + bx * y0 + zx;
      float py0 = ay * x0 + by * y0 + zy;
      float pz0 = az * x0 + bz * y0 + zz;
      float px1 = ax * x1 + bx * y1 + zx;
      float py1 = ay * x1 + by * y1 + zy;
      float pz1 = az * x1 + bz * y1 + zz;
      float px2 = ax * x2 + bx * y2 + zx;
      float py2 = ay * x2 + by * y2 + zy;
      float pz2 = az * x2 + bz * y2 + zz;
      body.triangle(px0, py0, pz0, px1, py1, pz1, px2, py2, pz2, color, light);
      if (mask != null) {
        mask.triangle(px0, py0, pz0, px1, py1, pz1, px2, py2, pz2, color, light);
      }
    }

    this.vertices += t.length / 6 * (mask != null ? 8 : 4);
  }

  private void walls(GlyphMesh mesh, float front, float back, boolean seesFront, boolean seesBack, float width) {
    float[] s = mesh.sides();
    float[] r = mesh.rim();
    float frontX = this.cx * front;
    float frontY = this.cy * front;
    float frontZ = this.cz * front;
    float backX = this.cx * back;
    float backY = this.cy * back;
    float backZ = this.cz * back;
    float eyeX = this.eyeX;
    float eyeY = this.eyeY;
    boolean rims = width > 0.0F;
    GeometrySink body = this.body;
    GeometrySink mask = this.mask;
    int light = this.light;

    int ringAt = -1;
    boolean firstSeen = false;
    boolean lastSeen = false;
    float lastNx = 0.0F;
    float lastNy = 0.0F;
    for (int at = 0; at < s.length; at += GlyphMesh.SIDE_STRIDE) {
      float x0 = s[at];
      float y0 = s[at + 1];
      float nx0 = s[at + 2];
      float ny0 = s[at + 3];
      float x1 = s[at + 4];
      float y1 = s[at + 5];
      float nx1 = s[at + 6];
      float ny1 = s[at + 7];
      boolean seen = (eyeX - x0) * (y1 - y0) - (eyeY - y0) * (x1 - x0) > 0.0F;

      if (ringAt < 0) {
        ringAt = at;
        firstSeen = seen;
      } else if (rims && seen != lastSeen) {
        // In the colour of the wall that shows, not of the one turning away.
        this.fin(x0, y0, r[at + 2], r[at + 3], seen ? nx0 : lastNx, seen ? ny0 : lastNy, front, back, width);
      }

      lastSeen = seen;
      lastNx = nx1;
      lastNy = ny1;

      if (seen) {
        float ox0 = this.ax * x0 + this.bx * y0 + this.tx;
        float oy0 = this.ay * x0 + this.by * y0 + this.ty;
        float oz0 = this.az * x0 + this.bz * y0 + this.tz;
        float ox1 = this.ax * x1 + this.bx * y1 + this.tx;
        float oy1 = this.ay * x1 + this.by * y1 + this.ty;
        float oz1 = this.az * x1 + this.bz * y1 + this.tz;
        int near0 = this.wallColor(nx0, ny0, 1.0F);
        int near1 = this.wallColor(nx1, ny1, 1.0F);
        int far0 = this.wallColor(nx0, ny0, WALL_FAR_END);
        int far1 = this.wallColor(nx1, ny1, WALL_FAR_END);
        body.quad(ox0 + frontX, oy0 + frontY, oz0 + frontZ, near0,
            ox1 + frontX, oy1 + frontY, oz1 + frontZ, near1,
            ox1 + backX, oy1 + backY, oz1 + backZ, far1,
            ox0 + backX, oy0 + backY, oz0 + backZ, far0, light);
        this.vertices += 4;
        if (mask != null) {
          mask.quad(ox0 + frontX, oy0 + frontY, oz0 + frontZ, near0,
              ox1 + frontX, oy1 + frontY, oz1 + frontZ, near1,
              ox1 + backX, oy1 + backY, oz1 + backZ, far1,
              ox0 + backX, oy0 + backY, oz0 + backZ, far0, light);
          this.vertices += 4;
        }

        if (rims) {
          if (!seesBack) {
            this.rimEdge(x0, y0, r[at + 2], r[at + 3], x1, y1, r[at + 6], r[at + 7], width, back, far0, far1, true);
          }

          if (!seesFront) {
            this.rimEdge(x0, y0, r[at + 2], r[at + 3], x1, y1, r[at + 6], r[at + 7], width, front, near0, near1, false);
          }
        }
      }

      // Rings follow each other in the array, and a ring ends where it began.
      if (x1 == s[ringAt] && y1 == s[ringAt + 1]) {
        if (rims && seen != firstSeen) {
          this.fin(x1, y1, r[ringAt + 2], r[ringAt + 3], seen ? nx1 : s[ringAt + 2], seen ? ny1 : s[ringAt + 3], front,
              back, width);
        }

        ringAt = -1;
      }
    }
  }

  /**
   * Where the walls turn out of sight the silhouette is a line from the front to the back, and only a soft strip along
   * that line keeps it from stair-stepping when the letter is seen from the side.
   */
  private void fin(float x, float y, float ox, float oy, float nx, float ny, float front, float back, float width) {
    float qx = x + ox * width;
    float qy = y + oy * width;
    float px = this.ax * x + this.bx * y + this.tx;
    float py = this.ay * x + this.by * y + this.ty;
    float pz = this.az * x + this.bz * y + this.tz;
    float rx = this.ax * qx + this.bx * qy + this.tx;
    float ry = this.ay * qx + this.by * qy + this.ty;
    float rz = this.az * qx + this.bz * qy + this.tz;
    int near = this.wallColor(nx, ny, 1.0F);
    int far = this.wallColor(nx, ny, WALL_FAR_END);
    float fx = this.cx * front;
    float fy = this.cy * front;
    float fz = this.cz * front;
    float bx = this.cx * back;
    float by = this.cy * back;
    float bz = this.cz * back;
    if ((this.eyeX - x) * oy - (this.eyeY - y) * ox > 0.0F) {
      this.rim.quad(px + fx, py + fy, pz + fz, near,
          rx + fx, ry + fy, rz + fz, near & 0xFFFFFF,
          rx + bx, ry + by, rz + bz, far & 0xFFFFFF,
          px + bx, py + by, pz + bz, far, this.light);
    } else {
      this.rim.quad(px + bx, py + by, pz + bz, far,
          rx + bx, ry + by, rz + bz, far & 0xFFFFFF,
          rx + fx, ry + fy, rz + fz, near & 0xFFFFFF,
          px + fx, py + fy, pz + fz, near, this.light);
    }

    this.vertices += 4;
  }

  private void wholeRim(GlyphMesh mesh, float z, int color, boolean forward, float width) {
    float[] r = mesh.rim();
    for (int at = 0; at < r.length; at += GlyphMesh.RIM_STRIDE) {
      this.rimEdge(r[at], r[at + 1], r[at + 2], r[at + 3], r[at + 4], r[at + 5], r[at + 6], r[at + 7], width, z, color,
          color, forward);
    }
  }

  private void rimEdge(float x0, float y0, float ox0, float oy0, float x1, float y1, float ox1, float oy1, float width,
      float z, int color0, int color1, boolean forward) {
    float zx = this.cx * z + this.tx;
    float zy = this.cy * z + this.ty;
    float zz = this.cz * z + this.tz;
    float qx0 = x0 + ox0 * width;
    float qy0 = y0 + oy0 * width;
    float qx1 = x1 + ox1 * width;
    float qy1 = y1 + oy1 * width;
    float ax = this.ax;
    float ay = this.ay;
    float az = this.az;
    float bx = this.bx;
    float by = this.by;
    float bz = this.bz;
    if (forward) {
      this.rim.quad(ax * x0 + bx * y0 + zx, ay * x0 + by * y0 + zy, az * x0 + bz * y0 + zz, color0,
          ax * x1 + bx * y1 + zx, ay * x1 + by * y1 + zy, az * x1 + bz * y1 + zz, color1,
          ax * qx1 + bx * qy1 + zx, ay * qx1 + by * qy1 + zy, az * qx1 + bz * qy1 + zz, color1 & 0xFFFFFF,
          ax * qx0 + bx * qy0 + zx, ay * qx0 + by * qy0 + zy, az * qx0 + bz * qy0 + zz, color0 & 0xFFFFFF, this.light);
    } else {
      this.rim.quad(ax * qx0 + bx * qy0 + zx, ay * qx0 + by * qy0 + zy, az * qx0 + bz * qy0 + zz, color0 & 0xFFFFFF,
          ax * qx1 + bx * qy1 + zx, ay * qx1 + by * qy1 + zy, az * qx1 + bz * qy1 + zz, color1 & 0xFFFFFF,
          ax * x1 + bx * y1 + zx, ay * x1 + by * y1 + zy, az * x1 + bz * y1 + zz, color1,
          ax * x0 + bx * y0 + zx, ay * x0 + by * y0 + zy, az * x0 + bz * y0 + zz, color0, this.light);
    }

    this.vertices += 4;
  }

  private int wallColor(float nx, float ny, float end) {
    float toLight = this.lightAlongX * nx + this.lightAlongY * ny;
    return this.alphaBits | shaded(this.rgb, this.side * (WALL_BASE + WALL_SPAN * toLight) * end);
  }

  private static int shaded(int rgb, float shade) {
    int f = shade >= 1.0F ? 256 : shade <= 0.0F ? 0 : (int) (shade * 256.0F);
    return ((rgb >>> 16 & 0xFF) * f >> 8) << 16 | ((rgb >>> 8 & 0xFF) * f >> 8) << 8 | (rgb & 0xFF) * f >> 8;
  }
}
