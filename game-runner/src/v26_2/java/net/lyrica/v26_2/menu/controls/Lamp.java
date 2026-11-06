package net.lyrica.v26_2.menu.controls;

import java.util.function.BooleanSupplier;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** An on/off setting: a small square that lights up. The whole line is the switch. */
public final class Lamp extends Control {

  public interface Set {

    void set(boolean value);
  }

  private static final int SIZE = 7;

  private final BooleanSupplier get;
  private final Set set;
  private final BooleanSupplier plain;

  public Lamp(String key, BooleanSupplier get, Set set, BooleanSupplier plain) {
    super(words(key), words(key + ".desc"));
    this.get = get;
    this.set = set;
    this.plain = plain;
  }

  @Override
  protected void draw(GuiGraphicsExtractor g, Font font, int mx, int my, boolean hot, boolean live) {
    this.drawLabel(g, font, this.w - Ink.GUTTER - SIZE - 4, labelColor(hot, live));
    int left = this.x + this.w - SIZE - 1;
    int top = this.y + (Ink.LINE - SIZE) / 2;
    if (this.get.getAsBoolean()) {
      g.fill(left, top, left + SIZE, top + SIZE, live ? Ink.ACCENT : Ink.FAINT);
    } else {
      Ink.frame(g, left, top, SIZE, SIZE, hot ? Ink.TEXT : live ? Ink.HAIR_BRIGHT : Ink.HAIR);
    }
  }

  @Override
  protected boolean pressed(double mx, double my, int button) {
    boolean now = !this.get.getAsBoolean();
    this.set.set(now);
    Ink.click(now ? 1.5F : 1.1F);
    return false;
  }

  @Override
  protected boolean isChanged() {
    return this.get.getAsBoolean() != this.plain.getAsBoolean();
  }

  @Override
  protected void reset() {
    this.set.set(this.plain.getAsBoolean());
  }
}
