package net.lyrica.v1_21_11.menu;

import net.lyrica.v1_21_11.menu.controls.Chips;
import net.lyrica.v1_21_11.menu.controls.Fader;
import net.lyrica.v1_21_11.menu.controls.Lamp;
import net.lyrica.v1_21_11.menu.controls.Swatches;

/** The letters as objects: their body, their edge, and how they take and give light. */
final class SolidPage extends Page {

  private static final String[] GLOWS = {"OFF", "SOFT", "BRIGHT"};
  private static final String[] GLOW_COLOURS = {"MATCH", "CUSTOM"};

  SolidPage() {
    super("solid");
  }

  @Override
  protected void build() {
    this.columns.group("lyrical.menu.solid.form")
        .add(new Fader("lyrical.menu.solid.depth", () -> this.look.depth, value -> this.look.depth = value,
            () -> base.depth, 0.0F, 4.0F, 0.05F, Fader.TWO_PLACES))
        .add(new Fader("lyrical.menu.solid.side_shade", () -> this.look.sideShade,
            value -> this.look.sideShade = value, () -> base.sideShade, 0.0F, 1.0F, 0.01F, Fader.TWO_PLACES))
        .add(new Fader("lyrical.menu.solid.bend", () -> this.look.bend, value -> this.look.bend = value,
            () -> base.bend, 0.0F, 1.5F, 0.01F, Fader.TWO_PLACES));

    this.columns.group("lyrical.menu.solid.edge")
        .add(new Lamp("lyrical.menu.solid.outline", () -> this.look.outline, value -> this.look.outline = value,
            () -> base.outline))
        .add(new Swatches("lyrical.menu.solid.outline_color", () -> this.look.outlineColor,
            value -> this.look.outlineColor = value, () -> base.outlineColor).when(() -> this.look.outline));

    this.columns.group("lyrical.menu.solid.light")
        .add(new Lamp("lyrical.menu.solid.self_lit", () -> this.look.selfLit, value -> this.look.selfLit = value,
            () -> base.selfLit))
        .add(new Lamp("lyrical.menu.solid.shadow", () -> this.look.shadow, value -> this.look.shadow = value,
            () -> base.shadow))
        .add(new Lamp("lyrical.menu.solid.through_walls", () -> this.look.throughWalls,
            value -> this.look.throughWalls = value, () -> base.throughWalls))
        .add(new Lamp("lyrical.menu.solid.sparkles", () -> this.look.sparkles, value -> this.look.sparkles = value,
            () -> base.sparkles));

    this.columns.group("lyrical.menu.solid.glow_group")
        .add(new Chips("lyrical.menu.solid.glow", GLOWS, () -> this.look.glow, value -> this.look.glow = value,
            () -> base.glow))
        .add(new Chips("lyrical.menu.solid.glow_mode", GLOW_COLOURS, () -> this.look.glowColorMode,
            value -> this.look.glowColorMode = value, () -> base.glowColorMode).when(this::glows))
        .add(new Fader("lyrical.menu.solid.glow_reach", () -> this.look.glowReach,
            value -> this.look.glowReach = value, () -> base.glowReach, 0.3F, 2.5F, 0.05F, Fader.TWO_PLACES)
            .when(this::glows))
        .add(new Swatches("lyrical.menu.solid.glow_color", () -> this.look.glowColor,
            value -> this.look.glowColor = value, () -> base.glowColor)
            .when(() -> this.glows() && "CUSTOM".equals(this.look.glowColorMode)));
  }

  private boolean glows() {
    return !"OFF".equals(this.look.glow);
  }
}
