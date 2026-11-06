package net.lyrica.v26_3.menu.controls;

import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * One value out of a list, between two arrows. Click a side or turn the wheel to step; it wraps round. The words come
 * from {@code <key>.<value in lower case>}, unless literal ones are given.
 */
public final class Dial extends Control {

  private static final int ARROW = 3;

  private final String[] values;
  private final String[] names;
  private final Supplier<String> get;
  private final Consumer<String> set;
  private final Supplier<String> plain;

  private int labelWidth;
  private int areaLeft;
  private int areaRight;

  public Dial(String key, String[] values, Supplier<String> get, Consumer<String> set, Supplier<String> plain) {
    this(key, values, translated(key, values), get, set, plain);
  }

  public Dial(String key, String[] values, String[] names, Supplier<String> get, Consumer<String> set,
      Supplier<String> plain) {
    super(words(key), words(key + ".desc"));
    this.values = values;
    this.names = names;
    this.get = get;
    this.set = set;
    this.plain = plain;
  }

  private static String[] translated(String key, String[] values) {
    String[] names = new String[values.length];
    for (int i = 0; i < values.length; i++) {
      names[i] = words(key + "." + values[i].toLowerCase(Locale.ROOT));
    }

    return names;
  }

  @Override
  public void place(int x, int y, int w, int h) {
    super.place(x, y, w, h);
    int inner = w - Ink.GUTTER;
    this.labelWidth = inner * 2 / 5;
    this.areaLeft = x + Ink.GUTTER + this.labelWidth + 3;
    this.areaRight = x + w - 1;
  }

  @Override
  protected void draw(GuiGraphicsExtractor g, Font font, int mx, int my, boolean hot, boolean live) {
    this.drawLabel(g, font, this.labelWidth, labelColor(hot, live));
    boolean inArea = mx >= this.areaLeft;
    boolean onLeft = mx < this.middle();
    int arrowTop = this.y + (Ink.LINE - 5) / 2;
    int leftColor = !live ? Ink.FAINT : hot && inArea && onLeft ? Ink.ACCENT : Ink.DIM;
    int rightColor = !live ? Ink.FAINT : hot && inArea && !onLeft ? Ink.ACCENT : Ink.DIM;
    Ink.arrow(g, this.areaLeft, arrowTop, false, leftColor);
    Ink.arrow(g, this.areaRight - ARROW, arrowTop, true, rightColor);

    int room = this.areaRight - this.areaLeft - ARROW * 2 - 4;
    String name = Ink.fit(font, this.names[this.index()], room);
    g.text(font, name, this.middle() - font.width(name) / 2, this.y + Ink.TEXT_DROP, live ? Ink.TEXT : Ink.FAINT,
        false);
  }

  @Override
  protected boolean pressed(double mx, double my, int button) {
    if (mx < this.areaLeft) {
      return false;
    }

    boolean back = mx < this.middle();
    this.step(back ? -1 : 1);
    Ink.click(back ? 0.95F : 1.05F);
    return false;
  }

  @Override
  public boolean wheel(double amount) {
    if (amount == 0.0) {
      return false;
    }

    this.step(amount > 0.0 ? -1 : 1);
    Ink.click(1.0F);
    return true;
  }

  @Override
  protected boolean isChanged() {
    return !this.plain.get().equals(this.get.get());
  }

  @Override
  protected void reset() {
    this.set.accept(this.plain.get());
  }

  private void step(int by) {
    int count = this.values.length;
    this.set.accept(this.values[(this.index() + by + count) % count]);
  }

  private int index() {
    String now = this.get.get();
    for (int i = 0; i < this.values.length; i++) {
      if (this.values[i].equals(now)) {
        return i;
      }
    }

    return 0;
  }

  private int middle() {
    return (this.areaLeft + this.areaRight) / 2;
  }
}
