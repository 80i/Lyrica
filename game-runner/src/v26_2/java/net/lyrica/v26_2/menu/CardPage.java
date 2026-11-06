package net.lyrica.v26_2.menu;

import net.labymod.api.util.I18n;
import net.lyrica.v26_2.hud.NowCard;
import net.lyrica.v26_2.menu.controls.Action;
import net.lyrica.v26_2.menu.controls.Chips;
import net.lyrica.v26_2.menu.controls.Fader;
import net.lyrica.v26_2.menu.controls.Ink;
import net.lyrica.v26_2.menu.controls.Lamp;
import net.lyrica.v26_2.menu.controls.Note;
import net.lyrica.v26_2.menu.controls.Swatches;

/** The now-playing card on the screen: its form, where it sits, what it shows. */
final class CardPage extends Page {

  private static final String[] STYLES = {"STRIP", "DISC", "COVER", "TICKER", "NONE"};
  private static final String[] CORNERS = {"TOP_LEFT", "TOP_RIGHT", "BOTTOM_LEFT", "BOTTOM_RIGHT"};

  private final String hiddenWords = I18n.translate("lyrical.menu.card.behind_desk");

  CardPage() {
    super("card");
  }

  @Override
  protected void build() {
    this.columns.group("lyrical.menu.card.form")
        .add(new Lamp("lyrical.menu.card.on", () -> this.look.cardOn, value -> this.look.cardOn = value,
            () -> base.cardOn))
        .add(new Chips("lyrical.menu.card.style", STYLES, () -> this.look.cardStyle,
            value -> this.look.cardStyle = value, () -> base.cardStyle).when(() -> this.look.cardOn));

    this.columns.group("lyrical.menu.card.where")
        .add(new Chips("lyrical.menu.card.corner", CORNERS, () -> this.look.cardCorner, this::setCorner,
            () -> base.cardCorner).when(() -> this.look.cardOn))
        .add(new Action("lyrical.menu.card.back_to_corner", this::backToCorner).when(this::wasDragged))
        .add(new Note(this::hiddenNote).color(Ink.ACCENT));

    this.columns.group("lyrical.menu.card.measure")
        .add(new Fader("lyrical.menu.card.size", () -> this.look.cardSize, value -> this.look.cardSize = value,
            () -> base.cardSize, 0.5F, 2.5F, 0.05F, Fader.TWO_PLACES).when(() -> this.look.cardOn))
        .add(new Fader("lyrical.menu.card.opacity", () -> this.look.cardOpacity,
            value -> this.look.cardOpacity = value, () -> base.cardOpacity, 0.1F, 1.0F, 0.01F, Fader.PERCENT)
            .when(() -> this.look.cardOn));

    this.columns.group("lyrical.menu.card.shows")
        .add(new Lamp("lyrical.menu.card.cover", () -> this.look.cardCover, value -> this.look.cardCover = value,
            () -> base.cardCover).when(() -> this.look.cardOn))
        .add(new Lamp("lyrical.menu.card.times", () -> this.look.cardTimes, value -> this.look.cardTimes = value,
            () -> base.cardTimes).when(() -> this.look.cardOn))
        .add(new Lamp("lyrical.menu.card.line", () -> this.look.cardLine, value -> this.look.cardLine = value,
            () -> base.cardLine).when(() -> this.look.cardOn));

    this.columns.group("lyrical.menu.card.accent_group")
        .add(new Swatches("lyrical.menu.card.accent", () -> this.look.cardAccent,
            value -> this.look.cardAccent = value, () -> base.cardAccent).when(() -> this.look.cardOn));
  }

  /** Where the card was dragged to is no control's value, and goes back with the rest. */
  @Override
  void resetAll() {
    super.resetAll();
    this.look.cardX = base.cardX;
    this.look.cardY = base.cardY;
  }

  private void setCorner(String corner) {
    this.look.cardCorner = corner;
    this.backToCorner();
  }

  private void backToCorner() {
    this.look.cardX = -1.0F;
    this.look.cardY = -1.0F;
  }

  private boolean wasDragged() {
    return this.look.cardOn && (this.look.cardX >= 0.0F || this.look.cardY >= 0.0F);
  }

  /** The desk covers the bottom of the screen, and a card under it could not be seen or grabbed. */
  private String hiddenNote() {
    if (!this.look.cardOn || "NONE".equals(this.look.cardStyle)) {
      return "";
    }

    NowCard.Bounds bounds = NowCard.bounds(this.look);
    return bounds.y() >= this.top - StudioScreen.TAB_HEIGHT ? this.hiddenWords : "";
  }
}
