package net.lyrica.v26_2.stage;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.List;
import net.minecraft.client.Camera;
import net.minecraft.client.gui.Font;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.phys.Vec3;
import net.lyrica.v26_2.look.Look;
import net.lyrica.v26_2.look.Palette;
import org.joml.Matrix4f;
import org.joml.Vector3fc;

/**
 * Draws placed lines as solid objects.
 *
 * <p>The space works out like this. The pose starts at the camera with world axes, because the camera's rotation lives
 * in the model-view. {@code Ry(yaw)} sends local z to (sin yaw, 0, cos yaw), the direction the face looks, and local x
 * to (cos yaw, 0, -sin yaw). So for a viewer on the +z side, with yaw 0, x runs to their right and +z comes at them.
 * The scale flips y because glyphs run downward, which makes a glyph's outline run counter-clockwise from that same
 * side. Hence +z is the front, the letter's body runs from the front at +depth/2 back to -depth/2, and the composer's
 * own z and tilts mean what they say. A viewer on the other side gets the plane turned by half a circle, around the
 * middle of the thickness, so nothing jumps.
 *
 * <p>Letters are real geometry from their fonts' outlines (see {@link LetterMesher}). Only what has no outline, a
 * character the font lacks, the game's own font, a right-to-left row, still goes through the font's texture, as a stack
 * of layers.
 */
final class SlotDrawer {

  /** A letter's baseline lies this far below its centre. Rows are 9 tall with the baseline at 7. */
  static final float BASELINE_BELOW_CENTRE = 2.5F;

  private static final float GLYPH_TOP = -4.5F;
  private static final float GLYPH_BOTTOM = 2.5F;

  // Screen pixels per mesh pixel below which a letter uses the coarser mesh, and above which it goes back, so a letter
  // right at the limit does not keep changing.
  private static final float COARSE_BELOW = 12.0F;
  private static final float FINE_ABOVE = 13.5F;
  private static final float RIM_SCREEN_PIXELS = 1.15F;
  private static final float WIDEST_RIM = 1.0F;
  private static final float THINNEST_SEEN_PIXELS = 0.3F;
  private static final float NEAREST_AHEAD = 0.5F;
  private static final float FLOOR_AIR = 0.01F;

  // Per frame. Past the first, letters use coarse meshes; past the second they lose their rims; past the last, nothing.
  private static final int FINE_VERTICES = 90_000;
  private static final int RIM_VERTICES = 180_000;
  private static final int MOST_VERTICES = 260_000;

  private static final float OUTLINE_LIFT_BLOCKS = 0.004F;

  private static final float LAYER_GAP_BLOCKS = 0.02F;
  private static final float LAYER_GAP_FROM_DISTANCE = 12.0F;
  private static final float SMALLEST_GAP = 0.0006F;
  private static final float DEPTH_PRECISION = 3.0e-6F;
  private static final int FEWEST_SIDES = 3;
  private static final int MOST_SIDES = 24;
  private static final int LEAST_BUDGET_SIDES = 6;
  private static final int SIDE_LAYER_BUDGET = 1400;

  // Quantised, as the sides are dark and thin: flowing colours then rarely need a fresh layout for them.
  private static final int SIDE_COLOR_MASK = 0xFFF8F8F8;
  private static final float MIDDLE_SHADE = 0.78F;
  private static final float FAR_SHADE = 0.58F;
  private static final int SOLID_ALPHA = 250;
  // The text shader throws away anything below a tenth, so a layer fainter than this vanishes rather than fades.
  private static final int SHADER_ALPHA_FLOOR = 26;
  private static final float LEAST_LAYER_ALPHA = 0.125F;

  private static final float OUTLINE_REACH = 0.6F;
  private static final float OUTLINE_DIAGONAL = 0.42F;
  private static final float OUTLINE_FULL_DISTANCE = 24.0F;
  private static final float FAINT_SHARE = 0.3F;

  // The halo: light along each letter's own outline, and a wide faint pool that melts a word together. Text pixels.
  private static final float SOFT_REACH = 1.5F;
  private static final float BRIGHT_REACH = 2.6F;
  private static final float SOFT_PEAK = 0.46F;
  private static final float BRIGHT_PEAK = 0.78F;
  private static final float POOL_REACH = 2.4F;
  private static final float SOFT_POOL_PEAK = 0.07F;
  private static final float BRIGHT_POOL_PEAK = 0.14F;
  private static final float HALO_BEHIND_BLOCKS = 0.012F;
  // A letter breaking lets go a flash, then has only its pool left, which fades with its pieces.
  private static final float FLASH_UNTIL = 0.14F;
  private static final float FLASH_STRENGTH = 3.0F;
  private static final float SHARD_THICKNESS = 0.32F;

