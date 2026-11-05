package net.splyrics.v1_21_11.stage;

import java.util.List;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.splyrics.v1_21_11.look.Look;
import org.joml.Vector3fc;

/** Finds a line's spot in the world, once, by looking at what is actually there. */
final class Placement {

  /** Bundled fonts have capitals about seven glyph pixels tall, so this makes a letter about a block at the default. */
  static final float BLOCKS_PER_PIXEL = 1.0F / 7.0F;
  /** A thin line's whole thickness in blocks. */
  static final float THIN_BLOCKS = 0.06F;
  /** How far beyond the text a shadow reaches along the line, in blocks. */
  static final float SHADOW_OVERHANG = 0.8F;

  private static final float DEFAULT_DISTANCE = 12.0F;
  private static final float BEFORE_HIT = 0.7F;
  private static final float LEAST_ROOM = 0.4F;
  private static final float LEAST_SHRINK = 0.12F;
  private static final float LEAST_FIT = 0.25F;
  private static final float SIDE_AIR = 0.25F;
  private static final float TOP_AIR = 0.25F;
  private static final float PROBE_HEIGHT = 0.7F;
  // Anything lower than this above the middle of the line is a step, and the line goes up onto it. Anything taller is
  // a wall, and the line moves out of its way.
  private static final float STEP_PROBE = 2.5F;
  private static final float LEAST_PROBE = 0.3F;
  private static final float PROBE_BELOW_ROOF = 0.2F;
  // Taller than this above the middle of the line, and it cannot be stood on: there the line's only hope was moving away.
  private static final float RISE_LIMIT = 6.0F;
  private static final float FOOT_AIR = 0.2F;
  private static final float FOOT_STEP = 0.5F;
  private static final int MOST_ALONG = 96;
  private static final int MOST_ACROSS = 13;
  private static final int FIT_LOOKS = 5;
  static final float GROUND_REACH = 40.0F;
  private static final float SHADOW_REACH = 10.0F;
  private static final float COLUMN_REACH = 2.5F;
  private static final float CLEARANCE = 0.03F;
  private static final float SURFACE_AIR = 0.02F;
  private static final float WITHOUT_GROUND_DROP = 1.2F;
  private static final float LEAN_RADIANS = 0.12F;
  // Lines take turns near and far, so that a leaving line and an entering one never share a plane.
  private static final float FAR_TURN = 1.16F;
  private static final float WANDER_BASE = 0.1F;
  private static final float WANDER_ROOM = 0.55F;
  private static final float VIEW_HALF_WIDTH = 1.0F;
  private static final float[] TRAIL_STEPS = {1.0F, 0.78F, 0.6F, 1.25F, 1.5F};
  private static final float TRAIL_ELBOW_ROOM = 0.35F;

  private static final float MOST_SWEEP = 1.4F;
  private static final float SWEEP_PER_BEND = 1.2F;
  private static final float FULL_CURVE_HALF_BLOCKS = 5.0F;

  private static final float SKY_LEAST_DISTANCE = 70.0F;
  private static final float SKY_DISTANCE = 6.0F;
  private static final float SKY_HEIGHT = 38.0F;
  private static final float SKY_RAISE = 40.0F;
  private static final float SKY_ROOF_CHECK = 16.0F;
  private static final float SKY_SIZE = 1.6F;
  private static final float SKY_WANDER = 0.18F;

  private static final float WALL_REACH = 2.5F;
  private static final float WALL_LEAST_REACH = 48.0F;
  private static final float WALL_LEAST_DISTANCE = 1.6F;
  private static final float WALL_OFF_SURFACE = 0.3F;
  private static final float FLOOR_DISTANCE = 0.7F;
  private static final float FLOOR_SIZE = 1.25F;
  private static final float LYING_STRETCH = 1.6F;

  private static final float ORBIT_GAP_DEGREES = 10.0F;
  private static final float ORBIT_MOST_WIDTH_DEGREES = 150.0F;
  private static final float ORBIT_BEHIND_DEGREES = 110.0F;

  private static final float QUARTER_TURN = (float) Math.PI * 0.5F;

  private static final float DUET_AIR = 0.6F;

  private int side = 1;
  private boolean orbiting;
  private float orbitYaw;
  private float orbitHalf;

