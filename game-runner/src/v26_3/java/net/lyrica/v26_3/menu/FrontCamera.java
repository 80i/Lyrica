package net.lyrica.v26_3.menu;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;

/**
 * Turns the camera to the player's front, which puts the head and what stands over it in the world above the desk, and
 * back. A page has it only while that page is on screen: the screen's closing and a change of tab both come through the
 * page's {@code hidden()}, which covers closing, dying, changing world and disconnecting, as the game goes through
 * {@code setScreen} for all of them.
 */
final class FrontCamera {

  private CameraType before;

  /** Whether the camera is turned right now. */
  boolean turned() {
    return this.before != null;
  }

  void turn() {
    if (this.before == null) {
      Minecraft client = Minecraft.getInstance();
      this.before = client.options.getCameraType();
      client.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
    }
  }

  void back() {
    if (this.before != null) {
      Minecraft.getInstance().options.setCameraType(this.before);
      this.before = null;
    }
  }
}
