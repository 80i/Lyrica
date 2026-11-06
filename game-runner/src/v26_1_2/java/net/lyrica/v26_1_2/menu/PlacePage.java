package net.lyrica.v26_1_2.menu;

import net.labymod.api.util.I18n;
import net.lyrica.v26_1_2.menu.controls.Chips;
import net.lyrica.v26_1_2.menu.controls.Fader;
import net.lyrica.v26_1_2.menu.controls.Lamp;
import net.lyrica.v26_1_2.menu.controls.Note;

/** Where the words stand in the world, or on the screen. */
final class PlacePage extends Page {

  private static final String[] PLACES = {"STAND", "HOVER", "SKY", "WALL", "FLOOR", "ORBIT", "TRAIL", "SCREEN"};
  private static final String[] FACING = {"FIXED", "TURN"};
  private static final String[] SPOTS = {"TOP", "MIDDLE", "BOTTOM"};

  private final String behindDesk = I18n.translate("lyrical.menu.place.behind_desk");

  PlacePage() {
    super("place");
  }

  @Override
  protected void build() {
    this.columns.group("lyrical.menu.place.where")
        .add(new Chips("lyrical.menu.place.place", PLACES, () -> this.look.place, value -> this.look.place = value,
            () -> base.place));

    this.columns.group("lyrical.menu.place.position")
        .add(new Fader("lyrical.menu.place.distance", () -> this.look.distance, value -> this.look.distance = value,
            () -> base.distance, 3.0F, 64.0F, 0.5F, Fader.ONE_PLACE).when(this::inWorld))
        .add(new Fader("lyrical.menu.place.height_add", () -> this.look.heightAdd,
            value -> this.look.heightAdd = value, () -> base.heightAdd, -6.0F, 20.0F, 0.25F, Fader.TWO_PLACES)
            .when(this::inWorld))
        .add(new Fader("lyrical.menu.place.spread", () -> this.look.spread, value -> this.look.spread = value,
            () -> base.spread, 0.0F, 3.0F, 0.05F, Fader.TWO_PLACES).when(this::inWorld));

    this.columns.group("lyrical.menu.place.behaviour")
        .add(new Chips("lyrical.menu.place.facing", FACING, () -> this.look.facing, value -> this.look.facing = value,
            () -> base.facing).when(this::inWorld))
        .add(new Lamp("lyrical.menu.place.carry", () -> this.look.carryWhenFast,
            value -> this.look.carryWhenFast = value, () -> base.carryWhenFast).when(this::inWorld))
        .add(new Fader("lyrical.menu.place.trail_length", () -> this.look.trailLength,
            value -> this.look.trailLength = Math.round(value), () -> base.trailLength, 1.0F, 8.0F, 1.0F, Fader.WHOLE)
            .when(() -> "TRAIL".equals(this.look.place)));

    this.columns.group("lyrical.menu.place.screen")
        .add(new Chips("lyrical.menu.place.screen_spot", SPOTS, () -> this.look.screenSpot,
            value -> this.look.screenSpot = value, () -> base.screenSpot).when(this::onScreen))
        .add(new Fader("lyrical.menu.place.screen_size", () -> this.look.screenSize,
            value -> this.look.screenSize = value, () -> base.screenSize, 0.3F, 3.0F, 0.05F, Fader.TWO_PLACES)
            .when(this::onScreen))
        .add(new Note(() -> this.onScreen() && "BOTTOM".equals(this.look.screenSpot) ? this.behindDesk : ""));
  }

  private boolean inWorld() {
    return !this.onScreen();
  }

  private boolean onScreen() {
    return "SCREEN".equals(this.look.place);
  }
}
