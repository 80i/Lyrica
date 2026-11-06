package net.lyrica.v26_1_2.mixins;

import net.labymod.api.mixin.condition.RequiresDevEnvironment;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.RemoteChatSession;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * For the development test only, which has no second account and so no session Mojang signed. Only DevSmoke calls this,
 * and only in the development environment, which is also the only place this is applied.
 */
@RequiresDevEnvironment
@Mixin(PlayerInfo.class)
public interface PlayerInfoTestAccess {

  @Invoker("setChatSession")
  void lyrica$setChatSession(RemoteChatSession session);

  @Invoker("clearChatSession")
  void lyrica$clearChatSession(boolean enforcesSecureChat);
}