  private double spotX;
  private double spotZ;
  private float spotShrink;

  void forget() {
    this.side = 1;
    this.orbiting = false;
  }

  void place(LineSlot slot, Look look, Camera camera, ClientLevel level, float heightPx, List<LineSlot> others) {
    float widthPx = slot.widthPx;
    slot.placed = true;
    slot.standing = true;
    slot.onFoot = false;
    slot.bobbing = false;
    slot.thin = false;
    slot.farAway = false;
    slot.stretch = 1.0F;
    slot.orbitRadius = 0.0F;
    slot.hasGround = false;
    slot.hasFloor = false;
    slot.columnsValid = false;
    slot.turning = false;
    slot.bendEase = 1.0F;
    slot.lightAge = 0;
    slot.settleY = 0.0;
    slot.waitingForChunk = false;
    switch (look.place) {
      case "HOVER" -> this.hover(slot, look, camera, level, widthPx, heightPx);
      case "SKY" -> this.sky(slot, look, camera, level, widthPx, heightPx);
      case "WALL" -> this.wall(slot, look, camera, level, widthPx, heightPx);
      case "FLOOR" -> this.floor(slot, look, camera, level, widthPx, heightPx);
      case "ORBIT" -> this.orbit(slot, look, camera, level, widthPx, heightPx);
      case "TRAIL" -> this.stand(slot, look, camera, level, widthPx, heightPx, trailStep(slot, look, camera, others));
      default -> this.stand(slot, look, camera, level, widthPx, heightPx, 1.0F);
    }

    if (slot.standing) {
      slot.orient.rotationY(slot.yaw);
    }

    findColumns(slot, look, level);
  }

  /** How strongly a line curves, in radians per glyph pixel: the band of an orbit, otherwise the look's bend. */
  static float curvature(LineSlot slot, Look look) {
    if (slot.orbitRadius > 0.0F) {
      return slot.scale / slot.orbitRadius;
    }

    float half = slot.widthPx * 0.5F;
    if (!slot.standing || half < 1.0F) {
      return 0.0F;
    }

    // A short word on the same sweep as a long row would look like a bowl rather than part of a big curve.
    float presence = Math.min(1.0F, half * slot.scale / FULL_CURVE_HALF_BLOCKS);
    float sweep = Math.min(MOST_SWEEP, SWEEP_PER_BEND * look.bend * presence);
    return sweep / half * slot.bendEase;
  }

  /** The ground under each part of the line, so a shadow follows steps instead of floating over them. */
  static void findColumns(LineSlot slot, Look look, ClientLevel level) {
    slot.columnsValid = true;
    if (!slot.hasGround || !slot.standing) {
      return;
    }

    float sin = (float) Math.sin(slot.yaw);
    float cos = (float) Math.cos(slot.yaw);
    float halfLength = slot.widthPx * 0.5F * slot.scale + SHADOW_OVERHANG;
    float curve = curvature(slot, look) / slot.scale;
    for (int i = 0; i < SoftLight.SHADOW_COLUMNS; i++) {
      float along = SoftLight.columnAt(i) * halfLength;
      float x = curve == 0.0F ? along : (float) Math.sin(along * curve) / curve;
      float z = curve == 0.0F ? 0.0F : (1.0F - (float) Math.cos(along * curve)) / curve;
      double worldX = slot.anchorX + x * cos + z * sin;
      double worldZ = slot.anchorZ - x * sin + z * cos;
      double found = groundBelow(level, worldX, slot.groundY + COLUMN_REACH, worldZ, COLUMN_REACH * 2.0F, false);
      slot.groundColumns[i] = Double.isNaN(found) ? Float.NaN : (float) (found - slot.groundY);
    }
  }

  /** The height of what stops a fall from here, or NaN. Water holds a line up unless the player is under water too. */
  static double groundBelow(ClientLevel level, double x, double fromY, double z, float reach, boolean throughWater) {
    ClipContext.Fluid fluid = throughWater ? ClipContext.Fluid.NONE : ClipContext.Fluid.SOURCE_ONLY;
    BlockHitResult hit = level.clip(new ClipContext(new Vec3(x, fromY, z), new Vec3(x, fromY - reach, z),
        ClipContext.Block.COLLIDER, fluid, CollisionContext.empty()));
    return hit.getType() == HitResult.Type.BLOCK ? hit.getLocation().y : Double.NaN;
  }

