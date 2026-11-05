package net.splyrics.v1_21_11.mixins;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.splyrics.v1_21_11.VersionedLyricsBridge;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The lyrics are drawn into the world right before the hand. By then the camera's rotation is no longer in the
 * model-view, so it goes back in here, or the words would turn against the camera instead of staying put in the
 * world. The view bobbing is already part of the projection, so the pose stack starts clean.
 */
@Mixin(GameRenderer.class)
public class MixinGameRenderer {

  @Unique
  private final PoseStack splyrics$pose = new PoseStack();

  @Inject(
      method = "renderLevel",
      at = @At(value = "INVOKE_STRING", target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V", args = "ldc=hand")
  )
  private void splyrics$renderLyrics(DeltaTracker deltaTracker, CallbackInfo ci) {
    VersionedLyricsBridge bridge = VersionedLyricsBridge.get();
    if (bridge == null) {
      return;
    }

    Camera camera = ((GameRenderer) (Object) this).getMainCamera();
    Matrix4fStack view = RenderSystem.getModelViewStack();
    view.pushMatrix();

    try {
      view.mul(new Matrix4f().rotation(new Quaternionf(camera.rotation()).conjugate()));
      bridge.renderWorld(this.splyrics$pose, camera);
    } finally {
      view.popMatrix();
    }
  }

  /** The end of the frame: the development test takes its screenshots here. */
  @Inject(method = "render", at = @At("TAIL"))
  private void splyrics$frameDone(DeltaTracker deltaTracker, boolean tick, CallbackInfo ci) {
    VersionedLyricsBridge bridge = VersionedLyricsBridge.get();
    if (bridge != null) {
      bridge.frame();
    }
  }
}
