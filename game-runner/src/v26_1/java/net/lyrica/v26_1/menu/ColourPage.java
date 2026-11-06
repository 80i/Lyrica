package net.lyrica.v26_1.menu;

import net.lyrica.v26_1.menu.controls.Chips;
import net.lyrica.v26_1.menu.controls.Fader;
import net.lyrica.v26_1.menu.controls.Swatches;

/** The colour of the letters. Controls the chosen mode does not use go dark rather than disappear. */
final class ColourPage extends Page {

  private static final String[] MODES = {"SOLID", "GRADIENT", "RAINBOW", "ALBUM", "ALBUM_GRADIENT", "PER_LINE"};

  ColourPage() {
    super("colour");
  }

  @Override
  protected void build() {
    this.columns.group("lyrical.menu.colour.mode_group")
        .add(new Chips("lyrical.menu.colour.mode", MODES, () -> this.look.colorMode,
            value -> this.look.colorMode = value, () -> base.colorMode))
        .add(new Fader("lyrical.menu.colour.flow", () -> this.look.flowSpeed, value -> this.look.flowSpeed = value,
            () -> base.flowSpeed, 0.0F, 4.0F, 0.1F, Fader.ONE_PLACE).when(this::flows));

    this.columns.group("lyrical.menu.colour.colours")
        .add(new Swatches("lyrical.menu.colour.first", () -> this.look.color, value -> this.look.color = value,
            () -> base.color).when(this::usesFirst))
        .add(new Swatches("lyrical.menu.colour.second", () -> this.look.color2, value -> this.look.color2 = value,
            () -> base.color2).when(this::usesSecond));
  }

  private boolean flows() {
    String mode = this.look.colorMode;
    return "GRADIENT".equals(mode) || "RAINBOW".equals(mode) || "ALBUM_GRADIENT".equals(mode);
  }

  private boolean usesFirst() {
    String mode = this.look.colorMode;
    return "SOLID".equals(mode) || "GRADIENT".equals(mode);
  }

  private boolean usesSecond() {
    return "GRADIENT".equals(this.look.colorMode) || (this.look.karaoke && "FILL".equals(this.look.karaokeStyle));
  }
}