  static boolean submerged(Camera camera) {
    return camera.getFluidInCamera() != FogType.NONE;
  }

  // The places

  private void stand(LineSlot slot, Look look, Camera camera, ClientLevel level, float widthPx, float heightPx,
      float distanceStep) {
    Vec3 eye = camera.position();
    slot.onFoot = true;
    float wanted = look.distance * distanceStep;
    float size = BLOCKS_PER_PIXEL * wanted / DEFAULT_DISTANCE;
    this.walkOut(slot, look, camera, level, wanted, size, widthPx);
    slot.scale = size * this.spotShrink;
    slot.anchorX = this.spotX;
    slot.anchorZ = this.spotZ;
    faceEye(slot, eye, leanOf(slot));
    settleOnGround(slot, look, camera, level, eye.y, heightPx);

    float half = widthPx * slot.scale * 0.5F + SIDE_AIR;
    float tangentX = (float) Math.cos(slot.yaw);
    float tangentZ = -(float) Math.sin(slot.yaw);
    if (this.fitFootprint(slot, look, level, half, tangentX, tangentZ)) {
      settleOnGround(slot, look, camera, level, eye.y, heightPx);
    }

    restOnHighestGround(slot, look, level, eye.y);

    float tall = heightPx * slot.scale;
    float room = free(level, slot.anchorX, slot.anchorY + 0.05, slot.anchorZ, 0.0F, 1.0F, 0.0F, tall + TOP_AIR * 2.0F);
    if (room < tall + TOP_AIR) {
      slot.scale *= Math.max(LEAST_FIT, (room - TOP_AIR) / tall);
    }
  }

  private void hover(LineSlot slot, Look look, Camera camera, ClientLevel level, float widthPx, float heightPx) {
    Vec3 eye = camera.position();
    slot.bobbing = true;
    float size = BLOCKS_PER_PIXEL * look.distance / DEFAULT_DISTANCE;
    this.walkOut(slot, look, camera, level, look.distance, size, widthPx);
    slot.scale = size * this.spotShrink;
    slot.anchorX = this.spotX;
    slot.anchorY = eye.y + look.heightAdd;
    slot.anchorZ = this.spotZ;
    faceEye(slot, eye, leanOf(slot));
    float half = widthPx * slot.scale * 0.5F + SIDE_AIR;
    fitAlong(slot, level, slot.anchorX, slot.anchorY, slot.anchorZ, (float) Math.cos(slot.yaw), 0.0F,
        -(float) Math.sin(slot.yaw), half);
    half = heightPx * slot.scale * 0.5F + TOP_AIR;
    fitAlong(slot, level, slot.anchorX, slot.anchorY, slot.anchorZ, 0.0F, 1.0F, 0.0F, half);
    findShadowGround(slot, camera, level);
    slot.hasFloor = slot.hasGround;
    slot.floorY = slot.groundY;
  }

  private void sky(LineSlot slot, Look look, Camera camera, ClientLevel level, float widthPx, float heightPx) {
    Vec3 eye = camera.position();
    // Under a roof the words would be up there behind the rock for good.
    if (free(level, eye.x, eye.y, eye.z, 0.0F, 1.0F, 0.0F, SKY_ROOF_CHECK) < SKY_ROOF_CHECK) {
      this.hover(slot, look, camera, level, widthPx, heightPx);
      return;
    }

    float yawRad = (float) Math.toRadians(camera.yRot());
    float forwardX = -(float) Math.sin(yawRad);
    float forwardZ = (float) Math.cos(yawRad);
    float distance = Math.max(SKY_LEAST_DISTANCE, look.distance * SKY_DISTANCE);
    int turn = this.nextSide();
    float wander = turn * look.spread * distance * SKY_WANDER * Motions.noise(slot.seed, 11, 0);
    double x = eye.x + forwardX * distance - forwardZ * wander;
    double z = eye.z + forwardZ * distance + forwardX * wander;
    double y = eye.y + SKY_HEIGHT + look.heightAdd;
    if (blocked(level, eye, x, y, z)) {
      y += SKY_RAISE;
    }

    slot.farAway = true;
    slot.anchorX = x;
    slot.anchorY = y;
    slot.anchorZ = z;
    slot.scale = BLOCKS_PER_PIXEL * distance / DEFAULT_DISTANCE * SKY_SIZE;
    faceEye(slot, eye, 0.0F);
  }