  private static final int LETTERS_PER_TWINKLE = 6;
  private static final int MOST_TWINKLES = 8;
  private static final float TWINKLE_SIZE = 2.0F;
  private static final float TWINKLE_OFF_FRONT = 0.2F;
  private static final long TWINKLE_PERIOD_MS = 1100L;
  private static final long TWINKLE_PERIOD_STEP_MS = 130L;

  private static final float SHADOW_HALF_WIDTH = 0.7F;
  private static final float SHADOW_WIDTH_PER_GAP = 0.35F;
  private static final float SHADOW_LIFT = 0.02F;
  private static final float SHADOW_PEAK = 0.5F;
  private static final float SHADOW_FADE_PER_GAP = 0.5F;
  private static final float SHADOW_MOST_OFFSET = 1.6F;
  private static final float SHADOW_OFFSET_PER_TALL = 0.35F;
  private static final float SHADOW_FADE_FROM = 0.75F;
  private static final float BLOCKS_IN_CHUNK = 16.0F;
  private static final float DEGREES_TO_RADIANS = (float) (Math.PI / 180.0);

  private static final float TRAIL_SHRINK_PER_AGE = 0.14F;
  private static final float TRAIL_LEAST_SIZE = 0.45F;
  private static final float TRAIL_FADE_PER_AGE = 0.62F;
  private static final float ROW_LAYER_SHARE = 0.5F;
  private static final float BOB_BLOCKS = 0.08F;
  private static final float BOB_SPEED = 0.0011F;

  private static final float SKY_FADE_START = 60.0F;
  private static final float SKY_FADE_LENGTH = 400.0F;
  private static final float SKY_MOST_ALPHA = 0.8F;
  private static final float SKY_LEAST_ALPHA = 0.35F;

  private static final float VIEW_MARGIN_BLOCKS = 4.0F;
  private static final float MOST_PHI = 2.6F;
  private static final float PHI_OVERSHOOT = 1.25F;

  private static final float DOT_RADIUS = 3.2F;
  private static final float DOT_SPACING = 10.0F;
  private static final float DOT_UNLIT = 0.25F;
  private static final float DOT_EMPTY_ALPHA = 0.6F;
  private static final float DOT_EMPTY_SIZE = 0.7F;

  /** The development test compares the detail levels at one spot. Otherwise -1: the distance decides. */
  static int forcedDetail = -1;

  private final StageBuffers buffers;
  private final LetterMesher mesher = new LetterMesher();
  private final Matrix4f base = new Matrix4f();
  private final Matrix4f line = new Matrix4f();
  private final Matrix4f letter = new Matrix4f();
  private final Matrix4f layer = new Matrix4f();
  private final Matrix4f meshMatrix = new Matrix4f();
  private final Matrix4f inverse = new Matrix4f();
  private long stageSeed;

  private Look look;
  private Font font;
  private Style style;
  private VectorFace face;
  private long nowMs;
  private double cameraX;
  private double cameraY;
  private double cameraZ;
  private float forwardX;
  private float forwardY;
  private float forwardZ;
  private float leftX;
  private float leftY;
  private float leftZ;
  private float upX;
  private float upY;
  private float upZ;
  private float pixelsAtOneBlock;
  private float tanHalfTall;
  private float tanHalfWide;
  private int lineOrder;
  private int lineLight;
  private boolean hasFloor;
  private float floorY;
  private float sunSide;
  private float daylight;
  private float fogReach;

  private Font.GlyphVisitor solidVisitor;
  private Font.GlyphVisitor faintVisitor;
  private int visitorLight = -1;

  private float curve;
  private float phiLimit;
  private float blocksPerPixel;
  private float stretchOf = 1.0F;
  private float cameraDistance;

  private float bentX;
  private float bentOut;
  private float bentPhi;

  SlotDrawer(StageBuffers buffers) {
    this.buffers = buffers;
  }

  void begin(PoseStack pose, Camera camera, ClientLevel level, Look look, Font font, Style style, VectorFace face,
      long nowMs, float renderDistanceChunks, int windowWidth, int windowHeight, float fovDegrees) {
    this.look = look;
    this.font = font;
    this.style = style;
    this.face = face;
    this.nowMs = nowMs;
    this.base.set(pose.last().pose());
    Vec3 position = camera.position();
    this.cameraX = position.x;
    this.cameraY = position.y;
    this.cameraZ = position.z;
    Vector3fc forward = camera.forwardVector();
    this.forwardX = forward.x();
    this.forwardY = forward.y();
    this.forwardZ = forward.z();
    Vector3fc left = camera.leftVector();
    this.leftX = left.x();
    this.leftY = left.y();
    this.leftZ = left.z();
    Vector3fc up = camera.upVector();
    this.upX = up.x();
    this.upY = up.y();
    this.upZ = up.z();
    this.tanHalfTall = (float) Math.tan(Math.toRadians(fovDegrees) * 0.5);
    this.tanHalfWide = this.tanHalfTall * windowWidth / Math.max(1, windowHeight);
    this.pixelsAtOneBlock = windowHeight * 0.5F / this.tanHalfTall;
    this.fogReach = renderDistanceChunks * BLOCKS_IN_CHUNK;
    this.mesher.vertices = 0;
    this.buffers.open();
    this.readSun(level);
  }

