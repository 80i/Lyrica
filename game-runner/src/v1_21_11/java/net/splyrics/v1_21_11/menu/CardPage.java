package net.splyrics.v1_21_11.menu;

import net.labymod.api.util.I18n;
import net.splyrics.v1_21_11.hud.NowCard;
import net.splyrics.v1_21_11.menu.controls.Action;
import net.splyrics.v1_21_11.menu.controls.Chips;
import net.splyrics.v1_21_11.menu.controls.Fader;
import net.splyrics.v1_21_11.menu.controls.Ink;
import net.splyrics.v1_21_11.menu.controls.Lamp;
import net.splyrics.v1_21_11.menu.controls.Note;
import net.splyrics.v1_21_11.menu.controls.Swatches;

/** The now-playing card on the screen: its form, where it sits, what it shows. */
final class CardPage extends Page {

  private static final String[] STYLES = {"STRIP", "DISC", "COVER", "TICKER", "NONE"};
  private static final String[] CORNERS = {"TOP_LEFT", "TOP_RIGHT", "BOTTOM_LEFT", "BOTTOM_RIGHT"};

  private final String hiddenWords = I18n.translate("splyrics.menu.card.behind_desk");

  CardPage() {
    super("card");
  }

  @Override
  protected void build() {
    this.columns.group("splyrics.menu.card.form")
        .add(new Lamp("splyrics.menu.card.on", () -> this.look.cardOn, value -> this.look.cardOn = value,
            () -> base.cardOn))
        .add(new Chips("splyrics.menu.card.style", STYLES, () -> this.look.cardStyle,
            value -> this.look.cardStyle = value, () -> base.cardStyle).when(() -> this.look.cardOn));

    this.columns.group("splyrics.menu.card.where")
        .add(new Chips("splyrics.menu.card.corner", CORNERS, () -> this.look.cardCorner, this::setCorner,
            () -> base.cardCorner).when(() -> this.look.cardOn))
        .add(new Action("splyrics.menu.card.back_to_corner", this::backToCorner).when(this::wasDragged))
        .add(new Note(this::hiddenNote).color(Ink.ACCENT));

    this.columns.group("splyrics.menu.card.measure")
        .add(new Fader("splyrics.menu.card.size", () -> this.look.cardSize, value -> this.look.cardSize = value,
            () -> base.cardSize, 0.5F, 2.5F, 0.05F, Fader.TWO_PLACES).when(() -> this.look.cardOn))
        .add(new Fader("splyrics.menu.card.opacity", () -> this.look.cardOpacity,
            value -> this.look.cardOpacity = value, () -> base.cardOpacity, 0.1F, 1.0F, 0.01F, Fader.PERCENT)
            .when(() -> this.look.cardOn));

    this.columns.group("splyrics.menu.card.shows")
        .add(new Lamp("splyrics.menu.card.cover", () -> this.look.cardCover, value -> this.look.cardCover = value,
            () -> base.cardCover).when(() -> this.look.cardOn))
        .add(new Lamp("splyrics.menu.card.times", () -> this.look.cardTimes, value -> this.look.cardTimes = value,
            () -> base.cardTimes).when(() -> this.look.cardOn))
        .add(new Lamp("splyrics.menu.card.line", () -> this.look.cardLine, value -> this.look.cardLine = value,
            () -> base.cardLine).when(() -> this.look.cardOn));

    this.columns.group("splyrics.menu.card.accent_group")
        .add(new Swatches("splyrics.menu.card.accent", () -> this.look.cardAccent,
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