  private void wall(LineSlot slot, Look look, Camera camera, ClientLevel level, float widthPx, float heightPx) {
    Vec3 eye = camera.position();
    float reach = Math.max(WALL_LEAST_REACH, look.distance * WALL_REACH);
    Vector3fc forward = camera.forwardVector();
    Vec3 far = eye.add(forward.x() * reach, forward.y() * reach, forward.z() * reach);
    BlockHitResult hit = level.clip(new ClipContext(eye, far, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
        CollisionContext.empty()));
    if (hit.getType() != HitResult.Type.BLOCK) {
      this.stand(slot, look, camera, level, widthPx, heightPx, 1.0F);
      return;
    }

    Vec3 at = hit.getLocation();
    Direction face = hit.getDirection();
    float distance = Math.max(WALL_LEAST_DISTANCE, (float) at.distanceTo(eye));
    slot.scale = BLOCKS_PER_PIXEL * distance / DEFAULT_DISTANCE;
    slot.thin = true;
    slot.standing = false;
    float off = Math.min(look.depth * 0.3F, THIN_BLOCKS) * 0.5F + SURFACE_AIR;
    slot.anchorX = at.x + face.getStepX() * off;
    slot.anchorY = at.y + face.getStepY() * off;
    slot.anchorZ = at.z + face.getStepZ() * off;

    if (face.getAxis() == Direction.Axis.Y) {
      slot.yaw = viewYaw(eye, at.x, at.z);
      slot.stretch = LYING_STRETCH;
      slot.hasFloor = face == Direction.UP;
      slot.floorY = at.y;
      // On a floor the tops of the letters point away from the viewer, on a ceiling toward them: both read upright.
      slot.orient.rotationY(slot.yaw).rotateX(face == Direction.UP ? -QUARTER_TURN : QUARTER_TURN);
      return;
    }

    slot.yaw = (float) Math.atan2(face.getStepX(), face.getStepZ());
    slot.orient.rotationY(slot.yaw);
    // From a little off the surface, or the wall itself would count as being in the way.
    double fromX = slot.anchorX + face.getStepX() * WALL_OFF_SURFACE;
    double fromZ = slot.anchorZ + face.getStepZ() * WALL_OFF_SURFACE;
    float half = widthPx * slot.scale * 0.5F + SIDE_AIR;
    fitAlong(slot, level, fromX, slot.anchorY, fromZ, (float) Math.cos(slot.yaw), 0.0F, -(float) Math.sin(slot.yaw),
        half);
    half = heightPx * slot.scale * 0.5F + TOP_AIR;
    fitAlong(slot, level, fromX, slot.anchorY, fromZ, 0.0F, 1.0F, 0.0F, half);
  }

  private void floor(LineSlot slot, Look look, Camera camera, ClientLevel level, float widthPx, float heightPx) {
    Vec3 eye = camera.position();
    float wanted = look.distance * FLOOR_DISTANCE;
    float size = BLOCKS_PER_PIXEL * wanted / DEFAULT_DISTANCE * FLOOR_SIZE;
    this.walkOut(slot, look, camera, level, wanted, size, widthPx);
    double ground = groundBelow(level, this.spotX, eye.y, this.spotZ, GROUND_REACH, submerged(camera));
    if (Double.isNaN(ground)) {
      this.hover(slot, look, camera, level, widthPx, heightPx);
      return;
    }

    slot.scale = size * this.spotShrink;
    slot.thin = true;
    slot.standing = false;
    slot.stretch = LYING_STRETCH;
    slot.anchorX = this.spotX;
    slot.anchorY = ground + Math.min(look.depth * 0.3F, THIN_BLOCKS) * 0.5F + SURFACE_AIR;
    slot.hasFloor = true;
    slot.floorY = ground;
    slot.anchorZ = this.spotZ;
    slot.yaw = viewYaw(eye, slot.anchorX, slot.anchorZ);
    slot.orient.rotationY(slot.yaw).rotateX(-QUARTER_TURN);
    float half = widthPx * slot.scale * 0.5F + SIDE_AIR;
    fitAlong(slot, level, slot.anchorX, slot.anchorY + PROBE_HEIGHT, slot.anchorZ, (float) Math.cos(slot.yaw), 0.0F,
        -(float) Math.sin(slot.yaw), half);
  }

