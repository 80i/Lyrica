package net.lyrica.v26_1_1.mixins;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.lyrica.v26_1_1.VersionedLyricsBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The now-playing widget and the on-screen lyrics go over the game's HUD. */
@Mixin(Gui.class)
public class MixinGui {

  @Inject(method = "extractRenderState", at = @At("TAIL"))
  private void lyrica$renderHud(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
    VersionedLyricsBridge bridge = VersionedLyricsBridge.get();
    if (bridge != null) {
      bridge.renderHud(graphics);
    }
  }
}
