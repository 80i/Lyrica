package net.lyrica.core;

import java.util.EnumMap;
import java.util.Map;
import net.labymod.api.addon.AddonConfig;
import net.labymod.api.client.gui.screen.key.Key;
import net.labymod.api.client.gui.screen.widget.widgets.input.ButtonWidget.ButtonSetting;
import net.labymod.api.client.gui.screen.widget.widgets.input.KeybindWidget.KeyBindSetting;
import net.labymod.api.client.gui.screen.widget.widgets.input.SliderWidget.SliderSetting;
import net.labymod.api.client.gui.screen.widget.widgets.input.SwitchWidget.SwitchSetting;
import net.labymod.api.configuration.loader.annotation.ConfigName;
import net.labymod.api.configuration.loader.property.ConfigProperty;
import net.labymod.api.configuration.settings.Setting;
import net.labymod.api.configuration.settings.annotation.SettingRequires;
import net.labymod.api.configuration.settings.annotation.SettingSection;
import net.labymod.api.util.MethodOrder;
import net.lyrica.api.LyricsBridge.LookSwitch;

/**
 * The addon's own settings in LabyMod's settings screen: on/off, the everyday settings, the keys, and a button to the
 * menu, where the look of the words, their place, the card and the staff list live. The everyday settings only mirror
 * the look, see {@link LookSettings}; their defaults are the look's.
 */
@ConfigName("settings")
public class LyricaConfiguration extends AddonConfig {

  @SwitchSetting
  private final ConfigProperty<Boolean> enabled = new ConfigProperty<>(true);

  @SettingSection("privacy")
  @SwitchSetting
  private final ConfigProperty<Boolean> spotifyOnly = new ConfigProperty<>(false);

  @SettingSection("lyrics")
  @SwitchSetting
  private final ConfigProperty<Boolean> showLyrics = new ConfigProperty<>(true);

  @SwitchSetting
  private final ConfigProperty<Boolean> hideWhenPaused = new ConfigProperty<>(true);

  @SwitchSetting
  private final ConfigProperty<Boolean> karaoke = new ConfigProperty<>(false);

  @SliderSetting(min = 0, max = 3, steps = 1)
  private final ConfigProperty<Integer> earlierLines = new ConfigProperty<>(0);

  @SwitchSetting
  private final ConfigProperty<Boolean> nextLine = new ConfigProperty<>(false);

  @SwitchSetting
  private final ConfigProperty<Boolean> breakDots = new ConfigProperty<>(false);

  @SliderSetting(min = -5000, max = 5000, steps = 50)
  private final ConfigProperty<Integer> syncAllMs = new ConfigProperty<>(0);

  @SettingSection("card")
  @SwitchSetting
  private final ConfigProperty<Boolean> showCard = new ConfigProperty<>(true);

  @SettingRequires("showCard")
  @SwitchSetting
  private final ConfigProperty<Boolean> cardCover = new ConfigProperty<>(true);

  @SettingRequires("showCard")
  @SwitchSetting
  private final ConfigProperty<Boolean> cardTimes = new ConfigProperty<>(true);

  @SettingRequires("showCard")
  @SwitchSetting
  private final ConfigProperty<Boolean> cardLine = new ConfigProperty<>(false);

  @SettingSection("head")
  @SwitchSetting
  private final ConfigProperty<Boolean> ownCard = new ConfigProperty<>(true);

  @SwitchSetting
  private final ConfigProperty<Boolean> shareSong = new ConfigProperty<>(false);

  @SwitchSetting
  private final ConfigProperty<Boolean> showShared = new ConfigProperty<>(true);

  @SwitchSetting
  private final ConfigProperty<Boolean> teamRoles = new ConfigProperty<>(true);

  @SettingSection("keys")
  @KeyBindSetting
  private final ConfigProperty<Key> menuKey = new ConfigProperty<>(Key.B);

  @KeyBindSetting
  private final ConfigProperty<Key> syncEarlierKey = new ConfigProperty<>(Key.NONE);

  @KeyBindSetting
  private final ConfigProperty<Key> syncLaterKey = new ConfigProperty<>(Key.NONE);

  @KeyBindSetting
  private final ConfigProperty<Key> hideKey = new ConfigProperty<>(Key.NONE);

  @SettingSection("menu")
  @MethodOrder(after = "hideKey")
  @ButtonSetting
  public void openMenu(Setting setting) {
    LyricaAddon addon = LyricaAddon.get();
    if (addon != null) {
      addon.openMenu();
    }
  }

  @Override
  public ConfigProperty<Boolean> enabled() {
    return this.enabled;
  }

  /** Each switch of the look with the property that stands for it on the settings screen. */
  Map<LookSwitch, ConfigProperty<Boolean>> lookSwitches() {
    Map<LookSwitch, ConfigProperty<Boolean>> switches = new EnumMap<>(LookSwitch.class);
    switches.put(LookSwitch.LYRICS, this.showLyrics);
    switches.put(LookSwitch.HIDE_WHEN_PAUSED, this.hideWhenPaused);
    switches.put(LookSwitch.KARAOKE, this.karaoke);
    switches.put(LookSwitch.NEXT_LINE, this.nextLine);
    switches.put(LookSwitch.BREAK_DOTS, this.breakDots);
    switches.put(LookSwitch.CARD, this.showCard);
    switches.put(LookSwitch.CARD_COVER, this.cardCover);
    switches.put(LookSwitch.CARD_TIMES, this.cardTimes);
    switches.put(LookSwitch.CARD_LINE, this.cardLine);
    switches.put(LookSwitch.OWN_CARD, this.ownCard);
    switches.put(LookSwitch.SHARE_SONG, this.shareSong);
    switches.put(LookSwitch.SHOW_SHARED, this.showShared);
    switches.put(LookSwitch.TEAM_ROLES, this.teamRoles);
    switches.put(LookSwitch.SPOTIFY_ONLY, this.spotifyOnly);
    return switches;
  }

  ConfigProperty<Integer> earlierLines() {
    return this.earlierLines;
  }

  ConfigProperty<Integer> syncAllMs() {
    return this.syncAllMs;
  }

  public ConfigProperty<Key> menuKey() {
    return this.menuKey;
  }

  public ConfigProperty<Key> syncEarlierKey() {
    return this.syncEarlierKey;
  }

  public ConfigProperty<Key> syncLaterKey() {
    return this.syncLaterKey;
  }

  public ConfigProperty<Key> hideKey() {
    return this.hideKey;
  }
}
