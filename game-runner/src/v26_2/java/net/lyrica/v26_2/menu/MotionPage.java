package net.lyrica.v26_2.menu;

import net.lyrica.v26_2.menu.controls.Chips;
import net.lyrica.v26_2.menu.controls.Dial;
import net.lyrica.v26_2.menu.controls.Fader;
import net.lyrica.v26_2.menu.controls.Lamp;

/** How a line is arranged, how it arrives and leaves, what it does meanwhile, and how it follows the singing. */
final class MotionPage extends Page {

  private static final String[] ARRANGE = {"LINE", "WORDS", "STAIRS", "ARC", "STACK"};
  private static final String[] ENTERS = {
      "DROP", "RISE_UP", "FLY_IN", "FLIP", "UNFOLD", "TYPE", "SCATTER_IN", "FADE", "NONE"
  };
  private static final String[] EXITS = {
      "SINK", "TIP_OVER", "BLOW_AWAY", "FLY_OUT", "SHATTER", "ERASE", "FADE", "NONE"
  };
  private static final String[] IDLES = {
      "NONE", "BREATHE", "BOB", "SWAY", "WAVE", "RIPPLE", "JITTER", "FLICKER", "GLITCH", "ORBIT_LETTERS"
  };
  private static final String[] KARAOKE = {"DIM", "SWEEP", "FILL", "LIFT"};

  MotionPage() {
    super("motion");
  }

  @Override
  protected void build() {
    this.columns.group("lyrica.menu.motion.shape")
        .add(new Chips("lyrica.menu.motion.arrange", ARRANGE, () -> this.look.arrange,
            value -> this.look.arrange = value, () -> base.arrange));

    this.columns.group("lyrica.menu.motion.in_out")
        .add(new Dial("lyrica.menu.motion.enter", ENTERS, () -> this.look.enter, value -> this.look.enter = value,
            () -> base.enter))
        .add(new Dial("lyrica.menu.motion.exit", EXITS, () -> this.look.exit, value -> this.look.exit = value,
            () -> base.exit));

    this.columns.group("lyrica.menu.motion.meanwhile")
        .add(new Dial("lyrica.menu.motion.idle", IDLES, () -> this.look.idle, value -> this.look.idle = value,
            () -> base.idle))
        .add(new Fader("lyrica.menu.motion.idle_speed", () -> this.look.idleSpeed,
            value -> this.look.idleSpeed = value, () -> base.idleSpeed, 0.1F, 3.0F, 0.05F, Fader.TWO_PLACES)
            .when(this::idles))
        .add(new Fader("lyrica.menu.motion.idle_amount", () -> this.look.idleAmount,
            value -> this.look.idleAmount = value, () -> base.idleAmount, 0.0F, 2.0F, 0.05F, Fader.TWO_PLACES)
            .when(this::idles));

    this.columns.group("lyrica.menu.motion.singing")
        .add(new Lamp("lyrica.menu.motion.karaoke", () -> this.look.karaoke, value -> this.look.karaoke = value,
            () -> base.karaoke))
        .add(new Chips("lyrica.menu.motion.karaoke_style", KARAOKE, () -> this.look.karaokeStyle,
            value -> this.look.karaokeStyle = value, () -> base.karaokeStyle).when(() -> this.look.karaoke));
  }

  private boolean idles() {
    return !"NONE".equals(this.look.idle);
  }
}
