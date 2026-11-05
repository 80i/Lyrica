package net.splyrics.core;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.labymod.api.configuration.loader.property.ConfigProperty;
import net.splyrics.api.LyricsBridge;

/**
 * Keeps the settings on LabyMod's settings screen and the look saying the same. The look is the one that counts, so what
 * the settings file says about them is overwritten on start: a change made in the menu, by Reset all or with the hide key
 * shows here within a tick, and a change made here goes straight to the look.
 */
public final class LookSettings {

  private final List<Mirror<?>> mirrors = new ArrayList<>();

  LookSettings(LyricsBridge bridge, SpLyricsConfiguration config) {
    config.lookSwitches().forEach((which, property) -> this.mirrors.add(
        new Mirror<>(property, () -> bridge.isOn(which), on -> bridge.turn(which, on))));
    this.mirrors.add(new Mirror<>(config.earlierLines(), bridge::earlierLines, bridge::earlierLines));
    this.mirrors.add(new Mirror<>(config.syncAllMs(), bridge::syncAllMs, bridge::syncAllMs));
  }

  /** Once a tick. */
  public void follow() {
    for (int i = 0; i < this.mirrors.size(); i++) {
      this.mirrors.get(i).follow();
    }
  }

  private static final class Mirror<T> {

    private final ConfigProperty<T> property;
    private final Supplier<T> look;

    private Mirror(ConfigProperty<T> property, Supplier<T> look, Consumer<T> apply) {
      this.property = property;
      this.look = look;
      property.set(look.get());
      property.addChangeListener((Consumer<T>) value -> {
        // Also called when follow() brings the setting into line, when there is nothing left to change
        if (!look.get().equals(value)) {
          apply.accept(value);
        }
      });
    }

    private void follow() {
      T now = this.look.get();
      if (!now.equals(this.property.get())) {
        this.property.set(now);
      }
    }
  }
}
