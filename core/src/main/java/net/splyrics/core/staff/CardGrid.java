package net.splyrics.core.staff;

import net.labymod.api.Laby;
import net.labymod.api.client.gui.window.Window;
import net.labymod.api.loader.MinecraftVersions;
import org.joml.Matrix4f;

/**
 * Where the screen's pixels fall on a card this frame. A tag always faces the camera, so its plane maps onto the screen
 * by one scale and one shift, and every straight edge can land exactly between two pixels: with the soft step of a
 * shape reaching half a pixel to each side of the edge, such an edge comes out hard, while a curve keeps its blending.
 */
final class CardGrid {

  private static final float SMALLEST_DEPTH = 0.05F;
  private static final float USUAL_FOV_DEGREES = 70.0F;
  // In blocks. The depth buffer gets coarser with the square of the distance, and a layer brought further forward than
  // needed shows: nearer also means bigger, by enough to move an edge off its pixel.
  private static final float DEPTH_STEP_CLOSEST = 1.0E-4F;
  private static final float DEPTH_STEP_PER_SQUARE_BLOCK = 1.0E-5F;
  /** Which way a tag's own depth axis points: at the viewer now, away from the viewer in 1.20.6 and older. */
  private static final float TOWARD_VIEWER = MinecraftVersions.V1_20_6.orOlder() ? -1.0F : 1.0F;

  /** Screen pixels per text pixel. */
  final float perUnit;
  /** One screen pixel, in text pixels. */
  final float pixel;
  /** What to add to the tag's own depth to bring one layer of the card just in front of the one under it. */
  final float depthStep;

  private final float shiftX;
  private final float shiftY;

  private CardGrid(float perUnit, float distance, float lengthZ, float shiftX, float shiftY) {
    this.perUnit = perUnit;
    this.pixel = 1.0F / perUnit;
    this.depthStep = TOWARD_VIEWER * Math.max(DEPTH_STEP_CLOSEST, distance * distance * DEPTH_STEP_PER_SQUARE_BLOCK)
        / lengthZ;
    this.shiftX = shiftX;
    this.shiftY = shiftY;
  }

  /** @param pose the tag's matrix: its origin is the card's top-left corner, its axes run along the screen */
  static CardGrid of(Matrix4f pose) {
    float tx = pose.m30();
    float ty = pose.m31();
    float tz = pose.m32();
    float distance = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
    float lengthX = length(pose.m00(), pose.m01(), pose.m02());
    float lengthY = length(pose.m10(), pose.m11(), pose.m12());
    float lengthZ = length(pose.m20(), pose.m21(), pose.m22());
    float across = (tx * pose.m00() + ty * pose.m01() + tz * pose.m02()) / lengthX;
    float down = (tx * pose.m10() + ty * pose.m11() + tz * pose.m12()) / lengthY;
    // How far in front of the camera. Without the version's sign, every tag on an old version would seem to be behind it
    float depth = -TOWARD_VIEWER * (tx * pose.m20() + ty * pose.m21() + tz * pose.m22()) / lengthZ;

    Window window = Laby.labyAPI().minecraft().minecraftWindow();
    int screenWidth = window.getRawWidth();
    int screenHeight = window.getRawHeight();
    if (depth < SMALLEST_DEPTH) {
      return new CardGrid(Math.max(1.0F, screenHeight * lengthX), distance, lengthZ, 0.0F, 0.0F);
    }

    Matrix4f projection = Laby.references().gameTransformations().projectionMatrix();
    float screenX;
    float screenY;
    float focal;
    if (projection.m33() == 0.0F && projection.m23() != 0.0F) {
      float viewY = -down;
      float viewZ = -depth;
      float w = projection.m03() * across + projection.m13() * viewY + projection.m23() * viewZ + projection.m33();
      float clipX = projection.m00() * across + projection.m10() * viewY + projection.m20() * viewZ + projection.m30();
      float clipY = projection.m01() * across + projection.m11() * viewY + projection.m21() * viewZ + projection.m31();
      screenX = (clipX / w + 1.0F) * 0.5F * screenWidth;
      screenY = (1.0F - clipY / w) * 0.5F * screenHeight;
      focal = projection.m11() * screenHeight * 0.5F;
    } else {
      // Not the world's projection at this moment: the option's field of view gives nearly the same answer
      double fov = Laby.labyAPI().minecraft().options().getFov();
      focal = (float) (screenHeight * 0.5 / Math.tan(Math.toRadians(fov > 0.0 ? fov : USUAL_FOV_DEGREES) * 0.5));
      screenX = screenWidth * 0.5F + across / depth * focal;
      screenY = screenHeight * 0.5F + down / depth * focal;
    }

    float perUnit = Math.max(0.01F, focal * lengthX / depth);
    return new CardGrid(perUnit, distance, lengthZ, (Math.round(screenX) - screenX) / perUnit,
        (Math.round(screenY) - screenY) / perUnit);
  }

  private static float length(float x, float y, float z) {
    return (float) Math.sqrt(x * x + y * y + z * z);
  }

  /** The pixel boundary nearest a position across the card, in text pixels. */
  float x(float at) {
    return Math.round(at * this.perUnit) * this.pixel + this.shiftX;
  }

  /** The pixel boundary nearest a position down the card, in text pixels. */
  float y(float at) {
    return Math.round(at * this.perUnit) * this.pixel + this.shiftY;
  }

  /** A length as a whole number of pixels, at least one. */
  float size(float length) {
    return Math.max(1, Math.round(length * this.perUnit)) * this.pixel;
  }
}