  /** What the letters of the last frame took. */
  int vertices() {
    return this.mesher.vertices;
  }

  /** A frame with nothing on stage writes nothing. */
  void noVertices() {
    this.mesher.vertices = 0;
  }

  // A line

  void drawLine(LineSlot slot, float master, int order) {
    ComposedLine main = slot.composed;
    this.stageSeed = slot.seed;
    this.lineOrder = order;
    this.lineLight = slot.light;
    this.hasFloor = slot.hasFloor;
    this.floorY = (float) (slot.floorY - this.cameraY) + FLOOR_AIR;
    float size = Math.max(TRAIL_LEAST_SIZE, 1.0F - TRAIL_SHRINK_PER_AGE * slot.age);
    float scale = slot.scale * size;
    float alpha = master * (float) Math.pow(TRAIL_FADE_PER_AGE, slot.age);

    float tall = (main.height + slot.belowEase + slot.abovePx) * scale;
    float liftY = slot.onFoot ? tall * 0.5F : 0.0F;
    double middleX = slot.anchorX - this.cameraX;
    double middleY = slot.anchorY + liftY - this.cameraY;
    double middleZ = slot.anchorZ - this.cameraZ;
    this.cameraDistance = (float) Math.sqrt(middleX * middleX + middleY * middleY + middleZ * middleZ);
    if (slot.farAway) {
      alpha *= Math.max(SKY_LEAST_ALPHA,
          Math.min(SKY_MOST_ALPHA, 1.0F - (this.cameraDistance - SKY_FADE_START) / SKY_FADE_LENGTH));
    }

    float reach = Math.max(slot.widthPx * scale, tall) * 0.5F + VIEW_MARGIN_BLOCKS;
    if (middleX * this.forwardX + middleY * this.forwardY + middleZ * this.forwardZ < -reach) {
      return;
    }

    this.curve = Placement.curvature(slot, this.look);
    this.phiLimit = Math.min(MOST_PHI, this.curve * Math.max(1.0F, slot.widthPx * 0.5F) * PHI_OVERSHOOT);
    this.blocksPerPixel = scale;
    this.stretchOf = slot.stretch;
    this.useLight(slot.light);

    // On foot the anchor is the lowest point of the whole block of rows, descenders included.
    float origin = slot.onFoot ? slot.bottomEase : main.height * 0.5F;
    this.setLineMatrix(slot, scale, origin);

    float depthPx = depthPx(slot, this.look);
    int sides = this.sidesFor(main, depthPx * this.look.size * scale);
    sides = Math.max(Math.min(sides, FEWEST_SIDES), Math.round(sides / (1.0F + slot.age)));

    if (slot.standing && slot.hasGround && this.look.shadow) {
      this.drawShadow(slot, main, scale, alpha);
    }

    this.drawRow(main, slot.plates, 0.0F, alpha, sides, depthPx);
    if (this.look.sparkles) {
      this.twinkles(slot, main, alpha, depthPx);
    }

    int rowSides = Math.max(Math.min(sides, FEWEST_SIDES), Math.round(sides * ROW_LAYER_SHARE));
    for (int i = 0; i < LineSlot.EXTRA_ROWS; i++) {
      LineSlot.Extra extra = slot.extras[i];
      if (extra != null && extra.composed != null) {
        float rowAlpha = alpha * extra.alpha;
        this.drawRow(extra.composed, extra.plates, extra.y, rowAlpha, rowSides, depthPx);
      }
    }
  }

  private void setLineMatrix(LineSlot slot, float scale, float originPx) {
    float bob = slot.bobbing
        ? (float) Math.sin(this.nowMs * BOB_SPEED + (slot.seed & 1023L)) * BOB_BLOCKS * slot.scale
            / Placement.BLOCKS_PER_PIXEL
        : 0.0F;
    this.line.set(this.base)
        .translate((float) (slot.anchorX - this.cameraX), (float) (slot.anchorY + bob - this.cameraY),
            (float) (slot.anchorZ - this.cameraZ))
        .rotate(slot.orient)
        .scale(scale, -scale * slot.stretch, scale)
        .translate(0.0F, -originPx, 0.0F);
  }

  /** The letters' thickness in glyph pixels at scale 1. */
  private static float depthPx(LineSlot slot, Look look) {
    float blocks = look.depth * 0.3F;
    return slot.thin ? Math.min(blocks, Placement.THIN_BLOCKS) / slot.scale : blocks / Placement.BLOCKS_PER_PIXEL;
  }

