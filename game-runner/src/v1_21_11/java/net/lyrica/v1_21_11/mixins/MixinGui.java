package net.lyrica.v1_21_11.mixins;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.lyrica.v1_21_11.VersionedLyricsBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The now-playing widget and the on-screen lyrics go over the game's HUD. */
@Mixin(Gui.class)
public class MixinGui {

  @Inject(method = "render", at = @At("TAIL"))
  private void lyrica$renderHud(GuiGraphics graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
    VersionedLyricsBridge bridge = VersionedLyricsBridge.get();
    if (bridge != null) {
      bridge.renderHud(graphics);
    }
  }
}
