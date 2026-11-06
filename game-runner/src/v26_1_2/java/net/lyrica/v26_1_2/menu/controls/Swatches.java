package net.lyrica.v26_1_2.menu.controls;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.lyrica.v26_1_2.look.Palette;

/** A colour: the label and a hex field on the first line, a row of squares from the palette below it. */
public final class Swatches extends Control {

  public interface Get {

    int get();
  }

  public interface Set {

    void set(int rgb);
  }

  private static final int HEX_WIDTH = 46;
  private static final int STAMP = 9;
  private static final int LEAST_SQUARE = 5;
  private static final int MOST_SQUARE = 9;
  private static final int GAP = 1;
  private static final float DARK_WHEN_OFF = 0.4F;

  private final Get get;
  private final Set set;
  private final Get plain;
  private final TextLine hex = new TextLine(7, point -> point == '#' || Character.digit(point, 16) >= 0);

  private int square = MOST_SQUARE;
  private int perRow = Palette.SWATCHES.length;
  private boolean editing;
  private int shownRgb = -1;

  public Swatches(String key, Get get, Set set, Get plain) {
    super(words(key), words(key + ".desc"));
    this.get = get;
    this.set = set;
    this.plain = plain;
  }

  @Override
  public int heightFor(Font font, int width) {
    int inner = width - Ink.GUTTER;
    int count = Palette.SWATCHES.length;
    int fitted = (inner + GAP) / count - GAP;
    this.square = Math.max(LEAST_SQUARE, Math.min(MOST_SQUARE, fitted));
    this.perRow = Math.max(1, (inner + GAP) / (this.square + GAP));
    int rows = (count + this.perRow - 1) / this.perRow;
    return Ink.LINE + rows * (this.square + GAP) + 3;
  }

  @Override
  protected void draw(GuiGraphicsExtractor g, Font font, int mx, int my, boolean hot, boolean live) {
    int value = this.get.get() & 0xFFFFFF;
    if (!this.editing && value != this.shownRgb) {
      this.shownRgb = value;
      this.hex.set(Palette.hex(value));
    }

    int hexLeft = this.hexLeft();
    int stampLeft = hexLeft - STAMP - 3;
    this.drawLabel(g, font, stampLeft - this.x - Ink.GUTTER - 3, labelColor(hot, live));
    g.fill(stampLeft, this.y + 2, stampLeft + STAMP, this.y + 2 + STAMP, solid(value, live));
    Ink.frame(g, stampLeft, this.y + 2, STAMP, STAMP, Ink.HAIR_BRIGHT);
    Ink.hair(g, hexLeft, this.y + Ink.LINE - 2, HEX_WIDTH, this.editing ? Ink.ACCENT : Ink.HAIR);
    this.hex.draw(g, font, hexLeft, this.y + 2, HEX_WIDTH, live ? Ink.TEXT : Ink.FAINT, this.editing);

    int over = hot ? this.squareAt(mx, my) : -1;
    for (int i = 0; i < Palette.SWATCHES.length; i++) {
      int left = this.squareLeft(i);
      int top = this.squareTop(i);
      int rgb = Palette.SWATCHES[i];
      g.fill(left, top, left + this.square, top + this.square, solid(rgb, live));
      if (i == over || (live && rgb == value)) {
        Ink.frame(g, left, top, this.square, this.square, rgb == value ? Ink.BLACK : Ink.TEXT);
      }

      if (live && rgb == value) {
        Ink.hair(g, left, top + this.square, this.square, Ink.TEXT);
      }
    }
  }

  private static int solid(int rgb, boolean live) {
    return 0xFF000000 | (live ? rgb : Palette.dim(rgb, DARK_WHEN_OFF));
  }

  @Override
  protected boolean pressed(double mx, double my, int button) {
    if (my < this.y + Ink.LINE) {
      if (mx >= this.hexLeft()) {
        this.editing = true;
        this.hex.clickAt(Minecraft.getInstance().font, mx - this.hexLeft());
      }

      return false;
    }

    this.editing = false;
    int chosen = this.squareAt(mx, my);
    if (chosen >= 0) {
      this.set.set(Palette.SWATCHES[chosen]);
      Ink.click(1.3F);
    }

    return false;
  }

  @Override
  public boolean wantsKeys() {
    return this.editing;
  }

  @Override
  public void blur() {
    this.editing = false;
    // Whatever half-typed text remains gives way to the colour actually in use.
    this.shownRgb = -1;
  }

  @Override
  public boolean key(KeyEvent event) {
    int key = event.key();
    if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) {
      this.blur();
      return true;
    }

    String before = this.hex.text();
    boolean used = this.hex.key(event);
    this.applyIfChanged(before);
    return used;
  }

  @Override
  public boolean chars(CharacterEvent event) {
    String before = this.hex.text();
    boolean used = this.hex.chars(event);
    this.applyIfChanged(before);
    return used;
  }

  private void applyIfChanged(String before) {
    if (!before.equals(this.hex.text())) {
      int parsed = Palette.parseHex(this.hex.text(), -1);
      if (parsed >= 0) {
        this.set.set(parsed);
      }
    }
  }

  @Override
  protected boolean isChanged() {
    return (this.get.get() & 0xFFFFFF) != (this.plain.get() & 0xFFFFFF);
  }

  @Override
  protected void reset() {
    this.editing = false;
    this.shownRgb = -1;
    this.set.set(this.plain.get());
  }

  private int hexLeft() {
    return this.x + this.w - HEX_WIDTH;
  }

  private int squareLeft(int index) {
    return this.x + Ink.GUTTER + index % this.perRow * (this.square + GAP);
  }

  private int squareTop(int index) {
    return this.y + Ink.LINE + index / this.perRow * (this.square + GAP);
  }

  private int squareAt(double mx, double my) {
    for (int i = 0; i < Palette.SWATCHES.length; i++) {
      if (Ink.inside(mx, my, this.squareLeft(i), this.squareTop(i), this.square, this.square)) {
        return i;
      }
    }

    return -1;
  }
}