  /**
   * How many layers behind the front face. Enough that no gap shows from an angle, fewer far away where a gap covers
   * less of the screen, and never so close together that the depth buffer cannot tell them apart.
   */
  private int sidesFor(ComposedLine row, float thicknessBlocks) {
    if (thicknessBlocks <= 0.0F) {
      return 0;
    }

    float distance = this.cameraDistance;
    float tellApart = SMALLEST_GAP + distance * distance * DEPTH_PRECISION;
    float fit = thicknessBlocks / tellApart;
    if (fit < FEWEST_SIDES) {
      return (int) fit;
    }

    float gap = Math.max(tellApart, LAYER_GAP_BLOCKS * Math.max(1.0F, distance / LAYER_GAP_FROM_DISTANCE));
    int wanted = Math.max(FEWEST_SIDES, (int) Math.ceil(thicknessBlocks / gap));
    int budget = Math.max(LEAST_BUDGET_SIDES, SIDE_LAYER_BUDGET / Math.max(1, row.letters.size()));
    return Math.min(wanted, Math.min(MOST_SIDES, budget));
  }

  private void drawRow(ComposedLine row, LetterPlates plates, float rowY, float alphaFactor, int sides, float depthPx) {
    float halfLine = this.font.lineHeight * 0.5F;
    float shade = this.look.sideShade;
    List<Letter> letters = row.letters;
    int count = letters.size();
    for (int i = 0; i < count; i++) {
      Letter l = letters.get(i);
      if (!l.visible()) {
        continue;
      }

      int alpha = Math.round((l.argb >>> 24) * alphaFactor);
      if (alpha < SHADER_ALPHA_FLOOR) {
        continue;
      }

      VectorFace.Glyph glyph = this.glyphOf(l);
      // The font's texture only has a front, so there a spin has to stay short of edge-on.
      float tiltX = glyph != null ? l.tiltX : Motions.faceFront(l.tiltX);
      float tiltY = glyph != null ? l.tiltY : Motions.faceFront(l.tiltY);

      this.bend(l.x, l.z);
      this.letter.set(this.line).translate(this.bentX, rowY + l.y, this.bentOut);
      float turn = tiltY * DEGREES_TO_RADIANS - this.bentPhi;
      if (turn != 0.0F) {
        this.letter.rotateY(turn);
      }

      if (tiltX != 0.0F) {
        this.letter.rotateX(tiltX * DEGREES_TO_RADIANS);
      }

      if (l.tiltZ != 0.0F) {
        this.letter.rotateZ(l.tiltZ * DEGREES_TO_RADIANS);
      }

      this.letter.scale(l.scale);

      float halfDepth = depthPx * 0.5F;
      this.liftOffFloor(l, glyph, halfDepth, halfLine);

      float blocks = l.scale * this.blocksPerPixel;
      float reach = blocks * (l.width + depthPx + this.font.lineHeight) * Math.max(1.0F, this.stretchOf) + 0.5F;
      float atX = this.letter.m30();
      float atY = this.letter.m31();
      float atZ = this.letter.m32();
      float ahead = atX * this.forwardX + atY * this.forwardY + atZ * this.forwardZ;
      if (ahead < -reach) {
        continue;
      }

      float inFront = Math.max(ahead, 0.0F);
      float across = atX * this.leftX + atY * this.leftY + atZ * this.leftZ;
      float upward = atX * this.upX + atY * this.upY + atZ * this.upZ;
      if (Math.abs(across) - reach > inFront * this.tanHalfWide || Math.abs(upward) - reach > inFront * this.tanHalfTall) {
        continue;
      }

      this.haloOf(l, glyph, alpha, depthPx, ahead);
      if (glyph != null) {
        this.drawVector(l, i, glyph, alpha, depthPx, ahead);
        continue;
      }

      FormattedCharSequence text = l.sequence(this.style);
      float left = -l.width * 0.5F;
      int rgb = l.argb & 0xFFFFFF;
      float frontZ = sides > 0 ? depthPx * 0.5F : 0.0F;

      if (this.look.throughWalls) {
        int faint = Math.max(SHADER_ALPHA_FLOOR, Math.round(alpha * FAINT_SHARE)) << 24 | rgb;
        this.letter.translate(left, -halfLine, frontZ, this.layer);
        plates.get(this.font, i, text, LetterPlates.FAINT, faint).visit(this.faintVisitor);
      }

      float gap = 0.0F;
      if (sides > 0) {
        int used = sides;
        int sideAlpha = alpha;
        if (alpha < SOLID_ALPHA) {
          // Stacked see-through layers add up to nearly opaque, so fewer and fainter ones stand in for them while the
          // letter fades, adding up to about what the fade asks for.
          float shown = alpha / 255.0F;
          float target = shown * shown;
          used = Math.min(sides, (int) (Math.log(1.0F - target) / Math.log(1.0F - LEAST_LAYER_ALPHA)));
          sideAlpha = used > 0 ? Math.round((1.0F - (float) Math.pow(1.0F - target, 1.0 / used)) * 255.0F) : 0;
        }

        if (used > 0) {
          gap = depthPx / used;
          int alphaBits = sideAlpha << 24;
          int near = (alphaBits | Palette.dim(rgb, shade) & 0xFFFFFF) & SIDE_COLOR_MASK;
          int middle = (alphaBits | Palette.dim(rgb, shade * MIDDLE_SHADE) & 0xFFFFFF) & SIDE_COLOR_MASK;
          int far = (alphaBits | Palette.dim(rgb, shade * FAR_SHADE) & 0xFFFFFF) & SIDE_COLOR_MASK;
          for (int j = used; j >= 1; j--) {
            int band = (j - 1) * 3 / used;
            int kind = band == 0 ? LetterPlates.NEAR_SIDE : band == 1 ? LetterPlates.MIDDLE_SIDE : LetterPlates.FAR_SIDE;
            int color = band == 0 ? near : band == 1 ? middle : far;
            this.letter.translate(left, -halfLine, frontZ - j * gap, this.layer);
            plates.get(this.font, i, text, kind, color).visit(this.solidVisitor);
          }
        }
      }

      if (this.look.outline) {
        float behind = gap > 0.0F ? gap * 0.5F : SMALLEST_GAP * 8.0F / Math.max(this.blocksPerPixel * l.scale, 0.001F);
        int edge = alpha << 24 | (this.look.outlineColor & 0xFFFFFF);
        Font.PreparedText plate = plates.get(this.font, i, text, LetterPlates.EDGE, edge);
        float z = frontZ - behind;
        this.outlineAt(plate, left + OUTLINE_REACH, -halfLine, z);
        this.outlineAt(plate, left - OUTLINE_REACH, -halfLine, z);
        this.outlineAt(plate, left, -halfLine + OUTLINE_REACH, z);
        this.outlineAt(plate, left, -halfLine - OUTLINE_REACH, z);
        if (this.cameraDistance < OUTLINE_FULL_DISTANCE) {
          this.outlineAt(plate, left + OUTLINE_DIAGONAL, -halfLine + OUTLINE_DIAGONAL, z);
          this.outlineAt(plate, left - OUTLINE_DIAGONAL, -halfLine + OUTLINE_DIAGONAL, z);
          this.outlineAt(plate, left + OUTLINE_DIAGONAL, -halfLine - OUTLINE_DIAGONAL, z);
          this.outlineAt(plate, left - OUTLINE_DIAGONAL, -halfLine - OUTLINE_DIAGONAL, z);
        }
      }

      this.letter.translate(left, -halfLine, frontZ, this.layer);
      plates.get(this.font, i, text, LetterPlates.FRONT, alpha << 24 | rgb).visit(this.solidVisitor);
    }
  }

