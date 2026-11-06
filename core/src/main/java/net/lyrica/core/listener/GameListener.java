package net.lyrica.core.listener;

import net.labymod.api.client.gui.screen.key.Key;
import net.labymod.api.event.Phase;
import net.labymod.api.event.Subscribe;
import net.labymod.api.event.client.input.KeyEvent;
import net.labymod.api.event.client.lifecycle.GameTickEvent;
import net.labymod.api.event.client.render.GameRenderEvent;
import net.lyrica.api.LyricsBridge;
import net.lyrica.core.LookSettings;
import net.lyrica.core.LyricaAddon;
import net.lyrica.core.LyricaConfiguration;
import net.lyrica.core.share.SongShare;

/** Ticks the version side and hands it the key presses the addon reacts to. */
public class GameListener {

  private static final int SHARE_EVERY_TICKS = 20;

  private final LyricaAddon addon;
  private final SongShare share;
  private final LookSettings settings;
  private boolean running;
  private int ticks;

  public GameListener(LyricaAddon addon, SongShare share, LookSettings settings) {
    this.addon = addon;
    this.share = share;
    this.settings = settings;
    // enable() already started the lyrics when the addon was on
    this.running = addon.configuration().enabled().get();
  }

  @Subscribe
  public void onTick(GameTickEvent event) {
    if (event.phase() != Phase.POST) {
      return;
    }

    this.settings.follow();
    LyricaConfiguration config = this.addon.configuration();
    boolean wanted = config.enabled().get();
    if (wanted != this.running) {
      this.running = wanted;
      if (wanted) {
        this.addon.bridge().start();
      } else {
        this.addon.bridge().stop();
      }
    }

    if (wanted) {
      this.addon.bridge().tick();
      if (++this.ticks % SHARE_EVERY_TICKS == 0) {
        this.share.tick(this.addon.bridge());
      }
    }
  }

  @Subscribe
  public void onRender(GameRenderEvent event) {
    // Not before the frame: the main target has just been cleared by then
    if (event.phase() == Phase.POST && this.addon.bridge() != null) {
      this.addon.bridge().frame();
    }
  }

  @Subscribe
  public void onKey(KeyEvent event) {
    LyricaConfiguration config = this.addon.configuration();
    if (event.state() != KeyEvent.State.PRESS || !config.enabled().get()) {
      return;
    }

    Key key = event.key();
    if (key == null || key == Key.NONE) {
      return;
    }

    LyricsBridge bridge = this.addon.bridge();
    if (bridge == null || !bridge.canHandleKeys()) {
      return;
    }

    if (key == config.menuKey().get()) {
      // The menu opens after this key event is over, so the same press cannot reach and close it
      bridge.openMenu();
      event.setCancelled(true);
    } else if (key == config.syncEarlierKey().get()) {
      bridge.nudgeSync(-100);
      event.setCancelled(true);
    } else if (key == config.syncLaterKey().get()) {
      bridge.nudgeSync(100);
      event.setCancelled(true);
    } else if (key == config.hideKey().get()) {
      bridge.toggleLyrics();
      event.setCancelled(true);
    }
  }
}
