package net.lyrica.v1_21_11.menu.controls;

import java.util.List;
import java.util.function.Supplier;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** Words that do nothing but inform: fixed text wrapped over lines, or a live one-liner shown whole in the hint line. */
public final class Note extends Control {

  private static final int LEADING = 10;

  private final Supplier<String> changing;
  private List<FormattedCharSequence> lines = List.of();
  private int color = Ink.DIM;

  /** Wrapped text from a key. */
  public Note(String key) {
    super(words(key), "");
    this.changing = null;
  }

  /** A single line that can change from frame to frame, and may be empty. */
  public Note(Supplier<String> text) {
    super("", "");
    this.changing = text;
  }

  public Note color(int argb) {
    this.color = argb;
    return this;
  }

  @Override
  public int heightFor(Font font, int width) {
    if (this.changing != null) {
      return Ink.LINE;
    }

    this.lines = font.split(Component.literal(this.label), width - Ink.GUTTER);
    return this.lines.size() * LEADING + 3;
  }

  @Override
  protected void draw(GuiGraphics g, Font font, int mx, int my, boolean hot, boolean live) {
    int left = this.x + Ink.GUTTER;
    if (this.changing != null) {
      g.drawString(font, Ink.fit(font, this.changing.get(), this.w - Ink.GUTTER), left, this.y + Ink.TEXT_DROP, this.color,
          false);
      return;
    }

    for (int i = 0; i < this.lines.size(); i++) {
      g.drawString(font, this.lines.get(i), left, this.y + 1 + i * LEADING, this.color, false);
    }
  }

  @Override
  public String hint(double mx, double my) {
    return this.changing != null ? this.changing.get() : this.label;
  }
}