  private void outlineAt(Font.PreparedText plate, float x, float y, float z) {
    this.letter.translate(x, y, z, this.layer);
    plate.visit(this.solidVisitor);
  }

  /** A letter's outlines, looked up only when its glyph changes, which GLITCH makes it do; null means the texture. */
  private VectorFace.Glyph glyphOf(Letter l) {
    if (this.face == null || l.wholeRow) {
      return null;
    }

    if (l.vectorFor != l.glyph || l.vectorFace != this.face) {
      l.vectorFor = l.glyph;
      l.vectorFace = this.face;
      l.vector = this.face.glyph(l.glyph);
    }

    return l.vector;
  }

  /**
   * Whatever a letter's motion is doing, none of it goes below the highest ground under the line: the box around the
   * letter is put through the matrix, and if its lowest corner is under that, the letter rises by the difference. It
   * rests above that height anyway, so this only ever touches a letter in motion, and it moves it smoothly.
   */
  private void liftOffFloor(Letter l, VectorFace.Glyph glyph, float halfDepth, float halfLine) {
    if (!this.hasFloor) {
      return;
    }

    float x0;
    float x1;
    float y0;
    float y1;
    if (glyph != null) {
      float size = this.face.scale;
      float origin = -l.width * 0.5F + this.face.extraAdvance * 0.5F;
      x0 = origin + glyph.minX * size;
      x1 = origin + glyph.maxX * size;
      y0 = BASELINE_BELOW_CENTRE + glyph.minY * size;
      y1 = BASELINE_BELOW_CENTRE + glyph.maxY * size;
    } else {
      x1 = l.width * 0.5F;
      x0 = -x1;
      y1 = halfLine;
      y0 = -y1;
    }

    Matrix4f m = this.letter;
    float lowest = m.m31() + Math.min(m.m01() * x0, m.m01() * x1) + Math.min(m.m11() * y0, m.m11() * y1)
        - Math.abs(m.m21()) * halfDepth;
    if (lowest < this.floorY) {
      m.m31(m.m31() + this.floorY - lowest);
    }
  }