  private void orbit(LineSlot slot, Look look, Camera camera, ClientLevel level, float widthPx, float heightPx) {
    Vec3 eye = camera.position();
    float radius = look.distance;
    float size = BLOCKS_PER_PIXEL * radius / DEFAULT_DISTANCE;
    float half = Math.min(ORBIT_MOST_WIDTH_DEGREES, (float) Math.toDegrees(widthPx * size / radius)) * 0.5F;
    float view = camera.yRot();
    if (!this.orbiting || Math.abs(wrapDegrees(this.orbitYaw - view)) > ORBIT_BEHIND_DEGREES) {
      // Going further round would put the next line out of sight, so the band starts over where the player looks.
      this.orbitYaw = view;
      this.orbiting = true;
    } else {
      this.orbitYaw += this.orbitHalf + half + ORBIT_GAP_DEGREES;
    }

    this.orbitHalf = half;
    float yawRad = (float) Math.toRadians(this.orbitYaw);
    double x = eye.x - Math.sin(yawRad) * radius;
    double z = eye.z + Math.cos(yawRad) * radius;
    double y = eye.y + look.heightAdd;
    float shrink = shrinkTo(level, eye, x, y, z, radius);
    slot.orbitRadius = radius * shrink;
    slot.scale = size * shrink;
    slot.anchorX = eye.x + (x - eye.x) * shrink;
    slot.anchorY = eye.y + (y - eye.y) * shrink;
    slot.anchorZ = eye.z + (z - eye.z) * shrink;
    faceEye(slot, eye, 0.0F);
    float halfHeight = heightPx * slot.scale * 0.5F + TOP_AIR;
    fitAlong(slot, level, slot.anchorX, slot.anchorY, slot.anchorZ, 0.0F, 1.0F, 0.0F, halfHeight);
    findShadowGround(slot, camera, level);
    slot.hasFloor = slot.hasGround;
    slot.floorY = slot.groundY;
  }

  // Parts

  /** Picks the spot ahead of where the player looks, on this line's side, and keeps it out of whatever is in the way. */
  private void walkOut(LineSlot slot, Look look, Camera camera, ClientLevel level, float wanted, float size,
      float widthPx) {
    Vec3 eye = camera.position();
    float yawRad = (float) Math.toRadians(camera.yRot());
    float forwardX = -(float) Math.sin(yawRad);
    float forwardZ = (float) Math.cos(yawRad);
    // Each singer of a duet has their own half of the view, as their lines can be sung at once. The dots have no line.
    int voice = slot.line == null ? 0 : slot.line.voice();
    int turn = voice == 0 ? this.nextSide() : voice % 2 == 1 ? -1 : 1;
    float forward = turn > 0 ? wanted : wanted * FAR_TURN;
    float room = Math.max(0.0F, wanted * VIEW_HALF_WIDTH - widthPx * size * 0.5F);
    float lateral = voice != 0
        ? turn * (widthPx * size * 0.5F + DUET_AIR)
        : turn * look.spread * (WANDER_BASE * wanted + WANDER_ROOM * room * Motions.noise(slot.seed, 13, 0));
    double x = eye.x + forwardX * forward - forwardZ * lateral;
    double z = eye.z + forwardZ * forward + forwardX * lateral;
    this.spotShrink = shrinkTo(level, eye, x, eye.y, z, (float) Math.sqrt(forward * forward + lateral * lateral));
    this.spotX = eye.x + (x - eye.x) * this.spotShrink;
    this.spotZ = eye.z + (z - eye.z) * this.spotShrink;
  }

