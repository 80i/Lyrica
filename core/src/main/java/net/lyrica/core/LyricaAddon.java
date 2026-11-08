package net.lyrica.core;

import net.labymod.api.Laby;
import net.labymod.api.addon.LabyAddon;
import net.labymod.api.client.entity.player.tag.PositionType;
import net.labymod.api.client.gui.screen.key.Key;
import net.lyrica.api.generated.ReferenceStorage;
import net.labymod.api.models.addon.annotation.AddonMain;
import net.lyrica.api.LyricsBridge;
import net.lyrica.core.listener.GameListener;
import net.lyrica.core.share.SongShare;
import net.lyrica.core.staff.BadgeTag;
import net.lyrica.core.staff.SongTag;

/**
 * Lyrica: synced lyrics of whatever is playing on your PC, standing in the world around you or flat on your
 * screen, with a now-playing widget, karaoke, looks, and staff badges over the team's names.
 */
@AddonMain
public class LyricaAddon extends LabyAddon<LyricaConfiguration> {

  private static LyricaAddon instance;
  private LyricsBridge bridge;

  public static LyricaAddon get() {
    return instance;
  }

  @Override
  protected void enable() {
    instance = this;
    this.bridge = ((ReferenceStorage) this.referenceStorageAccessor()).lyricsBridge();
    this.registerSettingCategory();

    LyricaConfiguration config = this.configuration();
    this.bridge.menuKey(new LyricsBridge.MenuKeyAccess() {
      @Override
      public String name() {
        Key key = config.menuKey().get();
        return key == null || key == Key.NONE ? "none" : key.getName();
      }

      @Override
      public boolean isMenuKey(int keyCode) {
        Key key = config.menuKey().get();
        return key != null && key != Key.NONE && key.getId() == keyCode;
      }

      @Override
      public void bind(int keyCode) {
        config.menuKey().set(Key.get(keyCode));
      }
    });

    SongShare share = new SongShare();
    this.registerListener(share);
    this.registerListener(new GameListener(this, share, new LookSettings(this.bridge, config)));
    Laby.labyAPI().tagRegistry().register("lyrica_song", PositionType.ABOVE_NAME, new SongTag(this, share, false));
    // Before the badge, as this stack builds upward too: the title stays right under the name, and the card under that
    Laby.labyAPI().tagRegistry().register("lyrica_song_under", PositionType.BELOW_NAME, new SongTag(this, share, true));
    Laby.labyAPI().tagRegistry().register("lyrica_badge", PositionType.BELOW_NAME, new BadgeTag(this, share));

    if (config.enabled().get()) {
      this.bridge.start();
    }

    this.logger().info("Lyrica enabled");
  }

  public LyricsBridge bridge() {
    return this.bridge;
  }

  /** Opens the lyrics menu from the settings button. */
  public void openMenu() {
    if (this.bridge != null) {
      this.bridge.openMenu();
    }
  }

  @Override
  protected Class<LyricaConfiguration> configurationClass() {
    return LyricaConfiguration.class;
  }
}