  /**
   * The halo goes letter by letter and moves with each one, whatever its motion is doing: it lies just behind the
   * letter, in the letter's own space, so a tumbling letter takes its light along. It has the letter's shape rather than
   * a word's box, and there is no box that letters flying apart could stretch.
   */
  private void haloOf(Letter l, VectorFace.Glyph glyph, int alpha, float depthPx, float ahead) {
    boolean bright = "BRIGHT".equals(this.look.glow);
    boolean lit = bright || "SOFT".equals(this.look.glow);
    // The GLOW karaoke style lights the sung letter even when the look has no glow of its own.
    float sungGlow = l.karaokeGlow;
    if (!lit && sungGlow <= 0.0F || this.mesher.vertices >= RIM_VERTICES) {
      return;
    }

    float shown = alpha / 255.0F * (lit ? 1.0F + sungGlow : sungGlow);
    int rgb = "CUSTOM".equals(this.look.glowColorMode) ? this.look.glowColor : l.argb & 0xFFFFFF;
    float reach = (bright ? BRIGHT_REACH : SOFT_REACH) * this.look.glowReach;
    float poolPeak = (bright ? BRIGHT_POOL_PEAK : SOFT_POOL_PEAK) * shown;
    float blocks = Math.max(this.blocksPerPixel * l.scale, 0.001F);
    // On the far side of the letter from the viewer, wherever the viewer is
    this.letter.invertAffine(this.inverse);
    boolean fromFront = this.inverse.m32() >= 0.0F;
    float off = depthPx * 0.5F + HALO_BEHIND_BLOCKS / blocks;
    float z = fromFront ? -off : off;
    VertexConsumer out = this.buffers.halo.getBuffer(RenderTypes.dragonRays());

    float breaking = l.shards;
    if (breaking > 0.0F) {
      float flash = breaking < FLASH_UNTIL ? 1.0F + FLASH_STRENGTH * (1.0F - breaking / FLASH_UNTIL) : 1.0F;
      poolPeak *= flash * (1.0F - breaking) * (1.0F - breaking);
    }

    float poolX = l.width * 0.5F + reach * POOL_REACH;
    float poolY = this.font.lineHeight * 0.5F + reach * POOL_REACH;
    if (Math.round(poolPeak * 255.0F) > 0) {
      this.mesher.vertices += SoftLight.pool(out, this.letter, 0.0F, 0.0F, z, poolX, poolY, rgb, poolPeak);
    }

    float peak = (bright ? BRIGHT_PEAK : SOFT_PEAK) * shown;
    if (glyph == null || breaking > 0.0F || Math.round(peak * 255.0F) == 0) {
      return;
    }

    VectorFace face = this.face;
    float left = -l.width * 0.5F + face.extraAdvance * 0.5F;
    GlyphMesh[] body = glyph.coarse;
    for (int i = 0; i < body.length; i++) {
      GlyphMesh mesh = body[i];
      if (!mesh.isEmpty()) {
        this.letter.translate(left + glyph.partX[i] * face.scale, BASELINE_BELOW_CENTRE, 0.0F, this.meshMatrix)
            .scale(face.scale, face.scale, 1.0F);
        this.mesher.vertices += SoftLight.contour(out, this.meshMatrix, mesh, z, reach / face.scale, rgb, peak);
      }
    }
  }

