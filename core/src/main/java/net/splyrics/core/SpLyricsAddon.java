package net.splyrics.core;

import net.labymod.api.Laby;
import net.labymod.api.addon.LabyAddon;
import net.labymod.api.client.entity.player.tag.PositionType;
import net.labymod.api.client.gui.screen.key.Key;
import net.splyrics.api.generated.ReferenceStorage;
import net.labymod.api.models.addon.annotation.AddonMain;
import net.splyrics.api.LyricsBridge;
import net.splyrics.core.listener.GameListener;
import net.splyrics.core.share.SongShare;
import net.splyrics.core.staff.BadgeTag;
import net.splyrics.core.staff.SongTag;

/**
 * SpLyrics: synced lyrics of whatever is playing on your PC, standing in the world around you or flat on your
 * screen, with a now-playing widget, karaoke, looks, and staff badges over the team's names.
 */
@AddonMain
public class SpLyricsAddon extends LabyAddon<SpLyricsConfiguration> {

  private static SpLyricsAddon instance;
  private LyricsBridge bridge;

  public static SpLyricsAddon get() {
    return instance;
  }

  @Override
  protected void enable() {
    instance = this;
    this.bridge = ((ReferenceStorage) this.referenceStorageAccessor()).lyricsBridge();
    this.registerSettingCategory();

    SpLyricsConfiguration config = this.configuration();
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
    Laby.labyAPI().tagRegistry().register("splyrics_song", PositionType.ABOVE_NAME, new SongTag(this, share, false));
    // Before the badge, as this stack builds upward too: the title stays right under the name, and the card under that
    Laby.labyAPI().tagRegistry().register("splyrics_song_under", PositionType.BELOW_NAME, new SongTag(this, share, true));
    Laby.labyAPI().tagRegistry().register("splyrics_badge_over", PositionType.ABOVE_NAME, new BadgeTag(this, share, true));
    Laby.labyAPI().tagRegistry().register("splyrics_badge", PositionType.BELOW_NAME, new BadgeTag(this, share, false));

    if (config.enabled().get()) {
      this.bridge.start();
    }

    this.logger().info("SpLyrics enabled");
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
  protected Class<SpLyricsConfiguration> configurationClass() {
    return SpLyricsConfiguration.class;
  }
}
