package net.splyrics.v1_21_11.menu;

import net.splyrics.v1_21_11.look.Fonts;
import net.splyrics.v1_21_11.menu.controls.Chips;
import net.splyrics.v1_21_11.menu.controls.Fader;
import net.splyrics.v1_21_11.menu.controls.Lamp;

/** Which letters: the font, each named in itself, and how big and how far apart they stand. */
final class TypePage extends Page {

  TypePage() {
    super("type");
  }

  @Override
  protected void build() {
    this.columns.group("splyrics.menu.type.face")
        .add(new Chips("splyrics.menu.type.font", Fonts.NAMES, () -> this.look.font, value -> this.look.font = value,
            () -> base.font).faces(name -> Fonts.style(name, false)))
        .add(new Lamp("splyrics.menu.type.bold", () -> this.look.bold, value -> this.look.bold = value, () -> base.bold))
        .add(new Lamp("splyrics.menu.type.upper", () -> this.look.upper, value -> this.look.upper = value,
            () -> base.upper));

    this.columns.group("splyrics.menu.type.measure")
        .add(new Fader("splyrics.menu.type.size", () -> this.look.size, value -> this.look.size = value, () -> base.size,
            0.3F, 3.0F, 0.05F, Fader.TWO_PLACES))
        .add(new Fader("splyrics.menu.type.letter_spacing", () -> this.look.letterSpacing,
            value -> this.look.letterSpacing = value, () -> base.letterSpacing, -1.0F, 6.0F, 0.1F, Fader.ONE_PLACE))
        .add(new Fader("splyrics.menu.type.line_gap", () -> this.look.lineGap, value -> this.look.lineGap = value,
            () -> base.lineGap, -4.0F, 12.0F, 0.5F, Fader.ONE_PLACE))
        .add(new Fader("splyrics.menu.type.wrap_width", () -> this.look.wrapWidth,
            value -> this.look.wrapWidth = Math.round(value), () -> base.wrapWidth, 40.0F, 400.0F, 5.0F, Fader.WHOLE));
  }
}
