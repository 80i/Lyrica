package net.splyrics.v1_21_11.menu.controls;

import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

/**
 * One value out of a short list, all in view as small words, the chosen one filled. They sit right of the label when
 * they fit there, otherwise they flow in rows below it. The words come from {@code <key>.<value in lower case>}.
 */
public final class Chips extends Control {

  private static final int CHIP_HEIGHT = 11;
  private static final int PADDING = 3;
  private static final int GAP = 2;

  private final String[] values;
  private final String[] names;
  private final Supplier<String> get;
  private final Consumer<String> set;
  private final Supplier<String> plain;
  private final int[] chipX;
  private final int[] chipY;
  private final int[] chipW;

  private FormattedCharSequence[] sequences;
  private Function<String, Style> faces;
  private boolean labelOnTop;

  public Chips(String key, String[] values, Supplier<String> get, Consumer<String> set, Supplier<String> plain) {
    super(words(key), words(key + ".desc"));
    this.values = values;
    this.names = new String[values.length];
    for (int i = 0; i < values.length; i++) {
      this.names[i] = words(key + "." + values[i].toLowerCase(Locale.ROOT));
    }

    this.get = get;
    this.set = set;
    this.plain = plain;
    this.chipX = new int[values.length];
    this.chipY = new int[values.length];
    this.chipW = new int[values.length];
  }

  /** Chips whose words are not translations but somebody's own, like the names of the roles on the list. */
  public Chips(String key, String[] values, String[] names, Supplier<String> get, Consumer<String> set,
      Supplier<String> plain) {
    super(words(key), words(key + ".desc"));
    this.values = values;
    this.names = names;
    this.get = get;
    this.set = set;
    this.plain = plain;
    this.chipX = new int[values.length];
    this.chipY = new int[values.length];
    this.chipW = new int[values.length];
  }

  /** Sets each word in a look of its own, the way the font list shows each font in itself. */
  public Chips faces(Function<String, Style> faceOf) {
    this.faces = faceOf;
    return this;
  }

  @Override
  public int heightFor(Font font, int width) {
    this.measure(font);
    int total = 0;
    for (int i = 0; i < this.values.length; i++) {
      total += this.chipW[i] + (i == 0 ? 0 : GAP);
    }

    this.labelOnTop = Ink.GUTTER + font.width(this.label) + 6 + total > width;
    if (!this.labelOnTop) {
      int at = width - total;
      for (int i = 0; i < this.values.length; i++) {
        this.chipX[i] = at;
        this.chipY[i] = 1;
        at += this.chipW[i] + GAP;
      }

      return Ink.LINE;
    }

    int at = Ink.GUTTER;
    int row = Ink.LINE;
    for (int i = 0; i < this.values.length; i++) {
      if (at > Ink.GUTTER && at + this.chipW[i] > width) {
        at = Ink.GUTTER;
        row += CHIP_HEIGHT + GAP;
      }

      this.chipX[i] = at;
      this.chipY[i] = row;
      at += this.chipW[i] + GAP;
    }

    return row + CHIP_HEIGHT + GAP;
  }

  private void measure(Font font) {
    if (this.sequences != null) {
      return;
    }

    this.sequences = new FormattedCharSequence[this.values.length];
    for (int i = 0; i < this.values.length; i++) {
      Style style = this.faces == null ? Style.EMPTY : this.faces.apply(this.values[i]);
      this.sequences[i] = FormattedCharSequence.forward(this.names[i], style);
      this.chipW[i] = font.width(this.sequences[i]) + PADDING * 2;
    }
  }

  @Override
  protected void draw(GuiGraphics g, Font font, int mx, int my, boolean hot, boolean live) {
    int labelRoom = this.labelOnTop ? this.w - Ink.GUTTER : this.chipX[0] - Ink.GUTTER - 3;
    this.drawLabel(g, font, labelRoom, labelColor(hot, live));
    String now = this.get.get();
    int over = hot ? this.chipAt(mx, my) : -1;
    for (int i = 0; i < this.values.length; i++) {
      int left = this.x + this.chipX[i];
      int top = this.y + this.chipY[i];
      int textColor;
      if (this.values[i].equals(now)) {
        g.fill(left, top, left + this.chipW[i], top + CHIP_HEIGHT, live ? Ink.ACCENT : Ink.FAINT);
        textColor = Ink.BLACK;
      } else if (i == over) {
        Ink.hair(g, left, top + CHIP_HEIGHT - 1, this.chipW[i], Ink.TEXT);
        textColor = Ink.TEXT;
      } else {
        textColor = live ? Ink.DIM : Ink.FAINT;
      }

      g.drawString(font, this.sequences[i], left + PADDING, top + 2, textColor, false);
    }
  }

  @Override
  protected boolean pressed(double mx, double my, int button) {
    int chip = this.chipAt(mx, my);
    if (chip < 0) {
      return false;
    }

    this.set.accept(this.values[chip]);
    Ink.click(1.25F);
    return false;
  }

  @Override
  protected boolean isChanged() {
    return !this.plain.get().equals(this.get.get());
  }

  @Override
  protected void reset() {
    this.set.accept(this.plain.get());
  }

  private int chipAt(double mx, double my) {
    for (int i = 0; i < this.values.length; i++) {
      if (Ink.inside(mx, my, this.x + this.chipX[i], this.y + this.chipY[i], this.chipW[i], CHIP_HEIGHT)) {
        return i;
      }
    }

    return -1;
  }
}