  private void drawVector(Letter l, int index, VectorFace.Glyph glyph, int alpha, float depthPx, float ahead) {
    LetterMesher mesher = this.mesher;
    if (mesher.vertices >= MOST_VERTICES) {
      return;
    }

    VectorFace face = this.face;
    float textPixels = this.pixelsAtOneBlock / Math.max(ahead, NEAREST_AHEAD) * this.blocksPerPixel * l.scale;
    float meshPixels = textPixels * face.scale;
    boolean coarse = l.coarse ? meshPixels < FINE_ABOVE : meshPixels < COARSE_BELOW;
    l.coarse = coarse;
    coarse |= mesher.vertices >= FINE_VERTICES;
    if (forcedDetail >= 0) {
      coarse = forcedDetail == GlyphOutlines.COARSE;
    }

    GlyphMesh[] body = coarse ? glyph.coarse : glyph.fine;

    float fringe = mesher.vertices >= RIM_VERTICES ? 0.0F : Math.min(WIDEST_RIM, RIM_SCREEN_PIXELS / meshPixels);
    float front = depthPx * 0.5F;
    float back = -front;
    if (depthPx * textPixels < THINNEST_SEEN_PIXELS) {
      front = 0.0F;
      back = 0.0F;
    }

    int rgb = l.argb & 0xFFFFFF;
    if (alpha < SOLID_ALPHA) {
      StageBuffers.FadeLayer fade = this.buffers.fade(this.lineOrder);
      mesher.target(fade.color, fade.mask, fade.rim, this.lineLight);
    } else {
      mesher.target(this.buffers.letters, null, this.buffers.rims, this.lineLight);
    }

    float left = -l.width * 0.5F + face.extraAdvance * 0.5F;
    GlyphMesh[] plates = coarse ? glyph.plateCoarse : glyph.plateFine;
    for (int i = 0; i < body.length; i++) {
      GlyphMesh mesh = body[i];
      if (mesh.isEmpty()) {
        continue;
      }

      this.letter.translate(left + glyph.partX[i] * face.scale, BASELINE_BELOW_CENTRE, 0.0F, this.meshMatrix)
          .scale(face.scale, face.scale, 1.0F);
      if (l.shards > 0.0F) {
        // Fewer and bigger pieces read as a letter breaking, and the coarse outline has just those
        mesher.shards(this.meshMatrix, glyph.coarse[i], Math.max(front, SHARD_THICKNESS), rgb, alpha, this.look.sideShade,
            l.shards, this.stageSeed + index * 31L + i);
        continue;
      }

      if (this.look.throughWalls) {
        int faint = Math.max(SHADER_ALPHA_FLOOR, Math.round(alpha * FAINT_SHARE)) << 24 | rgb;
        mesher.ghost(this.meshMatrix, mesh, front, faint, this.buffers.faintLetters);
      }

      if (plates != null) {
        float lift = (OUTLINE_LIFT_BLOCKS + this.cameraDistance * this.cameraDistance * DEPTH_PRECISION * 2.0F)
            / Math.max(this.blocksPerPixel * l.scale, 0.001F);
        mesher.plate(this.meshMatrix, plates[i], front - lift, alpha << 24 | (this.look.outlineColor & 0xFFFFFF), fringe);
      }

      mesher.letter(this.meshMatrix, mesh, front, back, rgb, alpha, this.look.sideShade, fringe);
    }
  }

  /**
   * Puts a point of the row on the line's curve, a circle whose centre lies on the viewer's side, so the ends come
   * forward and turn inward by exactly the slope. A letter blown past the end of the row carries on along the tangent.
   */
  private void bend(float x, float z) {
    float k = this.curve;
    if (k <= 0.0F) {
      this.bentX = x;
      this.bentOut = z;
      this.bentPhi = 0.0F;
      return;
    }

    float phi = Math.max(-this.phiLimit, Math.min(this.phiLimit, x * k));
    float past = x - phi / k;
    float sin = (float) Math.sin(phi);
    float cos = (float) Math.cos(phi);
    this.bentX = sin / k + past * cos;
    this.bentOut = z + (1.0F - cos) / k + past * sin;
    this.bentPhi = phi;
  }

  // Shadow

  private void readSun(ClientLevel level) {
    if (!level.dimensionType().hasSkyLight()) {
      this.sunSide = 0.0F;
      this.daylight = 0.75F;
      return;
    }

    // Day time 0 is sunrise in the east (+x), 6000 is noon. At night the moon takes over from the opposite side.
    float angle = Math.floorMod(level.getOverworldClockTime(), 24000L) / 24000.0F * (float) Math.PI * 2.0F;
    float up = (float) Math.sin(angle);
    float across = (float) Math.cos(angle);
    this.sunSide = up >= 0.0F ? across : -across;
    this.daylight = up >= 0.0F ? 0.6F + 0.4F * up : 0.55F;
  }

  private void drawShadow(LineSlot slot, ComposedLine main, float scale, float alpha) {
    float gapBelow = Math.max(0.0F, (float) (slot.anchorY - slot.groundY));
    float nearGround = 1.0F / (1.0F + gapBelow * SHADOW_FADE_PER_GAP);
    float fog = Math.max(0.0F,
        Math.min(1.0F, (this.fogReach - this.cameraDistance) / (this.fogReach * (1.0F - SHADOW_FADE_FROM))));
    float peak = SHADOW_PEAK * nearGround * main.presence * alpha * this.daylight * fog;
    if (peak <= 0.0F) {
      return;
    }

    float tall = main.height * scale;
    float offset = -this.sunSide * Math.min(SHADOW_MOST_OFFSET, tall * SHADOW_OFFSET_PER_TALL);
    this.layer.set(this.base)
        .translate((float) (slot.anchorX + offset - this.cameraX), (float) (slot.groundY + SHADOW_LIFT - this.cameraY),
            (float) (slot.anchorZ - this.cameraZ))
        .rotateY(slot.yaw);
    float halfLength = slot.widthPx * 0.5F * scale + Placement.SHADOW_OVERHANG;
    float halfWidth = SHADOW_HALF_WIDTH + gapBelow * SHADOW_WIDTH_PER_GAP;
    float blockCurve = this.curve / scale;
    VertexConsumer out = this.buffers.shadow.getBuffer(RenderTypes.debugQuads());
    SoftLight.groundShadow(out, this.layer, halfLength, halfWidth, blockCurve,
        slot.columnsValid ? slot.groundColumns : null, peak);
  }

