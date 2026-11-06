package net.lyrica.v26_1_2.menu.controls;

import java.util.function.BooleanSupplier;
import net.labymod.api.util.I18n;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;

/**
 * One line (or a few) on the desk. A column asks for its height, places it, then draws it and hands it input in its own
 * coordinates. Labels and descriptions are translated once at creation.
 */
public abstract class Control {

  public int x;
  public int y;
  public int w;
  public int h;

  protected final String label;
  protected final String description;

  private BooleanSupplier when;
  private String fittedLabel;
  private int fittedFor = -1;

  protected Control(String label, String description) {
    this.label = label;
    this.description = description;
  }

  protected static String words(String key) {
    return I18n.translate(key);
  }

  /** The control stays in place but goes dark and deaf while this says no: its setting does nothing right now. */
  public Control when(BooleanSupplier condition) {
    this.when = condition;
    return this;
  }

  public final boolean enabled() {
    return this.when == null || this.when.getAsBoolean();
  }

  public int heightFor(Font font, int width) {
    return Ink.LINE;
  }

  public void place(int x, int y, int w, int h) {
    this.x = x;
    this.y = y;
    this.w = w;
    this.h = h;
  }

  public final void render(GuiGraphicsExtractor g, Font font, int mx, int my, boolean hover) {
    boolean live = this.enabled();
    boolean hot = live && hover;
    if (live && this.isChanged()) {
      boolean overButton = hot && mx < this.x + Ink.GUTTER;
      Ink.undo(g, this.x + 1, this.y + (Ink.LINE - Ink.UNDO_SIZE) / 2, overButton ? Ink.TEXT : Ink.ACCENT);
    }

    this.draw(g, font, mx, my, hot, live);
  }

  /** Puts its value back if it has been changed, also while it is dark. */
  public final void resetIfChanged() {
    if (this.isChanged()) {
      this.reset();
    }
  }

  /** @return true when the control wants the drag that follows */
  public final boolean press(double mx, double my, int button) {
    if (!this.enabled()) {
      return false;
    }

    if (this.isChanged() && mx < this.x + Ink.GUTTER) {
      this.reset();
      Ink.click(0.8F);
      return false;
    }

    return this.pressed(mx, my, button);
  }

  public String hint(double mx, double my) {
    if (this.enabled() && this.isChanged() && mx < this.x + Ink.GUTTER) {
      return words("lyrical.menu.reset");
    }

    return this.description;
  }

  protected abstract void draw(GuiGraphicsExtractor g, Font font, int mx, int my, boolean hot, boolean live);

  protected boolean pressed(double mx, double my, int button) {
    return false;
  }

  public void drag(double mx, double my) {
  }

  public void release(double mx, double my) {
  }

  public boolean wheel(double amount) {
    return false;
  }

  public boolean wantsKeys() {
    return false;
  }

  public void blur() {
  }

  public boolean key(KeyEvent event) {
    return false;
  }

  public boolean chars(CharacterEvent event) {
    return false;
  }

  protected boolean isChanged() {
    return false;
  }

  protected void reset() {
  }

  /** Draws the label at the left of the first line, cut to the width it is given. */
  protected final void drawLabel(GuiGraphicsExtractor g, Font font, int width, int color) {
    if (width != this.fittedFor) {
      this.fittedLabel = Ink.fit(font, this.label, width);
      this.fittedFor = width;
    }

    g.text(font, this.fittedLabel, this.x + Ink.GUTTER, this.y + Ink.TEXT_DROP, color, false);
  }

  protected static int labelColor(boolean hot, boolean live) {
    if (!live) {
      return Ink.FAINT;
    }

    return hot ? Ink.TEXT : Ink.DIM;
  }
}