  /** How far along the way from the eye a line can go, as a part of that way, stopping before what blocks it. */
  private static float shrinkTo(ClientLevel level, Vec3 eye, double x, double y, double z, float length) {
    BlockHitResult hit = level.clip(new ClipContext(eye, new Vec3(x, y, z), ClipContext.Block.COLLIDER,
        ClipContext.Fluid.NONE, CollisionContext.empty()));
    if (hit.getType() != HitResult.Type.BLOCK) {
      return 1.0F;
    }

    float open = (float) hit.getLocation().distanceTo(eye);
    return Math.max(LEAST_SHRINK, Math.max(LEAST_ROOM, open - BEFORE_HIT) / Math.max(length, 0.01F));
  }

  private static void settleOnGround(LineSlot slot, Look look, Camera camera, ClientLevel level, double eyeY,
      float heightPx) {
    double ground = groundBelow(level, slot.anchorX, eyeY, slot.anchorZ, GROUND_REACH, submerged(camera));
    if (Double.isNaN(ground)) {
      slot.hasGround = false;
      slot.waitingForChunk = !chunkLoaded(slot, level);
      slot.anchorY = eyeY - WITHOUT_GROUND_DROP - heightPx * slot.scale * 0.5F + look.heightAdd;
    } else {
      slot.hasGround = true;
      slot.waitingForChunk = false;
      slot.groundY = ground;
      // Never below the ground: the looks for walls would all begin inside something, and so find none.
      slot.anchorY = ground + CLEARANCE + Math.max(0.0F, look.heightAdd);
    }
  }

  /** An absent chunk reads as air, which cannot be told from the void by looking at its blocks. */
  static boolean chunkLoaded(LineSlot slot, ClientLevel level) {
    return level.hasChunk((int) Math.floor(slot.anchorX) >> 4, (int) Math.floor(slot.anchorZ) >> 4);
  }

  /**
   * Moves a line along itself away from any wall in its way, and shrinks it if it still will not fit. It looks along
   * lines through the whole depth of the footprint, as a wall that misses the middle of the line can still catch its
   * curved ends or its thickness. A look that begins inside something says nothing.
   */
  private boolean fitFootprint(LineSlot slot, Look look, ClientLevel level, float half, float tangentX, float tangentZ) {
    float y = (float) (slot.anchorY - Math.floor(slot.anchorY)) + stepProbe(slot, level);
    double base = Math.floor(slot.anchorY);
    float across = footprintDepth(slot, look, half);
    float normalX = -tangentZ;
    float normalZ = tangentX;
    float plus = half;
    float minus = half;
    for (int i = 0; i < FIT_LOOKS; i++) {
      float out = across * (i * 2.0F / (FIT_LOOKS - 1) - 1.0F);
      double x = slot.anchorX + normalX * out;
      double z = slot.anchorZ + normalZ * out;
      float one = freeFromOutside(level, x, base + y, z, tangentX, tangentZ, half);
      if (one >= 0.0F) {
        plus = Math.min(plus, one);
      }

      float other = freeFromOutside(level, x, base + y, z, -tangentX, -tangentZ, half);
      if (other >= 0.0F) {
        minus = Math.min(minus, other);
      }
    }

    return applyFit(slot, tangentX, 0.0F, tangentZ, half, plus, minus);
  }