  private void twinkles(LineSlot slot, ComposedLine main, float alpha, float depthPx) {
    List<Letter> letters = main.letters;
    int count = letters.size();
    if (count == 0) {
      return;
    }

    VertexConsumer out = this.buffers.top.getBuffer(RenderTypes.dragonRays());
    int twinkles = Math.min(MOST_TWINKLES, 1 + count / LETTERS_PER_TWINKLE);
    for (int k = 0; k < twinkles; k++) {
      long period = TWINKLE_PERIOD_MS + k * TWINKLE_PERIOD_STEP_MS;
      long cycle = this.nowMs / period;
      float part = (this.nowMs % period) / (float) period;
      int salt = (int) (cycle & 0xFFFFL);
      Letter l = letters.get((int) (Motions.noise(slot.seed, k, salt) * count) % count);
      if (!l.visible()) {
        continue;
      }

      float life = (float) Math.sin(part * Math.PI);
      float across = Motions.noise(slot.seed, k + 64, salt) < 0.5F ? -0.5F : 0.5F;
      float upDown = Motions.noise(slot.seed, k + 128, salt) < 0.5F ? GLYPH_TOP : GLYPH_BOTTOM;
      this.bend(l.x + across * l.width * l.scale, l.z + depthPx * l.scale * 0.5F + TWINKLE_OFF_FRONT);
      SoftLight.twinkle(out, this.line, this.bentX, l.y + upDown * l.scale, this.bentOut,
          TWINKLE_SIZE * l.scale * (0.6F + 0.4F * life), 0xFFFFFF, life * life * alpha * ((l.argb >>> 24) / 255.0F));
    }
  }

  // Break dots

  void drawDots(LineSlot slot, int dots, float progress, float alpha) {
    double middleX = slot.anchorX - this.cameraX;
    double middleY = slot.anchorY - this.cameraY;
    double middleZ = slot.anchorZ - this.cameraZ;
    this.cameraDistance = (float) Math.sqrt(middleX * middleX + middleY * middleY + middleZ * middleZ);
    float width = dots * DOT_SPACING;
    float origin = slot.onFoot ? DOT_RADIUS * 2.0F : DOT_RADIUS;
    this.setLineMatrix(slot, slot.scale, origin);
    this.curve = 0.0F;
    float depthPx = depthPx(slot, this.look);
    int sides = Math.min(MOST_SIDES / 2, this.sidesFor(slot.composed, depthPx * slot.scale));
    float gap = sides > 0 ? depthPx / sides : 0.0F;
    float front = sides > 0 ? depthPx * 0.5F : 0.0F;
    int rgb = this.look.color & 0xFFFFFF;
    VertexConsumer out = this.buffers.solid.getBuffer(RenderTypes.debugQuads());
    for (int i = 0; i < dots; i++) {
      float fill = Motions.clamp01(progress * dots - i);
      float x = -width * 0.5F + (i + 0.5F) * DOT_SPACING;
      float radius = DOT_RADIUS * (DOT_EMPTY_SIZE + (1.0F - DOT_EMPTY_SIZE) * fill);
      int faceRgb = Palette.mix(Palette.dim(rgb, DOT_UNLIT), rgb, fill);
      int a = Math.round(255.0F * alpha * (DOT_EMPTY_ALPHA + (1.0F - DOT_EMPTY_ALPHA) * fill));
      if (a < SHADER_ALPHA_FLOOR) {
        continue;
      }

      int side = a << 24 | Palette.dim(faceRgb, this.look.sideShade) & 0xFFFFFF;
      for (int j = sides; j >= 1; j--) {
        SoftLight.disc(out, this.line, x, DOT_RADIUS, front - j * gap, radius, side, slot.light);
      }

      SoftLight.disc(out, this.line, x, DOT_RADIUS, front, radius, a << 24 | faceRgb, slot.light);
    }
  }

  /** The visitors hold the layer matrix itself, not a copy, so they only need making again when the light changes. */
  private void useLight(int light) {
    if (light != this.visitorLight) {
      this.visitorLight = light;
      this.solidVisitor = this.buffers.text.visitor(this.layer, Font.DisplayMode.NORMAL, light);
      this.faintVisitor = this.buffers.faint.visitor(this.layer, Font.DisplayMode.SEE_THROUGH, light);
    }
  }
}