  private static float freeFromOutside(ClientLevel level, double x, double y, double z, float dirX, float dirZ,
      float length) {
    Vec3 from = new Vec3(x, y, z);
    Vec3 to = new Vec3(x + dirX * length, y, z + dirZ * length);
    BlockHitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
        CollisionContext.empty()));
    if (hit.getType() != HitResult.Type.BLOCK) {
      return length;
    }

    return hit.isInside() ? -1.0F : (float) hit.getLocation().distanceTo(from);
  }

  /** How far the line reaches to either side of its middle: its thickness, the curve of its ends, stairs of words. */
  private static float footprintDepth(LineSlot slot, Look look, float half) {
    float curve = curvature(slot, look) / slot.scale;
    float bulge = curve <= 0.0F ? 0.0F : (1.0F - (float) Math.cos(half * curve)) / curve;
    ComposedLine composed = slot.composed;
    float reach = composed == null ? 0.0F : Math.max(composed.restFront, -composed.restBack) * slot.scale;
    float thick = look.depth * 0.3F * look.size * slot.scale / BLOCKS_PER_PIXEL;
    return thick * 0.5F + reach + bulge + FOOT_AIR;
  }

  /** Up to where a sideways look for what is in the way can go, if the line is under something low. */
  private static float stepProbe(LineSlot slot, ClientLevel level) {
    float room = free(level, slot.anchorX, slot.anchorY + 0.05, slot.anchorZ, 0.0F, 1.0F, 0.0F, STEP_PROBE + 1.0F);
    return Math.max(LEAST_PROBE, Math.min(STEP_PROBE, room - PROBE_BELOW_ROOF));
  }

  /**
   * Stands a line on the highest solid ground under its whole width and depth, so that nothing of it sinks into a slope
   * or a step anywhere along it. A line wider than the flat ground rises rather than cuts into it. This looks once,
   * when the line is placed: it stays where it is however the player moves.
   */
  static void restOnHighestGround(LineSlot slot, Look look, ClientLevel level, double eyeY) {
    if (!slot.hasGround) {
      // Nothing under its middle, over a drop or the void, but its ends can still reach over land and must not cut in.
      slot.hasFloor = false;
      slot.groundY = slot.anchorY;
      double land = highestUnder(slot, look, level, eyeY);
      if (land > slot.anchorY) {
        slot.hasFloor = true;
        slot.floorY = land;
        slot.anchorY = land + CLEARANCE;
      }

      return;
    }

    double highest = highestUnder(slot, look, level, eyeY);
    slot.hasFloor = true;
    slot.floorY = highest;
    slot.anchorY = highest + CLEARANCE + Math.max(0.0F, look.heightAdd);
  }

  /** Samples a grid of block columns over the line's footprint. It takes in both sides, as a line can flip. */
  private static double highestUnder(LineSlot slot, Look look, ClientLevel level, double eyeY) {
    float half = slot.widthPx * slot.scale * 0.5F + FOOT_AIR;
    float across = footprintDepth(slot, look, half);

    int alongCount = Math.max(2, Math.min(MOST_ALONG, (int) Math.ceil(half * 2.0F / FOOT_STEP) + 1));
    int acrossCount = Math.max(2, Math.min(MOST_ACROSS, (int) Math.ceil(across * 2.0F / FOOT_STEP) + 1));
    double tangentX = Math.cos(slot.yaw);
    double tangentZ = -Math.sin(slot.yaw);
    double normalX = Math.sin(slot.yaw);
    double normalZ = Math.cos(slot.yaw);
    double limit = slot.groundY + RISE_LIMIT;
    double fromY = Math.min(eyeY, limit);
    double highest = slot.groundY;
    BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
    for (int i = 0; i < alongCount; i++) {
      double along = half * (i * 2.0 / (alongCount - 1) - 1.0);
      int lastX = Integer.MIN_VALUE;
      int lastZ = Integer.MIN_VALUE;
      for (int j = 0; j < acrossCount; j++) {
        double out = across * (j * 2.0 / (acrossCount - 1) - 1.0);
        int x = (int) Math.floor(slot.anchorX + tangentX * along + normalX * out);
        int z = (int) Math.floor(slot.anchorZ + tangentZ * along + normalZ * out);
        if (x != lastX || z != lastZ) {
          lastX = x;
          lastZ = z;
          double top = columnTop(level, at, x, z, fromY, highest, limit);
          if (top > highest && top <= limit) {
            highest = top;
          }
        }
      }
    }

    return highest;
  }

  /** The top of what a fall from {@code fromY} would land on, or if that spot is inside something, the top of that. */
  private static double columnTop(ClientLevel level, BlockPos.MutableBlockPos at, int x, int z, double fromY,
      double lowestWanted, double limit) {
    int y = (int) Math.floor(fromY);
    VoxelShape shape = level.getBlockState(at.set(x, y, z)).getCollisionShape(level, at);
    if (!shape.isEmpty()) {
      double top = y + shape.max(Direction.Axis.Y);
      while (top <= limit) {
        y++;
        shape = level.getBlockState(at.set(x, y, z)).getCollisionShape(level, at);
        if (shape.isEmpty()) {
          break;
        }

        top = y + shape.max(Direction.Axis.Y);
      }

      return top;
    }

    int stop = (int) Math.floor(lowestWanted);
    for (y--; y >= stop; y--) {
      shape = level.getBlockState(at.set(x, y, z)).getCollisionShape(level, at);
      if (!shape.isEmpty()) {
        return y + shape.max(Direction.Axis.Y);
      }
    }

    return Double.NEGATIVE_INFINITY;
  }

  private static void findShadowGround(LineSlot slot, Camera camera, ClientLevel level) {
    double ground = groundBelow(level, slot.anchorX, slot.anchorY, slot.anchorZ, SHADOW_REACH, submerged(camera));
    slot.hasGround = !Double.isNaN(ground);
    slot.groundY = slot.hasGround ? ground : 0.0;
  }

  /** Slides a line away from what it would touch along an axis, and shrinks it if there is still too little room. */
  private static boolean fitAlong(LineSlot slot, ClientLevel level, double x, double y, double z, float dirX, float dirY,
      float dirZ, float half) {
    float plus = free(level, x, y, z, dirX, dirY, dirZ, half);
    float minus = free(level, x, y, z, -dirX, -dirY, -dirZ, half);
    return applyFit(slot, dirX, dirY, dirZ, half, plus, minus);
  }

  private static boolean applyFit(LineSlot slot, float dirX, float dirY, float dirZ, float half, float plus,
      float minus) {
    if (plus >= half && minus >= half) {
      return false;
    }

    float fitted = Math.min(half, (plus + minus) * 0.5F);
    float shift = 0.0F;
    if (minus < fitted) {
      shift = fitted - minus;
    } else if (plus < fitted) {
      shift = plus - fitted;
    }

    slot.anchorX += dirX * shift;
    slot.anchorY += dirY * shift;
    slot.anchorZ += dirZ * shift;
    slot.scale *= Math.max(LEAST_FIT, fitted / half);
    return true;
  }

  private static float free(ClientLevel level, double x, double y, double z, float dirX, float dirY, float dirZ,
      float length) {
    Vec3 from = new Vec3(x, y, z);
    Vec3 to = new Vec3(x + dirX * length, y + dirY * length, z + dirZ * length);
    BlockHitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
        CollisionContext.empty()));
    return hit.getType() != HitResult.Type.BLOCK ? length : (float) hit.getLocation().distanceTo(from);
  }

  private static boolean blocked(ClientLevel level, Vec3 eye, double x, double y, double z) {
    return level.clip(new ClipContext(eye, new Vec3(x, y, z), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
        CollisionContext.empty())).getType() == HitResult.Type.BLOCK;
  }

  private static float trailStep(LineSlot slot, Look look, Camera camera, List<LineSlot> others) {
    Vec3 eye = camera.position();
    float yawRad = (float) Math.toRadians(camera.yRot());
    double forwardX = -Math.sin(yawRad);
    double forwardZ = Math.cos(yawRad);
    double elbow = look.distance * TRAIL_ELBOW_ROOM;
    // An older line that still stands here would hide the new one, so try nearer first, then past it.
    for (float step : TRAIL_STEPS) {
      double x = eye.x + forwardX * look.distance * step;
      double z = eye.z + forwardZ * look.distance * step;
      boolean taken = false;
      for (int i = 0; i < others.size() && !taken; i++) {
        LineSlot other = others.get(i);
        double dx = other.anchorX - x;
        double dz = other.anchorZ - z;
        taken = other != slot && other.placed && dx * dx + dz * dz < elbow * elbow;
      }

      if (!taken) {
        return step;
      }
    }

    return 1.0F;
  }

  private int nextSide() {
    int turn = this.side;
    this.side = -turn;
    return turn;
  }

  private static float leanOf(LineSlot slot) {
    return (Motions.noise(slot.seed, 17, 0) * 2.0F - 1.0F) * LEAN_RADIANS;
  }

  private static void faceEye(LineSlot slot, Vec3 eye, float lean) {
    slot.yaw = viewYaw(eye, slot.anchorX, slot.anchorZ) + lean;
  }

  private static float viewYaw(Vec3 eye, double x, double z) {
    return (float) Math.atan2(eye.x - x, eye.z - z);
  }

  private static float wrapDegrees(float degrees) {
    float wrapped = degrees % 360.0F;
    if (wrapped > 180.0F) {
      wrapped -= 360.0F;
    } else if (wrapped < -180.0F) {
      wrapped += 360.0F;
    }

    return wrapped;
  }
}
